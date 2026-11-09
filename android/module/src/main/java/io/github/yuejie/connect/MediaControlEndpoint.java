package io.github.yuejie.connect;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** One instance per authorized OEM executor; call only on its worker, never Binder/main. */
class MediaControlEndpoint implements MediaControlSession.Backend {
    static final String PROXY_SESSION_EXTRA = "io.github.yuejie.connect.remote_media_proxy.v1";
    protected final Context context;
    protected final AudioManager audio;
    private final MediaSessionManager sessions;
    private final PowerManager power;
    private final Map<String, Binding> bindings = new HashMap<>();
    private record Binding(MediaSession.Token nativeToken, String token) {}
    private final MediaControlSession engine;
    private final AtomicLong generation = new AtomicLong();

    MediaControlEndpoint(Context context, String expectedPackage) {
        if (!expectedPackage.equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context;
        audio = context.getSystemService(AudioManager.class);
        sessions = context.getSystemService(MediaSessionManager.class);
        power = context.getSystemService(PowerManager.class);
        engine = new MediaControlSession(this, new MediaControlSession.Clock() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
        });
    }

    JSONObject execute(JSONObject payload, BooleanSupplier authorized, BooleanSupplier cancelled) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("would_block_main");
        MediaControlSession.Request request = decode(payload);
        long epoch = generation.get();
        BooleanSupplier active = () -> generation.get() == epoch && authorized.getAsBoolean();
        MediaControlSession.Result result = engine.execute(request, active, cancelled);
        if (!active.getAsBoolean()) result = new MediaControlSession.Result("stale_session", result.dispatched(), null);
        else if (cancelled.getAsBoolean()) result = new MediaControlSession.Result(
                result.dispatched() ? "cancelled_after_dispatch" : "cancelled", result.dispatched(), null);
        JSONObject out = new JSONObject().put("status", result.confirmed() ? "ok" : "error")
                .put("code", result.code()).put("dispatched", result.dispatched());
        MediaControlSession.State s = result.state();
        // Revocation/cancellation must not release a final snapshot gathered under an old grant.
        if (s != null) {
            out.put("state", new JSONObject().put("media_token", s.token()).put("playback", s.playback())
                    .put("position_ms", s.position()).put("actions", s.actions()).put("volume", s.volume())
                    .put("min_volume", s.minVolume()).put("max_volume", s.maxVolume())
                    .put("interactive", s.interactive()).put("power_save", s.powerSave()));
        }
        return out;
    }

    /** Call on disconnect, executor revocation or any bridge-session replacement. */
    void invalidate() {
        generation.incrementAndGet();
        synchronized (bindings) { bindings.clear(); }
    }

    boolean available(MediaControlSession.Request request) {
        return audio != null && power != null && (!MediaControlSession.isMedia(request.action()) || sessions != null);
    }

    static MediaControlSession.Request decode(JSONObject json) throws Exception {
        if (json == null || json.toString().length() > 2048) throw new IllegalArgumentException("invalid_payload");
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) if (!java.util.Set.of("action", "package", "media_token", "value", "expected_volume").contains(keys.next()))
            throw new IllegalArgumentException("invalid_field");
        MediaControlSession.Action action = MediaControlSession.Action.valueOf(string(json, "action", null));
        java.util.Set<String> allowed = new java.util.HashSet<>();
        allowed.add("action");
        if (MediaControlSession.isMedia(action)) {
            allowed.add("package");
            if (action != MediaControlSession.Action.QUERY) allowed.add("media_token");
        }
        if (action == MediaControlSession.Action.SEEK || action == MediaControlSession.Action.SET_VOLUME
                || action == MediaControlSession.Action.RESTORE_VOLUME) allowed.add("value");
        if (action == MediaControlSession.Action.SET_VOLUME || action == MediaControlSession.Action.RESTORE_VOLUME)
            allowed.add("expected_volume");
        MediaControlWire.fields(json, allowed, 2048);
        if ((action == MediaControlSession.Action.SET_VOLUME || action == MediaControlSession.Action.RESTORE_VOLUME
                || action == MediaControlSession.Action.SEEK) && !json.has("value"))
            throw new IllegalArgumentException("missing_value");
        if (json.has("media_token") && !string(json, "media_token", "").matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("invalid_media_token");
        if (json.has("expected_volume") && number(json, "expected_volume", -1) < 0)
            throw new IllegalArgumentException("invalid_volume");
        return new MediaControlSession.Request(action,
                string(json, "package", ""), string(json, "media_token", ""), number(json, "value", 0),
                Math.toIntExact(number(json, "expected_volume", -1)));
    }
    private static String string(JSONObject json, String key, String fallback) throws Exception {
        if (!json.has(key)) { if (fallback == null) throw new IllegalArgumentException("missing_field"); return fallback; }
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("invalid_field");
        return (String) value;
    }
    private static long number(JSONObject json, String key, long fallback) throws Exception {
        if (!json.has(key)) return fallback;
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("invalid_field");
        return ((Number) value).longValue();
    }

    private MediaController controller(String packageName) {
        if (sessions == null) throw new IllegalStateException("media_unavailable");
        MediaController match = null;
        for (MediaController candidate : sessions.getActiveSessions(null)) {
            if (!packageName.equals(candidate.getPackageName())) continue;
            // A native system-control proxy is not a source player. Selecting another proxy as
            // a source would bounce controls between devices instead of executing real playback.
            android.os.Bundle extras = candidate.getExtras();
            if (extras != null && extras.getBoolean(PROXY_SESSION_EXTRA, false)) continue;
            if (match != null) throw new IllegalStateException("ambiguous_media_session");
            match = candidate;
        }
        if (match == null) { synchronized (bindings) { bindings.remove(packageName); } throw new IllegalStateException("media_unavailable"); }
        return match;
    }
    private String token(MediaController controller) {
        synchronized (bindings) {
        String pkg = controller.getPackageName();
        Binding binding = bindings.get(pkg);
        if (binding == null || !binding.nativeToken.equals(controller.getSessionToken())) {
            if (bindings.size() >= 64) bindings.clear();
            binding = new Binding(controller.getSessionToken(), UUID.randomUUID().toString());
            bindings.put(pkg, binding);
        }
        return binding.token;
        }
    }
    @Override public MediaControlSession.State read(MediaControlSession.Request request) {
        if (audio == null || power == null) throw new IllegalStateException("media_unavailable");
        String token = "";
        int state = 0;
        long position = -1, actions = 0;
        long queueItem = -1, nextItem = -1, previousItem = -1;
        if (MediaControlSession.isMedia(request.action())) {
            MediaController controller = controller(request.packageName());
            token = token(controller);
            PlaybackState playback = controller.getPlaybackState();
            if (playback != null) {
                state = playback.getState(); actions = playback.getActions(); position = playback.getPosition();
                queueItem = playback.getActiveQueueItemId();
                java.util.List<MediaSession.QueueItem> queue = controller.getQueue();
                if (queueItem != -1 && queue != null && queue.size() <= 256) {
                    java.util.HashSet<Long> ids = new java.util.HashSet<>();
                    int activeIndex = -1;
                    boolean unique = true;
                    for (int i = 0; i < queue.size(); i++) {
                        MediaSession.QueueItem item = queue.get(i);
                        if (item == null || item.getQueueId() == -1 || !ids.add(item.getQueueId())) { unique = false; break; }
                        if (item.getQueueId() == queueItem) activeIndex = i;
                    }
                    if (unique && activeIndex >= 0) {
                        if (activeIndex + 1 < queue.size()) nextItem = queue.get(activeIndex + 1).getQueueId();
                        if (activeIndex > 0) previousItem = queue.get(activeIndex - 1).getQueueId();
                    }
                }
                if (position >= 0 && state == PlaybackState.STATE_PLAYING && playback.getLastPositionUpdateTime() > 0)
                    position += (long) ((SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime()) * playback.getPlaybackSpeed());
            }
        }
        return new MediaControlSession.State(token, state, position, actions, audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                power.isInteractive(), power.isPowerSaveMode(), queueItem, nextItem, previousItem);
    }
    @Override public void apply(MediaControlSession.Request request, MediaControlSession.State before) throws Exception {
        if (request.action() == MediaControlSession.Action.SET_VOLUME || request.action() == MediaControlSession.Action.RESTORE_VOLUME) {
            if (audio.isVolumeFixed()) throw new IllegalStateException("volume_fixed");
            // Recheck immediately before dispatch so restore does not overwrite a user's new level.
            if (request.expectedVolume() >= 0 && audio.getStreamVolume(AudioManager.STREAM_MUSIC) != request.expectedVolume())
                throw new IllegalStateException("volume_changed");
            setMusicVolume((int) request.value());
            return;
        }
        MediaController controller = controller(request.packageName());
        if (!request.token().equals(token(controller))) throw new IllegalStateException("stale_media_session");
        MediaController.TransportControls controls = controller.getTransportControls();
        switch (request.action()) {
            case PLAY -> controls.play();
            case PAUSE -> controls.pause();
            case STOP -> controls.stop();
            case SEEK -> controls.seekTo(request.value());
            case NEXT -> controls.skipToNext();
            case PREVIOUS -> controls.skipToPrevious();
            case FAST_FORWARD -> controls.fastForward();
            case REWIND -> controls.rewind();
            default -> throw new IllegalArgumentException("invalid_action");
        }
    }
    protected void setMusicVolume(int value) throws Exception { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); }
}
