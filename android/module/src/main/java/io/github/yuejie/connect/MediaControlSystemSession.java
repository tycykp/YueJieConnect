package io.github.yuejie.connect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Android SYSTEM media-control path: stock SystemUI/media keys -> real remote endpoint -> stock
 * MediaSession/VolumeProvider state. This does not impersonate an OEM car/watch/TV controller.
 * No Activity/view, audio playback, audio-focus request, title/artwork/URI from a user is created.
 * The shared owner must bind only a genuinely rendered cast and a separately approved media target. */
final class MediaControlSystemSession implements AutoCloseable {
    interface Events {
        void observed(MediaControlNativeRoute.Scope scope, MediaControlSession.State actualState);
        void failed(MediaControlNativeRoute.Scope scope, String code, boolean attempted);
    }
    private static final String CHANNEL = "yj_remote_media_control", TAG = "yj.remote.media";
    private static final int NOTIFICATION = 0x594a4d43;
    private static final long SUPPORTED = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
    private final Context context;
    private final MediaControlNativeRoute route;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NotificationManager notifications;
    private final Map<String, PendingIntent> intents = new LinkedHashMap<>();
    private MediaControlNativeRoute.Scope scope;
    private Object peer;
    private BooleanSupplier currentCast, mediaAllowed, volumeAllowed;
    private MediaSession session;
    private VolumeProvider provider;
    private BroadcastReceiver receiver;
    private PendingIntent dismissIntent;
    private MediaControlSession.State observed;
    private String action, sessionTag;
    private long epoch, flight, nextRefresh;
    private boolean closed, inFlight, querying, allowMedia, allowVolume;

    MediaControlSystemSession(Context verifiedOemContext, MediaControlNativeRoute route, Events events) {
        requireMain(); context = Objects.requireNonNull(verifiedOemContext);
        if (!java.util.Set.of("com.xiaomi.mirror", "com.oplus.linker").contains(context.getPackageName()))
            throw new SecurityException("invalid_media_context");
        this.route = Objects.requireNonNull(route); this.events = Objects.requireNonNull(events);
        notifications = context.getSystemService(NotificationManager.class);
    }

