package io.github.yuejie.connect;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Exact native peer + rendered cast lease. Discovery/projection alone cannot create a grant.
 * No metadata or queue contents are transported. One operation at a time prevents stale UI results. */
final class MediaControlNativeRoute implements AutoCloseable {
    record Scope(String projectionId, String sessionId, String nativePeerId, long streamId, int screenId) {
        Scope {
            if (projectionId == null || projectionId.isBlank() || projectionId.length() > 256
                    || sessionId == null || !sessionId.matches("[0-9a-f]{64}")
                    || nativePeerId == null || nativePeerId.isBlank() || nativePeerId.length() > 256
                    || streamId < 0 || screenId < 0 || streamId == 0 && screenId != 0) throw new IllegalArgumentException("invalid_media_scope");
        }
    }
    interface Transport {
        void submit(Scope scope, String requestId, MediaControlSession.Request request,
                java.util.function.Consumer<MediaControlSession.Result> completion);
        void cancel(Scope scope, String requestId);
    }
    private record Binding(Scope scope, Object peer, String targetPackage, BooleanSupplier active) { }
    private enum VolumeMode { PERCENT, ABSOLUTE, RELATIVE }
    private static final class Pending {
        final Binding binding;
        final MediaControlSession.Action action;
        final long value, deadline;
        final VolumeMode volumeMode;
        final String expectedToken;
        final CompletableFuture<MediaControlSession.Result> future = new CompletableFuture<>();
        String requestId;
        boolean dispatched;
        Pending(Binding binding, MediaControlSession.Action action, long value, long deadline, VolumeMode volumeMode, String expectedToken) {
            this.binding = binding; this.action = action; this.value = value; this.deadline = deadline;
            this.volumeMode = volumeMode; this.expectedToken = expectedToken;
        }
    }
    private final Transport transport;
    private final LongSupplier elapsed;
    private Binding binding;
    private Pending pending;
    private boolean closed;
    private String streamSession;
    private final java.util.Set<Long> usedStreams = new java.util.HashSet<>();

