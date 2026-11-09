package io.github.yuejie.connect;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Routes Android media keys to the current remote player during a rendered cast. */
final class MediaControlSystemSession implements AutoCloseable {
    interface Events {
        void failed(MediaControlNativeRoute.Scope scope, String code, boolean attempted);
    }

    private static final long SUPPORTED = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
    private final Context context;
    private final MediaControlNativeRoute route;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaControlNativeRoute.Scope scope;
    private Object peer;
    private BooleanSupplier currentCast;
    private MediaSession session;
    private MediaControlSession.State observed;
    private long epoch, flight, nextRefresh;
    private boolean closed, inFlight, querying;

    MediaControlSystemSession(Context verifiedOemContext, MediaControlNativeRoute route, Events events) {
        requireMain();
        context = Objects.requireNonNull(verifiedOemContext);
        if (!CallerPolicy.MIRROR.equals(context.getPackageName())) throw new SecurityException("invalid_media_context");
        this.route = Objects.requireNonNull(route);
        this.events = Objects.requireNonNull(events);
    }

    void start(MediaControlNativeRoute.Scope castScope, Object nativePeer, String target, BooleanSupplier grantedCast) {
        requireMain();
        if (closed || scope != null) throw new IllegalStateException("media_system_session_busy");
        scope = Objects.requireNonNull(castScope);
        peer = Objects.requireNonNull(nativePeer);
        currentCast = Objects.requireNonNull(grantedCast);
        long generation = ++epoch;
        try {
            route.bind(scope, peer, target, () -> epoch == generation && live());
            session = new MediaSession(context, "yuejie-remote-control-" + UUID.randomUUID().toString().replace("-", ""));
            Bundle role = new Bundle();
            role.putBoolean(MediaControlEndpoint.PROXY_SESSION_EXTRA, true);
            session.setExtras(role);
            session.setMediaButtonReceiver(null);
            // Cast audio is played by a local AudioTrack. Keep the system volume keys on
            // the tablet's media stream so the native volume panel stays in sync.
            session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { send(MediaControlSession.Action.PLAY, 0); }
                @Override public void onPause() { send(MediaControlSession.Action.PAUSE, 0); }
                @Override public void onStop() { send(MediaControlSession.Action.STOP, 0); }
                @Override public void onSeekTo(long position) { send(MediaControlSession.Action.SEEK, position); }
                @Override public void onSkipToNext() { send(MediaControlSession.Action.NEXT, 0); }
                @Override public void onSkipToPrevious() { send(MediaControlSession.Action.PREVIOUS, 0); }
                @Override public void onFastForward() { send(MediaControlSession.Action.FAST_FORWARD, 0); }
                @Override public void onRewind() { send(MediaControlSession.Action.REWIND, 0); }
                private void send(MediaControlSession.Action action, long value) {
                    if (epoch == generation) command(action, value);
                }
            }, main);
            refresh();
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    private void refresh() {
        consume(route.control(peer, MediaControlSession.Action.QUERY, 0), true);
    }

    private void command(MediaControlSession.Action action, long value) {
        requireMain();
        if (!live() || observed == null) return;
        if (inFlight && querying) {
            flight++; inFlight = false; querying = false; route.cancelQuery();
        }
        if (inFlight) return;
        consume(route.control(peer, action, value, observed.token()), false);
    }

    private void consume(CompletableFuture<MediaControlSession.Result> pending, boolean readOnly) {
        long generation = epoch;
        long dispatchedFlight = ++flight;
        inFlight = true; querying = readOnly;
        pending.whenComplete((result, failure) -> main.post(() -> {
            if (generation != epoch || dispatchedFlight != flight) return;
            inFlight = false; querying = false; nextRefresh = SystemClock.elapsedRealtime() + 2000;
            if (!live()) { stop(); return; }
            if (failure != null || result == null) resultFailed("invalid_result", false, readOnly);
            else if (result.confirmed() && result.state() != null) observed = result.state();
            else resultFailed(result.code(), result.dispatched(), readOnly);
            try { publish(); }
            catch (RuntimeException rejected) {
                events.failed(scope, "native_rejected", result != null && result.dispatched());
                stop();
            }
        }));
    }

    private void resultFailed(String code, boolean attempted, boolean readOnly) {
        if (readOnly || "stale_media_session".equals(code)) observed = null;
        if (!"media_unavailable".equals(code) && !"ambiguous_media_session".equals(code))
            events.failed(scope, code, attempted);
    }

    private void publish() {
        long actions = observed == null ? 0 : observed.actions() & SUPPORTED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(observed == null ? PlaybackState.STATE_NONE : observed.playback(),
                        observed == null ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : observed.position(), 0f,
                        SystemClock.elapsedRealtime()).build());
        session.setActive(observed != null);
    }

    private boolean live() {
        try { return !closed && scope != null && currentCast.getAsBoolean(); }
        catch (RuntimeException invalid) { return false; }
    }

    void tick() {
        requireMain();
        if (scope == null) return;
        if (!live()) { stop(); return; }
        route.tick();
        if (!inFlight && SystemClock.elapsedRealtime() >= nextRefresh) refresh();
    }

    void stop() {
        requireMain();
        epoch++; flight++; route.revoke();
        scope = null; peer = null; inFlight = false; querying = false;
        observed = null; currentCast = null;
        if (session != null) {
            session.setCallback(null); session.setActive(false); session.release(); session = null;
        }
    }

    @Override public void close() { requireMain(); if (!closed) { stop(); closed = true; route.close(); } }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("media_system_requires_main");
    }
}