    /** Same native stream may not be restarted after stop. Bind on actual onStreaming, never on
     * pairing/selection/SDK 0. Package must be the ORIGINAL remote app selection; empty=volume only.
     * Completes only after a real query has been consumed by the native OS session and notification. */
    CompletableFuture<MediaControlSession.Result> start(MediaControlNativeRoute.Scope castScope, Object nativePeer,
            String selectedRemotePackage, BooleanSupplier actualNativeCastCurrent,
            BooleanSupplier separatelyAllowedMedia, BooleanSupplier separatelyAllowedVolume) {
        requireMain();
        if (closed || session != null || scope != null) throw new IllegalStateException("media_system_session_busy");
        if (!canNotify()) throw new IllegalStateException("media_notification_unavailable");
        scope = Objects.requireNonNull(castScope); peer = Objects.requireNonNull(nativePeer);
        currentCast = Objects.requireNonNull(actualNativeCastCurrent);
        mediaAllowed = Objects.requireNonNull(separatelyAllowedMedia); volumeAllowed = Objects.requireNonNull(separatelyAllowedVolume);
        String target = Objects.requireNonNull(selectedRemotePackage);
        allowMedia = !target.isEmpty() && granted(mediaAllowed); allowVolume = granted(volumeAllowed);
        long generation = ++epoch;
        try {
            route.bind(scope, peer, target, () -> epoch == generation && live());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            action = "io.github.yuejie.connect.media." + nonce;
            sessionTag = "yuejie-remote-control-" + nonce;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (epoch != generation || intent == null || !action.equals(intent.getAction()) || !live()) return;
                    if (intent.getBooleanExtra("dismiss", false)) { stop(); return; }
                    String control = intent.getStringExtra("control");
                    if (!intents.containsKey(control)) return;
                    if ("VOLUME_LOWER".equals(control)) { volume(-1, true); return; }
                    if ("VOLUME_RAISE".equals(control)) { volume(1, true); return; }
                    try { command(MediaControlSession.Action.valueOf(control), 0); }
                    catch (IllegalArgumentException ignoredAction) { }
                }
            };
            IntentFilter filter = new IntentFilter(action); filter.addDataScheme("yj-media");
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            dismissIntent = PendingIntent.getBroadcast(context, -1, new Intent(action).setPackage(context.getPackageName())
                    .setData(Uri.parse("yj-media://" + action + "/dismiss")).putExtra("dismiss", true),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            session = new MediaSession(context, sessionTag);
            android.os.Bundle role = new android.os.Bundle(); role.putBoolean(MediaControlEndpoint.PROXY_SESSION_EXTRA, true);
            session.setExtras(role);
            session.setMediaButtonReceiver(null); // An expired cast must never be restarted by a media key.
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { if (epoch == generation) command(MediaControlSession.Action.PLAY, 0); }
                @Override public void onPause() { if (epoch == generation) command(MediaControlSession.Action.PAUSE, 0); }
                @Override public void onStop() { if (epoch == generation) command(MediaControlSession.Action.STOP, 0); }
                @Override public void onSeekTo(long position) { if (epoch == generation) command(MediaControlSession.Action.SEEK, position); }
                @Override public void onSkipToNext() { if (epoch == generation) command(MediaControlSession.Action.NEXT, 0); }
                @Override public void onSkipToPrevious() { if (epoch == generation) command(MediaControlSession.Action.PREVIOUS, 0); }
                @Override public void onFastForward() { if (epoch == generation) command(MediaControlSession.Action.FAST_FORWARD, 0); }
                @Override public void onRewind() { if (epoch == generation) command(MediaControlSession.Action.REWIND, 0); }
            }, main);
            // Generic, accurate description of the remote-control session. No fabricated duration,
            // queue, playback or user metadata is inserted to make SystemUI display controls.
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,
                    target.isEmpty() ? "投屏音量" : "远端媒体控制").build());
            return refresh(true);
        } catch (RuntimeException error) { stop(); throw error; }
    }

    private CompletableFuture<MediaControlSession.Result> refresh(boolean initial) {
        boolean media = mediaGranted();
        if (!live() || (!media && !volumeGranted())) {
            stop(); return CompletableFuture.completedFuture(new MediaControlSession.Result("cast_inactive", false, null));
        }
        return consume(media ? route.control(peer, MediaControlSession.Action.QUERY, 0) : route.volumeQuery(peer), initial, media, true);
    }
    private void command(MediaControlSession.Action requested, long value) {
        requireMain();
        if (!live() || !mediaGranted() || route.targets("")) return;
        preemptRead();
        if (inFlight) { busy(); return; }
        consume(route.control(peer, requested, value), false, true, false);
    }
    private void volume(int value, boolean relative) {
        requireMain(); if (!live() || !volumeGranted()) return;
        preemptRead();
        if (inFlight) { busy(); return; }
        consume(relative ? route.volumeAdjust(peer, value) : route.volumeAbsolute(peer, value), false, false, false);
    }
    private void preemptRead() {
        if (inFlight && querying) {
            flight++; inFlight = false; querying = false; route.cancelQuery();
        }
    }
    private void busy() { try { events.failed(scope, "busy", false); } catch (RuntimeException ignored) { } }
    private CompletableFuture<MediaControlSession.Result> consume(CompletableFuture<MediaControlSession.Result> pending,
            boolean initial, boolean media, boolean readOnly) {
        long generation = epoch;
        long dispatchedFlight = ++flight;
        MediaControlNativeRoute.Scope bound = scope;
        CompletableFuture<MediaControlSession.Result> consumed = new CompletableFuture<>();
        inFlight = true; querying = readOnly;
        pending.whenComplete((actual, failure) -> main.post(() -> {
            MediaControlSession.Result result = actual;
            if (generation != epoch || bound != scope || dispatchedFlight != flight) {
                consumed.complete(new MediaControlSession.Result("cast_inactive", result != null && result.dispatched(), null)); return;
            }
            inFlight = false; querying = false; nextRefresh = SystemClock.elapsedRealtime() + 2000;
            if (!live() || !(media ? mediaGranted() : volumeGranted())) {
                if (!live()) stop();
                consumed.complete(new MediaControlSession.Result("cast_inactive", result != null && result.dispatched(), null)); return;
            }
            if (failure != null || result == null) result = new MediaControlSession.Result("invalid_result", false, null);
            if (result.confirmed() && result.state() == null) result = new MediaControlSession.Result("invalid_result", result.dispatched(), null);
            if (result.confirmed() && result.state() != null) {
                try { publish(result.state(), media); }
                catch (RuntimeException rejected) {
                    stop(); consumed.complete(new MediaControlSession.Result("native_rejected", result.dispatched(), null)); return;
                }
                try { events.observed(bound, result.state()); } catch (RuntimeException ignored) { }
            } else {
                try { events.failed(bound, result.code(), result.dispatched()); } catch (RuntimeException ignored) { }
                if (initial) stop();
            }
            consumed.complete(result);
        }));
        return consumed;
    }
    private void publish(MediaControlSession.State state, boolean mediaRead) {
        if (!live() || !canNotify()) throw new IllegalStateException("media_system_session_unavailable");
        if (mediaRead) observed = state;
        // Volume readback must not replace the real playback/token with VOLUME_QUERY's empty state.
        MediaControlSession.State playback = observed;
        long actions = playback == null || !mediaGranted() ? 0 : playback.actions() & SUPPORTED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(playback == null ? PlaybackState.STATE_NONE : playback.playback(),
                        playback == null ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : playback.position(), 0f,
                        SystemClock.elapsedRealtime()).build());
        int control = volumeGranted() ? VolumeProvider.VOLUME_CONTROL_ABSOLUTE : VolumeProvider.VOLUME_CONTROL_FIXED;
        if (provider == null || provider.getMaxVolume() != state.maxVolume() || provider.getVolumeControl() != control) {
                final long generation = epoch;
                provider = new VolumeProvider(control, state.maxVolume(), state.volume()) {
                    @Override public void onSetVolumeTo(int level) { main.post(() -> { if (epoch == generation && provider == this) volume(level, false); }); }
                    @Override public void onAdjustVolume(int direction) {
                        if (direction == -1 || direction == 1) main.post(() -> { if (epoch == generation && provider == this) volume(direction, true); });
                    }
                };
                session.setPlaybackToRemote(provider);
        } else provider.setCurrentVolume(state.volume());
        session.setActive(true);
        PlaybackState nativePlayback = session.getController().getPlaybackState();
        MediaController.PlaybackInfo nativeVolume = session.getController().getPlaybackInfo();
        if (nativePlayback == null || nativePlayback.getState() != (playback == null ? PlaybackState.STATE_NONE : playback.playback())
                || nativePlayback.getActions() != actions
                || nativePlayback.getPosition() != (playback == null ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : playback.position())
                || nativeVolume == null || nativeVolume.getCurrentVolume() != state.volume()
                || nativeVolume.getMaxVolume() != state.maxVolume() || nativeVolume.getVolumeControl() != control)
            throw new IllegalStateException("native_media_readback_unconfirmed");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "远端媒体控制", NotificationManager.IMPORTANCE_LOW);
        notifications.createNotificationChannel(channel);
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(session.getSessionToken());
        Notification.Builder notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(playback == null ? "投屏音量" : "远端媒体控制")
                .setContentText("已连接设备").setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_TRANSPORT).setDeleteIntent(dismissIntent).setStyle(style);
        int count = 0;
        if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            add(notification, MediaControlSession.Action.PREVIOUS, android.R.drawable.ic_media_previous, "上一项"); count++;
        }
        boolean playing = playback != null && playback.playback() == PlaybackState.STATE_PLAYING;
        if ((actions & (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY)) != 0) {
            add(notification, playing ? MediaControlSession.Action.PAUSE : MediaControlSession.Action.PLAY,
                    playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "暂停" : "播放"); count++;
        }
        if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
            add(notification, MediaControlSession.Action.NEXT, android.R.drawable.ic_media_next, "下一项"); count++;
        }
        if (volumeGranted()) {
            add(notification, "VOLUME_LOWER", android.R.drawable.ic_lock_silent_mode_off, "音量−"); count++;
            add(notification, "VOLUME_RAISE", android.R.drawable.ic_lock_silent_mode_off, "音量+"); count++;
        }
        int[] compact = new int[Math.min(3, count)]; for (int i = 0; i < compact.length; i++) compact[i] = i;
        style.setShowActionsInCompactView(compact);
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("media_notification_unavailable");
        notifications.notify(TAG, NOTIFICATION, notification.build());
    }
    private void add(Notification.Builder notification, MediaControlSession.Action control, int icon, String label) {
        add(notification, control.name(), icon, label);
    }
    private void add(Notification.Builder notification, String control, int icon, String label) {
        PendingIntent intent = intents.get(control);
        if (intent == null) {
            Intent command = new Intent(action).setPackage(context.getPackageName()).setData(Uri.parse("yj-media://" + action + "/" + control))
                    .putExtra("control", control);
            intent = PendingIntent.getBroadcast(context, control.hashCode(), command, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            intents.put(control, intent);
        }
        notification.addAction(new Notification.Action.Builder(icon, label, intent).build());
    }
    private boolean live() {
        return !closed && scope != null && granted(currentCast) && (allowMedia || allowVolume)
                && (!allowMedia || granted(mediaAllowed)) && (!allowVolume || granted(volumeAllowed));
    }
    private boolean mediaGranted() { return allowMedia && granted(mediaAllowed); }
    private boolean volumeGranted() { return allowVolume && granted(volumeAllowed); }
    private boolean canNotify() {
        if (notifications == null || !notifications.areNotificationsEnabled()
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private static boolean granted(BooleanSupplier gate) {
        try { return gate != null && gate.getAsBoolean(); } catch (RuntimeException invalid) { return false; }
    }
    /** The existing native cast timer calls once a second. No background discovery/session creation. */
    String nativeSessionTag() { requireMain(); return live() && session != null && session.isActive() ? sessionTag : null; }
    void tick() {
        requireMain(); if (scope == null) return; route.tick();
        if (!live()) { stop(); return; }
        if (!inFlight && SystemClock.elapsedRealtime() >= nextRefresh) refresh(false);
    }
    void stop() {
        requireMain(); epoch++; flight++; route.revoke(); scope = null; peer = null; inFlight = false; querying = false;
        observed = null; provider = null; currentCast = null; mediaAllowed = null; volumeAllowed = null; allowMedia = false; allowVolume = false;
        if (session != null) { session.setCallback(null); session.setActive(false); session.release(); session = null; }
        if (receiver != null) { try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { } receiver = null; }
        for (PendingIntent intent : intents.values()) intent.cancel(); intents.clear();
        if (dismissIntent != null) { dismissIntent.cancel(); dismissIntent = null; }
        if (notifications != null) notifications.cancel(TAG, NOTIFICATION);
        action = null; sessionTag = null;
    }
    @Override public void close() { requireMain(); if (!closed) { stop(); closed = true; route.close(); } }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("media_system_requires_main");
    }
}