    MediaControlNativeRoute(Transport transport, LongSupplier elapsed) {
        this.transport = Objects.requireNonNull(transport); this.elapsed = Objects.requireNonNull(elapsed);
    }
    /** Bind a rendered cast, or device volume (stream/screen zero) after explicit media consent.
     * active rechecks the current native owner and authorization on every request.
     * Empty targetPackage permits only volume. */
    synchronized void bind(Scope scope, Object originalPeer, String targetPackage, BooleanSupplier active) {
        Objects.requireNonNull(scope); Objects.requireNonNull(originalPeer); Objects.requireNonNull(active);
        String pkg = targetPackage == null ? "" : targetPackage;
        if (scope.streamId() == 0 && !pkg.isEmpty()) throw new IllegalArgumentException("invalid_media_scope");
        if (!pkg.isEmpty()) new MediaControlSession.Request(MediaControlSession.Action.QUERY, pkg, "", 0, -1);
        if (closed || !active.getAsBoolean()) throw new IllegalStateException("cast_inactive");
        // A revoked native cast may not lend its wire identity to a replacement lease. The shared
        // receiver must enforce this same session/stream non-reuse in its native grant registry.
        if (scope.sessionId.equals(streamSession) && usedStreams.contains(scope.streamId))
            throw new IllegalStateException("media_scope_reused");
        if (scope.sessionId.equals(streamSession) && usedStreams.size() >= 256)
            throw new IllegalStateException("media_scope_capacity");
        if (!scope.sessionId.equals(streamSession)) { streamSession = scope.sessionId; usedStreams.clear(); }
        usedStreams.add(scope.streamId);
        revoke(); binding = new Binding(scope, originalPeer, pkg, active);
    }
    synchronized boolean owns(Object peer) { return binding != null && peer == binding.peer; }
    synchronized boolean targets(String pkg) { return binding != null && binding.targetPackage.equals(pkg); }
    /** A user command may preempt a periodic READ, retaining the exact rendered cast binding.
     * Never cancels a mutation intent, even if that intent is currently querying its before-state. */
    synchronized boolean cancelQuery() {
        Pending old = pending;
        if (old == null || (old.action != MediaControlSession.Action.QUERY && old.action != MediaControlSession.Action.VOLUME_QUERY)) return false;
        pending = null;
        if (old.requestId != null) try { transport.cancel(old.binding.scope, old.requestId); } catch (RuntimeException ignored) { }
        old.future.complete(new MediaControlSession.Result("cancelled", false, null));
        return true;
    }
    CompletableFuture<MediaControlSession.Result> volume(Object peer, int percent) {
        if (percent < 0 || percent > 100) return completed("invalid_volume");
        return begin(peer, MediaControlSession.Action.SET_VOLUME, percent, VolumeMode.PERCENT);
    }
    CompletableFuture<MediaControlSession.Result> volumeQuery(Object peer) {
        return begin(peer, MediaControlSession.Action.VOLUME_QUERY, 0, VolumeMode.ABSOLUTE);
    }
    CompletableFuture<MediaControlSession.Result> volumeAbsolute(Object peer, int level) {
        if (level < 0 || level > 10_000) return completed("invalid_volume");
        return begin(peer, MediaControlSession.Action.SET_VOLUME, level, VolumeMode.ABSOLUTE);
    }
    CompletableFuture<MediaControlSession.Result> volumeAdjust(Object peer, int direction) {
        if (direction != -1 && direction != 1) return completed("invalid_volume");
        return begin(peer, MediaControlSession.Action.SET_VOLUME, direction, VolumeMode.RELATIVE);
    }
    CompletableFuture<MediaControlSession.Result> control(Object peer, MediaControlSession.Action action, long value) {
        return control(peer, action, value, null);
    }
    CompletableFuture<MediaControlSession.Result> control(Object peer, MediaControlSession.Action action, long value, String expectedToken) {
        if (!MediaControlSession.isMedia(action)) return completed("invalid_action");
        return begin(peer, action, value, VolumeMode.ABSOLUTE, expectedToken);
    }
    private CompletableFuture<MediaControlSession.Result> begin(Object peer, MediaControlSession.Action action, long value, VolumeMode volumeMode) {
        return begin(peer, action, value, volumeMode, null);
    }
    private synchronized CompletableFuture<MediaControlSession.Result> begin(Object peer, MediaControlSession.Action action, long value,
            VolumeMode volumeMode, String expectedToken) {
        if (!live(binding) || binding.peer != peer) return completed("cast_inactive");
        if (pending != null) return completed("busy");
        boolean media = MediaControlSession.isMedia(action);
        if (media && binding.targetPackage.isEmpty()) return completed("media_target_unbound");
        try { new MediaControlSession.Request(action, media ? binding.targetPackage : "", media ? "pending" : "",
                volumeMode == VolumeMode.RELATIVE ? 0 : value, -1); }
        catch (RuntimeException invalid) { return completed("invalid_media_request"); }
        Pending next = new Pending(binding, action, value, elapsed.getAsLong() + 15_000, volumeMode, expectedToken);
        pending = next;
        query(next);
        return next.future;
    }
    private void query(Pending p) {
        boolean media = MediaControlSession.isMedia(p.action);
        submit(p, new MediaControlSession.Request(media ? MediaControlSession.Action.QUERY : MediaControlSession.Action.VOLUME_QUERY,
                media ? p.binding.targetPackage : "", "", 0, -1), result -> {
            if (!valid(p)) { finish(p, failure(p, "cast_inactive")); return; }
            if (!result.confirmed() || result.state() == null || result.dispatched()) {
                finish(p, result.confirmed() ? failure(p, "invalid_result") : result); return;
            }
            if (media && result.state().token().isEmpty()) { finish(p, failure(p, "invalid_result")); return; }
            if (p.action == MediaControlSession.Action.QUERY || p.action == MediaControlSession.Action.VOLUME_QUERY) { finish(p, result); return; }
            MediaControlSession.State state = result.state();
            if (p.expectedToken != null && !p.expectedToken.equals(state.token())) {
                finish(p, failure(p, "stale_media_session")); return;
            }
            long value = p.value;
            if (!media) {
                value = switch (p.volumeMode) {
                    case PERCENT -> MediaControlSession.volumeFromPercent((int) p.value, state.maxVolume());
                    case ABSOLUTE -> p.value;
                    case RELATIVE -> Math.max(state.minVolume(), Math.min(state.maxVolume(), state.volume() + p.value));
                };
                if (value < state.minVolume() || value > state.maxVolume()) { finish(p, failure(p, "volume_out_of_range")); return; }
            }
            MediaControlSession.Request request = new MediaControlSession.Request(p.action,
                    media ? p.binding.targetPackage : "", media ? state.token() : "", value, media ? -1 : state.volume());
            submit(p, request, applied -> {
                if (!valid(p)) { finish(p, failure(p, "cast_inactive")); return; }
                if (applied.confirmed() && (applied.state() == null || (media
                        ? !request.token().equals(applied.state().token()) : applied.state().volume() != request.value())))
                    applied = failure(p, "invalid_result");
                if (applied.confirmed() && media && !observed(request, state, applied.state()))
                    applied = failure(p, "invalid_result");
                finish(p, applied);
            });
        });
    }
    private void submit(Pending p, MediaControlSession.Request request, java.util.function.Consumer<MediaControlSession.Result> consumer) {
        if (!valid(p)) { finish(p, failure(p, "cast_inactive")); return; }
        String id = "media:" + UUID.randomUUID();
        p.requestId = id;
        // Once a mutation is submitted its remote outcome may be unknown on disconnect.
        // Conservatively report attempted, never imply cancellation rolled it back.
        if (request.action() != MediaControlSession.Action.QUERY && request.action() != MediaControlSession.Action.VOLUME_QUERY)
            p.dispatched = true;
        AtomicBoolean once = new AtomicBoolean();
        try {
            transport.submit(p.binding.scope, id, request, result -> {
                if (!once.compareAndSet(false, true)) return;
                synchronized (this) {
                    if (pending != p) return;
                    if (result == null) result = failure(p, "invalid_result");
                    p.dispatched |= result.dispatched();
                    if (valid(p)) {
                        try { consumer.accept(result); }
                        catch (RuntimeException invalid) { finish(p, failure(p, "invalid_result")); }
                    } else finish(p, failure(p, "cast_inactive"));
                }
            });
        } catch (RuntimeException failed) { finish(p, failure(p, "service_unavailable")); }
    }
    private boolean live(Binding b) {
        if (closed || b == null || b != binding) return false;
        try { return b.active.getAsBoolean(); } catch (RuntimeException failure) { return false; }
    }
    private static boolean observed(MediaControlSession.Request request, MediaControlSession.State before, MediaControlSession.State after) {
        return switch (request.action()) {
            case PLAY -> after.playback() == 3;
            case PAUSE -> after.playback() == 2;
            case STOP -> after.playback() == 1;
            case FAST_FORWARD -> after.playback() == 4;
            case REWIND -> after.playback() == 5;
            case SEEK -> after.position() >= 0 && Math.abs(after.position() - request.value()) <= 1_000;
            // Relative seeks resolve against the receiver's dispatch-time state; queue checks are local
            // to the endpoint. Their internal targets are intentionally not part of public media state.
            case TOGGLE, SEEK_FORWARD, SEEK_BACKWARD, NEXT, PREVIOUS -> true;
            default -> false;
        };
    }
    private synchronized boolean valid(Pending p) { return pending == p && live(p.binding) && elapsed.getAsLong() < p.deadline; }
    private synchronized void finish(Pending p, MediaControlSession.Result result) {
        if (pending != p) return;
        if (!result.confirmed() && p.dispatched && !result.dispatched())
            result = new MediaControlSession.Result(result.code(), true, result.state());
        pending = null; p.future.complete(result);
    }
    /** Existing session timer calls tick at least once a second, even with no replies. */
    synchronized void tick() {
        if (pending != null && !valid(pending)) revoke();
    }
    synchronized void revoke() {
        binding = null;
        Pending old = pending;
        if (old != null) {
            pending = null;
            if (old.requestId != null) try { transport.cancel(old.binding.scope, old.requestId); } catch (RuntimeException ignored) { }
            old.future.complete(failure(old, "cast_inactive"));
        }
    }
    private static CompletableFuture<MediaControlSession.Result> completed(String code) {
        return CompletableFuture.completedFuture(new MediaControlSession.Result(code, false, null));
    }
    private static MediaControlSession.Result failure(Pending p, String code) { return new MediaControlSession.Result(code, p.dispatched, null); }
    @Override public synchronized void close() { closed = true; revoke(); }
}
