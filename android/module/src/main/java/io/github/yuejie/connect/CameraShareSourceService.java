package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Non-exported camera FGS. Its app-private controller is called locally by the :bridge process. */
public final class CameraShareSourceService extends Service {
    static final String UI_DESCRIPTOR = "io.github.yuejie.connect.CameraShareConsent.v1";
    static final String HANDLE = "camera_request_handle";
    static final String ACTIVATE = "io.github.yuejie.connect.camera.ACTIVATE";
    static final String STOP = "io.github.yuejie.connect.camera.STOP";
    static final int DESCRIBE = IBinder.FIRST_CALL_TRANSACTION, APPROVE = DESCRIBE + 1, DENY = DESCRIBE + 2;
    private static final int NOTIFICATION = 0x594a4301;
    private static final String CHANNEL = "camera_share_capture";

    record Request(CameraShareConsentRegistry.Scope scope, CameraShareConfig config,
                   String peerDisplayName, long permissionDeadlineMs, CameraShareStillSession.Size stillSize) {
        Request(CameraShareConsentRegistry.Scope scope, CameraShareConfig config, String peerDisplayName, long permissionDeadlineMs) {
            this(scope, config, peerDisplayName, permissionDeadlineMs, null);
        }
        Request {
            Objects.requireNonNull(scope); Objects.requireNonNull(config);
            if (!scope.cameraId().equals(config.cameraId)) throw new IllegalArgumentException("camera_source_scope_mismatch");
            if (peerDisplayName == null || peerDisplayName.isBlank() || peerDisplayName.length() > 128
                    || peerDisplayName.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)))
                throw new IllegalArgumentException("invalid_camera_peer_display_name");
        }
    }
    interface Listener {
        void capturing(CameraShareConsentRegistry.Scope scope);
        void streaming(CameraShareConsentRegistry.Scope scope);
        void closed(CameraShareConsentRegistry.Scope scope, String reason);
    }
    private static final class Slot {
        final String handle;
        final Request request;
        final CameraShareSender.Transport transport;
        final Listener listener;
        final AtomicBoolean completed = new AtomicBoolean();
        volatile boolean cancelled, foreground, starting, started;
        volatile long startDeadlineMs;
        volatile CameraShareSender sender;
        Slot(String handle, Request request, CameraShareSender.Transport transport, Listener listener) {
            this.handle = handle; this.request = request; this.transport = transport; this.listener = listener;
        }
    }
    private final CameraShareConsentRegistry consents = new CameraShareConsentRegistry();
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicInteger intentIds = new AtomicInteger(1);
    private final HandlerThread thread = new HandlerThread("YjCameraForeground");
    private Handler worker, main;
    private final Object foregroundLock = new Object();
    private volatile Slot active;
    private volatile boolean destroyed;
    private final LocalController controller = new LocalController();

    /** Local Java API only; video packets never cross this UI Binder or Intent extras. */
    final class LocalController extends Binder {
        /** Actual app-UID metadata, after the normal CAMERA grant; does not open a device or capture. */
        CompletionStage<List<CameraShareConfig.Camera>> cameras() {
            CompletableFuture<List<CameraShareConfig.Camera>> result = new CompletableFuture<>();
            if (destroyed || worker == null || !worker.post(() -> {
                try (CameraShareSource source = new CameraShareSource(CameraShareSourceService.this,
                        (session, stream, camera) -> false, new CameraShareSource.Listener() {
                            @Override public void captured(CameraShareLease lease, long timestamp) { }
                            @Override public void closed(CameraShareLease lease, String reason) { }
                        })) {
                    result.complete(source.cameras());
                } catch (Exception unavailable) { result.completeExceptionally(unavailable); }
            })) result.completeExceptionally(new IllegalStateException("camera_source_service_unavailable"));
            return result;
        }

        CompletionStage<List<CameraShareStillSession.Size>> stillSizes(String cameraId) {
            CompletableFuture<List<CameraShareStillSession.Size>> result = new CompletableFuture<>();
            if (destroyed || worker == null || !worker.post(() -> {
                try {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("camera_permission_denied");
                    android.hardware.camera2.CameraCharacteristics info = getSystemService(android.hardware.camera2.CameraManager.class)
                            .getCameraCharacteristics(cameraId);
                    Integer facing = info.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (facing == null || (facing != 0 && facing != 1)) throw new IllegalArgumentException("camera_physical_source_required");
                    android.hardware.camera2.params.StreamConfigurationMap map = info.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    android.util.Size[] values = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                    java.util.ArrayList<CameraShareStillSession.Size> supported = new java.util.ArrayList<>();
                    if (values != null) for (android.util.Size value : values) {
                        if (value.getWidth() <= 8192 && value.getHeight() <= 8192
                                && (long)value.getWidth() * value.getHeight() <= 32_000_000)
                            supported.add(new CameraShareStillSession.Size(value.getWidth(), value.getHeight()));
                    }
                    result.complete(List.copyOf(supported));
                } catch (Exception unavailable) { result.completeExceptionally(unavailable); }
            })) result.completeExceptionally(new IllegalStateException("camera_source_service_unavailable"));
            return result;
        }

        CompletionStage<CameraShareStillSession.Frame> captureStill(CameraShareConsentRegistry.Scope scope,
                CameraShareStillRequest request, CameraShareStillSource.Permit currentSingleNativeExposure) {
            CompletableFuture<CameraShareStillSession.Frame> result = new CompletableFuture<>();
            if (!worker.post(() -> {
                Slot slot = active;
                if (slot == null || !slot.request.scope().equals(scope) || !permitted(slot) || slot.sender == null
                        || slot.request.stillSize() == null) {
                    result.completeExceptionally(new IllegalStateException("camera_still_scope_expired")); return;
                }
                slot.sender.captureStill(request, r -> permitted(slot) && currentSingleNativeExposure.valid(r))
                        .whenComplete((frame, error) -> worker.post(() -> {
                            if (error != null) result.completeExceptionally(error);
                            else if (!permitted(slot) || result.isCancelled()) {
                                java.util.Arrays.fill(frame.jpeg(),(byte)0);
                                result.completeExceptionally(new IllegalStateException("camera_still_scope_expired"));
                            } else if (!result.complete(frame)) java.util.Arrays.fill(frame.jpeg(),(byte)0);
                        }));
            })) result.completeExceptionally(new IllegalStateException("camera_source_service_unavailable"));
            return result;
        }

        PendingIntent arm(Request request, BooleanSupplier currentNativeRequestAndCameraGrants,
                          CameraShareSender.Transport authenticatedBoundedTransport, Listener listener) {
            if (destroyed || worker == null) throw new IllegalStateException("camera_source_service_unavailable");
            Objects.requireNonNull(authenticatedBoundedTransport); Objects.requireNonNull(listener);
            String handle = consents.arm(request.scope(), SystemClock.elapsedRealtime(), request.permissionDeadlineMs(),
                    currentNativeRequestAndCameraGrants);
            Slot slot = new Slot(handle, request, authenticatedBoundedTransport, listener);
            slots.put(handle, slot);
            try {
                return PendingIntent.getActivity(CameraShareSourceService.this, intentIds.getAndIncrement(),
                        new Intent(CameraShareSourceService.this, CameraShareConsentActivity.class).putExtra(HANDLE, handle),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            } catch (RuntimeException failure) { CameraShareSourceService.this.cancel(slot); throw failure; }
        }

        void rendered(CameraShareConsentRegistry.Scope scope, long timestampUs) {
            worker.post(() -> {
                Slot slot = active;
                if (slot != null && slot.request.scope().equals(scope) && permitted(slot) && slot.sender != null)
                    slot.sender.rendered(scope.sessionId(), scope.streamId(), timestampUs);
            });
        }

        void requestKeyFrame(CameraShareConsentRegistry.Scope scope) {
            worker.post(() -> {
                Slot slot = active;
                if (slot != null && slot.request.scope().equals(scope) && permitted(slot) && slot.sender != null)
                    slot.sender.requestKeyFrame(scope.sessionId(), scope.streamId());
            });
        }

        void cancel(CameraShareConsentRegistry.Scope scope) {
            for (Slot slot : slots.values()) if (slot.request.scope().equals(scope)) CameraShareSourceService.this.cancel(slot);
        }

        void sessionInvalidated(String sessionId) {
            consents.sessionInvalidated(sessionId);
            for (Slot slot : slots.values()) if (slot.request.scope().sessionId().equals(sessionId)) CameraShareSourceService.this.cancel(slot);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(UI_DESCRIPTOR); return true; }
            if (code < DESCRIBE || code > DENY) return false;
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("camera_private_ui_only");
            data.enforceInterface(UI_DESCRIPTOR);
            String handle = data.readString(); data.enforceNoDataAvail();
            if (handle == null || !handle.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_camera_handle");
            Slot slot = slots.get(handle);
            if (code == DESCRIBE) {
                boolean valid = slot != null && !slot.cancelled && consents.pending(handle, SystemClock.elapsedRealtime()) != null;
                reply.writeNoException(); reply.writeBoolean(valid);
                if (valid) {
                    reply.writeString(slot.request.peerDisplayName()); reply.writeString(slot.request.config().cameraId);
                    reply.writeInt(slot.request.config().width); reply.writeInt(slot.request.config().height);
                    reply.writeInt(slot.request.config().fps);
                    reply.writeBoolean(slot.request.stillSize() != null);
                    if (slot.request.stillSize() != null) {
                        reply.writeInt(slot.request.stillSize().width()); reply.writeInt(slot.request.stillSize().height());
                    }
                }
                return true;
            }
            boolean allowed = false;
            if (code == APPROVE && slot != null && !slot.cancelled
                    && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                allowed = consents.approve(handle, SystemClock.elapsedRealtime());
            else if (code == DENY && slot != null) CameraShareSourceService.this.cancel(slot);
            reply.writeNoException(); reply.writeBoolean(allowed); return true;
        }
    }

    @Override public void onCreate() {
        super.onCreate(); main = new Handler(getMainLooper()); thread.start(); worker = new Handler(thread.getLooper());
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "共享摄像头采集", NotificationManager.IMPORTANCE_LOW));
        worker.post(this::watch);
    }

    @Override public IBinder onBind(Intent intent) { return controller; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String handle = intent.getStringExtra(HANDLE);
        Slot slot = handle == null ? null : slots.get(handle);
        if (STOP.equals(intent.getAction())) { if (slot != null) cancel(slot); return START_NOT_STICKY; }
        if (!ACTIVATE.equals(intent.getAction()) || slot == null || slot.cancelled
                || consents.activate(handle, SystemClock.elapsedRealtime()) == null) {
            if (active == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        slot.startDeadlineMs = SystemClock.elapsedRealtime() + 8_000;
        synchronized (foregroundLock) {
            if (destroyed || active != null) { cancel(slot); return START_NOT_STICKY; }
            active = slot;
        }
        try {
            startCameraForeground(slot);
            slot.foreground = true;
            worker.post(() -> begin(slot));
        } catch (RuntimeException denied) { cancel(slot); stopSelf(startId); }
        return START_NOT_STICKY;
    }

    // Manifest/normal CAMERA grant and a visible consent Activity are required, never auto-granted.
    @SuppressLint("MissingPermission")
    private void startCameraForeground(Slot slot) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("camera_permission_denied");
        PendingIntent stop = PendingIntent.getService(this, intentIds.getAndIncrement(),
                new Intent(this, CameraShareSourceService.class).setAction(STOP).putExtra(HANDLE, slot.handle),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("摄像头共享")
                .setContentText("对端：" + slot.request.peerDisplayName()).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "停止采集", stop).build()).build();
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
    }

    private boolean permitted(Slot slot) {
        return !destroyed && !slot.cancelled && slot.foreground && active == slot
                && consents.active(slot.handle, slot.request.scope(), SystemClock.elapsedRealtime());
    }

    private void begin(Slot slot) {
        if (!permitted(slot)) { cancel(slot); return; }
        CameraShareConsentRegistry.Scope scope = slot.request.scope();
        try {
            slot.sender = new CameraShareSender(this,
                    (session, stream, camera) -> scope.sessionId().equals(session) && Long.toString(scope.streamId()).equals(stream)
                            && scope.cameraId().equals(camera) && permitted(slot), slot.transport,
                    new CameraShareSender.Listener() {
                        @Override public void availabilityChanged(String cameraId, boolean available) {
                            if (scope.cameraId().equals(cameraId)) worker.post(() -> attemptStart(slot));
                        }
                        @Override public void capturing(CameraShareLease lease) {
                            worker.post(() -> { if (permitted(slot)) notifyCapturing(slot); });
                        }
                        @Override public void streaming(CameraShareLease lease) {
                            worker.post(() -> { if (permitted(slot)) notifyStreaming(slot); });
                        }
                        @Override public void closed(String session, String stream, String reason) {
                            worker.post(() -> finish(slot, reason));
                        }
                    });
            attemptStart(slot);
        } catch (RuntimeException failure) { finish(slot, "camera_source_initialization_failed"); }
    }

    private void attemptStart(Slot slot) {
        if (!permitted(slot)) { cancel(slot); return; }
        if (slot.starting || slot.started || slot.sender == null) return;
        Boolean available = slot.sender.available(slot.request.scope().cameraId());
        if (available == null) return; // Await the one CameraManager availability callback; no open retry.
        if (!available) { finish(slot, "camera_busy"); return; }
        slot.starting = true;
        try {
            slot.sender.startWithStill(slot.request.scope().sessionId(), slot.request.scope().peerId(),
                    slot.request.scope().streamId(), slot.request.config(), slot.request.stillSize());
            slot.started = true;
        } catch (SecurityException denied) { finish(slot, "camera_permission_denied"); }
        catch (Exception failed) { finish(slot, "camera_source_start_failed"); }
    }

    private void notifyCapturing(Slot slot) {
        try { slot.listener.capturing(slot.request.scope()); } catch (RuntimeException failure) { cancel(slot); }
    }
    private void notifyStreaming(Slot slot) {
        try { slot.listener.streaming(slot.request.scope()); } catch (RuntimeException failure) { cancel(slot); }
    }

    private void cancel(Slot slot) {
        slot.cancelled = true; consents.cancel(slot.handle);
        if (worker == null) return;
        worker.post(() -> {
            if (slot.sender != null) slot.sender.close();
            if (!slot.started) finish(slot, "camera_request_cancelled");
            // Otherwise actual CameraDevice.onClosed -> Sender.closed -> finish, never premature release.
        });
    }

    private void finish(Slot slot, String reason) {
        if (!slot.completed.compareAndSet(false, true)) return;
        slot.cancelled = true; consents.cancel(slot.handle); slots.remove(slot.handle, slot);
        if (slot.sender != null) slot.sender.close();
        main.post(() -> {
            synchronized (foregroundLock) {
                if (active == slot) { slot.foreground = false; active = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            }
        });
        try { slot.listener.closed(slot.request.scope(), reason); } catch (RuntimeException ignored) { }
        if (destroyed) thread.quitSafely();
    }

    private void watch() {
        if (destroyed) return;
        long now = SystemClock.elapsedRealtime();
        for (Slot slot : List.copyOf(slots.values())) {
            if (consents.state(slot.handle, now) == CameraShareConsentRegistry.State.CLOSED
                    || (active == slot && !slot.started && now >= slot.startDeadlineMs)) cancel(slot);
        }
        worker.postDelayed(this::watch, 250);
    }

    @Override public void onDestroy() {
        destroyed = true; consents.close();
        for (Slot slot : List.copyOf(slots.values())) cancel(slot);
        if (active == null) thread.quitSafely();
        super.onDestroy();
    }
}
