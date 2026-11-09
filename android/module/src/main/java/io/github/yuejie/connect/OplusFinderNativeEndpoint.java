package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.hardware.camera2.CameraManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** FindMyPhone 10.0.8 native RingEventManager. Requires execution inside that original package. */
final class OplusFinderNativeEndpoint implements DeviceFinderController.Backend, AutoCloseable {
    private final Context context;
    private final Object manager, nativeManager;
    private final AudioManager audio;
    private final Runnable interrupted;
    private final CameraManager cameras;
    private final String torchId;
    private volatile Boolean torchActive;
    private boolean torchCallbackRegistered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Object flashLock = new Object();
    private final AtomicInteger queuedFlash = new AtomicInteger();
    private final Constructor<?> playTask;
    private Constructor<?> keyCleanupTask;
    private Method keyCleanup;
    private final Method play, stopNative, closeFlash, advance;
    private final Field player, dialog, registered, receiver, timer, vibrator, handler;
    private volatile boolean stopping, released = true, closing, finalStageReached, touched;
    private AudioFocusRequest focus;
    private boolean bridgeStop;
    private long cleanupDeadline;
    private final Runnable cleanupWatchdog = new Runnable() {
        @Override public void run() {
            try {
                if (isReleased()) { if (closing) unhookWhenReleased(); return; }
                if (stopping && SystemClock.elapsedRealtime() >= cleanupDeadline) {
                    // A failed OEM asynchronous stage must not leave our player, torch or key listener alive.
                    synchronized (flashLock) {
                        silence();
                        if (player.get(nativeManager) == null) closeFlash.invoke(null, manager);
                        keyCleanup.invoke(keyCleanupTask.newInstance(manager, context), new Object[]{null});
                        finalStageReached = true;
                        finishRelease();
                    }
                }
                if (released) stop();
            } catch (Exception failure) { android.util.Log.w("YueJieFinder", "native_cleanup_watchdog_retry"); }
            main.postDelayed(this, 250);
        }
    };
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchId)) { torchActive = enabled; if (closing) unhookWhenReleased(); }
        }
        @Override public void onTorchModeUnavailable(String cameraId) {
            if (cameraId.equals(torchId)) torchActive = null;
        }
    };

    OplusFinderNativeEndpoint(Context context, ClassLoader loader, XposedModule module, Runnable interrupted) throws Exception {
        if (!"com.coloros.findmyphone".equals(context.getPackageName()) || module == null)
            throw new SecurityException("finder_native_process_required");
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("finder_main_thread_required");
        this.context = context;
        this.interrupted = interrupted;
        requireVibrationPermission();
        audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new IllegalStateException("finder_audio_unavailable");
        cameras = context.getSystemService(CameraManager.class);
        if (cameras == null || cameras.getCameraIdList().length == 0) throw new IllegalStateException("finder_torch_unavailable");
        torchId = cameras.getCameraIdList()[0];
        Class<?> owner = Class.forName("A3.f", false, loader);
        Class<?> app = Class.forName("com.oplus.FindMyPhoneApplication", false, loader);
        Class<?> flow = Class.forName("d1.f", false, loader);
        Class<?> task = Class.forName("A3.i", false, loader);
        Class<?> afterStop = Class.forName("A3.e", false, loader);
        player = field(owner, "c");
        dialog = field(owner, "e");
        registered = field(owner, "f");
        receiver = field(owner, "o");
        timer = field(owner, "m");
        vibrator = field(owner, "g");
        handler = field(owner, "b");
        // Independent OEM instance keeps bridge activity out of the cloud command's id/reporting state.
        manager = owner.getConstructor().newInstance();
        nativeManager = field(Class.forName("A3.f$b", false, loader), "a").get(null);
        if (field(owner, "i").getInt(manager) != -1 || !"".equals(field(owner, "j").get(manager)))
            throw new IllegalStateException("finder_cloud_state_not_empty");
        if (!app.isInstance(field(owner, "a").get(manager))) throw new IllegalStateException("finder_application_unavailable");
        playTask = task.getConstructor(owner, app, boolean.class);
        play = task.getMethod("a", flow);
        stopNative = owner.getMethod("c", Context.class, afterStop);
        closeFlash = owner.getMethod("b", owner);
        advance = flow.getMethod("b");
        try {
            Class<?> nativeHandler = Class.forName("A3.f$c", false, loader);
            Class<?> flashTask = Class.forName("A3.f$c$a", false, loader);
            Field handlerOwner = fieldOfType(nativeHandler, owner);
            Field taskHandler = fieldOfType(flashTask, nativeHandler);
            hooks.add(module.hook(nativeHandler.getDeclaredMethod("handleMessage", android.os.Message.class))
                    .setId("yj.finder.oplus.timer").intercept(chain -> {
                        if (handlerOwner.get(chain.getThisObject()) != manager) return chain.proceed();
                        synchronized (flashLock) { return stopping || released ? null : chain.proceed(); }
                    }));
            hooks.add(module.hook(flashTask.getDeclaredConstructor(nativeHandler)).setId("yj.finder.oplus.flash_queue").intercept(chain -> {
                Object result = chain.proceed();
                if (handlerOwner.get(taskHandler.get(chain.getThisObject())) == manager) queuedFlash.incrementAndGet();
                return result;
            }));
            hooks.add(module.hook(flashTask.getDeclaredMethod("run")).setId("yj.finder.oplus.flash_run").intercept(chain -> {
                if (handlerOwner.get(taskHandler.get(chain.getThisObject())) != manager) return chain.proceed();
                try { synchronized (flashLock) { return stopping || released ? null : chain.proceed(); } }
                finally { queuedFlash.decrementAndGet(); if (closing && released) main.post(this::unhookWhenReleased); }
            }));
            hooks.add(module.hook(stopNative).setId("yj.finder.oplus.stop").intercept(chain -> {
                if (chain.getThisObject() != manager) return chain.proceed();
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    main.post(() -> { try { stopNative.invoke(manager, context, null); } catch (Exception failure) { interrupted.run(); } });
                    return null;
                }
                if (stopping || released) return null;
                stopping = true;
                cleanupDeadline = SystemClock.elapsedRealtime() + 1000;
                main.removeCallbacks(cleanupWatchdog);
                main.postDelayed(cleanupWatchdog, 250);
                // Sound and user-visible dialog stop immediately; OEM stages finish flash/key cleanup.
                try { silence(); }
                catch (Exception failure) { android.util.Log.w("YueJieFinder", "native_cleanup_retry"); }
                if (!bridgeStop) interrupted.run();
                return chain.proceed();
            }));
            Class<?> restore = Class.forName("A3.l", false, loader);
            Field restoreOwner = field(restore, "b");
            hooks.add(module.hook(restore.getMethod("a", flow)).setId("yj.finder.oplus.restore").intercept(chain -> {
                if (restoreOwner.get(chain.getThisObject()) != manager) return chain.proceed();
                // Bridge playback never invokes A3.j or changes volumes/routes. Do not consume the
                // cloud manager's global saved-volume preferences in this instance's cleanup.
                abandonFocus();
                try { synchronized (flashLock) {
                    // If an independent stock cloud ring preempted us, it now owns the shared torch.
                    if (player.get(nativeManager) == null) closeFlash.invoke(null, manager);
                    else field(manager.getClass(), "k").set(manager, null);
                } }
                finally { advance.invoke(chain.getArg(0)); }
                return null;
            }));
            Class<?> finish = Class.forName("A3.k", false, loader);
            keyCleanupTask = finish.getConstructor(owner, Context.class);
            keyCleanup = finish.getMethod("a", flow);
            Field finishOwner = field(finish, "b");
            hooks.add(module.hook(finish.getMethod("a", flow)).setId("yj.finder.oplus.released").intercept(chain -> {
                if (finishOwner.get(chain.getThisObject()) != manager) return chain.proceed();
                Object result = chain.proceed();
                finalStageReached = true;
                finishRelease();
                return result;
            }));
            cameras.registerTorchCallback(torchCallback, main);
            torchCallbackRegistered = true;
        } catch (Exception failure) { unhook(); throw failure; }
    }

    @Override public void start(String peerName, String session) throws Exception {
        if (closing || !isReleased()) throw new DeviceFinderController.Failure("finder_busy");
        touched = false;
        requireVibrationPermission();
        if (torchActive == null) throw new DeviceFinderController.Failure("finder_torch_unavailable");
        if (torchActive) throw new DeviceFinderController.Failure("finder_torch_busy");
        MediaPlayer other = (MediaPlayer) player.get(nativeManager);
        if (other != null || audio.getMode() != AudioManager.MODE_NORMAL || audio.isMusicActive())
            throw new DeviceFinderController.Failure("audio_busy");
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE)
            throw new DeviceFinderController.Failure("alarm_not_audible");
        if (audio.isStreamMute(AudioManager.STREAM_ALARM) || audio.getStreamVolume(AudioManager.STREAM_ALARM) <= 0)
            throw new DeviceFinderController.Failure("alarm_not_audible");
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> { if (change < 0) interrupted.run(); }, main).build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            abandonFocus();
            throw new DeviceFinderController.Failure("audio_focus_denied");
        }
        stopping = false;
        finalStageReached = false;
        released = false;
        touched = true;
        // A3.i's native main-thread stage owns the stock dialog, MediaPlayer, flash and key observer.
        // Its serializer parameter is unused; unlike A3.j/l/m, it never calls advance().
        Object application = field(manager.getClass(), "a").get(manager);
        play.invoke(playTask.newInstance(manager, application, false), new Object[]{null});
        if (!isRinging()) throw new DeviceFinderController.Failure("playback_unverified");
    }

    @Override public boolean isRinging() throws Exception {
        MediaPlayer actual = (MediaPlayer) player.get(manager);
        return actual != null && actual.isPlaying();
    }

    @Override public boolean isBusy() throws Exception { return isRinging() || player.get(nativeManager) != null; }

    @Override public void stop() throws Exception {
        if (released) {
            abandonFocus();
            if (touched && Boolean.TRUE.equals(torchActive) && player.get(nativeManager) == null) cameras.setTorchMode(torchId, false);
            return;
        }
        if (!stopping) {
            bridgeStop = true;
            try { stopNative.invoke(manager, context, null); }
            finally { bridgeStop = false; }
        }
        else if (finalStageReached) finishRelease();
    }

    @Override public boolean isReleased() throws Exception {
        return !touched || released && queuedFlash.get() == 0
                && (Boolean.FALSE.equals(torchActive) || player.get(nativeManager) != null);
    }

    private void silence() throws Exception {
        Exception failure = null;
        try {
            MediaPlayer actual = (MediaPlayer) player.get(manager);
            if (actual != null) { actual.release(); player.set(manager, null); }
        } catch (Exception error) { failure = error; }
        try {
            Object nativeTimer = timer.get(manager);
            if (nativeTimer instanceof CountDownTimer countdown) countdown.cancel();
            Object nativeHandler = handler.get(manager);
            if (nativeHandler instanceof Handler callbacks) callbacks.removeCallbacksAndMessages(null);
            Object nativeVibrator = vibrator.get(manager);
            if (nativeVibrator instanceof Vibrator vibration) {
                try { cancelNativeVibration(vibration); }
                catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (registered.getBoolean(manager)) {
                context.unregisterReceiver((BroadcastReceiver) receiver.get(manager));
                registered.setBoolean(manager, false);
            }
            Object visible = dialog.get(manager);
            if (visible instanceof Dialog popup && popup.isShowing()) popup.dismiss();
        } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        finally { abandonFocus(); }
        if (failure != null) throw failure;
    }

    private void requireVibrationPermission() throws DeviceFinderController.Failure {
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED)
            throw new DeviceFinderController.Failure("vibration_permission_required");
    }

    @SuppressLint("MissingPermission") // Runs as the checked OEM Context/UID, not this module's manifest/UID.
    private void cancelNativeVibration(Vibrator vibration) throws DeviceFinderController.Failure {
        requireVibrationPermission(); // Recheck at cleanup; a failed check must not become a successful release.
        vibration.cancel();
    }

    private synchronized void abandonFocus() {
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }

    private void finishRelease() throws Exception {
        synchronized (flashLock) { silence(); released = true; }
        if (closing) main.post(this::unhookWhenReleased);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field match = null;
        for (Field candidate : owner.getDeclaredFields()) if (candidate.getType() == type) {
            if (match != null) throw new NoSuchFieldException("ambiguous_finder_field");
            candidate.setAccessible(true); match = candidate;
        }
        if (match == null) throw new NoSuchFieldException("missing_finder_field");
        return match;
    }

    private void unhookWhenReleased() {
        synchronized (flashLock) {
            try { if (isReleased() && queuedFlash.get() == 0) unhook(); }
            catch (Exception failure) { android.util.Log.w("YueJieFinder", "native_release_unverified"); }
        }
    }

    private void unhook() {
        main.removeCallbacks(cleanupWatchdog);
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        if (torchCallbackRegistered) { cameras.unregisterTorchCallback(torchCallback); torchCallbackRegistered = false; }
    }

    @Override public void close() throws Exception {
        closing = true;
        stop();
        // Keep instance-scoped hooks until the OEM asynchronous cleanup has really finished.
        if (released) unhookWhenReleased();
    }
}
