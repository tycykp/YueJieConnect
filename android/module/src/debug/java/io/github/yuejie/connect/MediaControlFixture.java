package io.github.yuejie.connect;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Synthetic, silent MediaSession for a debug-only control test. Releases itself after 60s. */
public final class MediaControlFixture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaSession session;
    private static long position;
    private static int state;
    private static int callbacks;
    private static final Runnable EXPIRE = MediaControlFixture::stop;
    private MediaControlFixture() {}

    public static synchronized void start(Context context) {
        requireMain();
        stop();
        session = new MediaSession(context, "yuejie-synthetic-media-control");
        MediaSession owner = session;
        position = 0; callbacks = 0; state = PlaybackState.STATE_PAUSED;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Synthetic control fixture")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 120_000).build());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { change(owner, PlaybackState.STATE_PLAYING, position); }
            @Override public void onPause() { change(owner, PlaybackState.STATE_PAUSED, position); }
            @Override public void onStop() { change(owner, PlaybackState.STATE_STOPPED, position); }
            @Override public void onSeekTo(long value) { change(owner, state, Math.min(120_000, Math.max(0, value))); }
        }, MAIN);
        publish();
        session.setActive(true);
        MAIN.postDelayed(EXPIRE, 60_000);
    }
    private static synchronized void change(MediaSession owner, int newState, long newPosition) {
        if (session == null || session != owner) return;
        state = newState; position = newPosition; callbacks++; publish();
    }
    private static void publish() {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(state, position, 0f).build());
    }
    public static synchronized int callbackCount() { return callbacks; }
    /** Fixed debug IPC helper for the shared owner. Start/stop enqueue only; pending never means
     * a callback or media action succeeded. State reads ONLY this synthetic session, no user list. */
    public static String command(Context moduleContext, String action) {
        if (!"io.github.yuejie.connect".equals(moduleContext.getPackageName()))
            throw new SecurityException("invalid_fixture_context");
        try {
            if ("start".equals(action)) { MAIN.post(() -> start(moduleContext)); return new JSONObject().put("status", "pending").toString(); }
            if ("stop".equals(action)) { MAIN.post(MediaControlFixture::stop); return new JSONObject().put("status", "pending").toString(); }
            if (!"state".equals(action)) return new JSONObject().put("status", "error").put("code", "invalid_fixture_action").toString();
            return snapshot().toString();
        } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
    }
    private static synchronized JSONObject snapshot() throws org.json.JSONException {
        PlaybackState actual = session == null ? null : session.getController().getPlaybackState();
        return new JSONObject().put("status", "ok").put("active", session != null && session.isActive())
                .put("callbacks", callbacks).put("playback", actual == null ? PlaybackState.STATE_NONE : actual.getState())
                .put("position_ms", actual == null ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : actual.getPosition());
    }
    public static synchronized void stop() {
        requireMain();
        MAIN.removeCallbacks(EXPIRE);
        if (session != null) { session.setActive(false); session.release(); session = null; }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("fixture_requires_main");
    }
}
