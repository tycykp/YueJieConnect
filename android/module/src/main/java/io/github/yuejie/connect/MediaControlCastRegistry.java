package io.github.yuejie.connect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Receiver-side media grants for actual rendered OEM casts. Pairing/discovery cannot bind one.
 * Native snapshots are read outside this object's monitor: a screen callback may hold the screen
 * session monitor while revoking its lease. Calling it under this lock would invert those locks. */
final class MediaControlCastRegistry implements MediaControlTasks.Authorization, AutoCloseable {
    /** The integrator reads these from the CURRENT original cast, not from the wire request.
     * streaming means a matched real render receipt, never SDK dispatch or encoder preparation. */
    record NativeState(MediaControlNativeRoute.Scope scope, Object peer, Object owner,
                       boolean streaming, boolean screenAllowed, boolean volumeAllowed,
                       boolean mediaAllowed, String selectedPackage) {
        @Override public String toString() { return "NativeMediaCastState"; }
    }
    private final Map<Long, Lease> active = new HashMap<>();
    private final Set<Long> used = new HashSet<>();
    private String session;
    private long epoch;
    private boolean closed;

    /** Only the authoritative bridge projection listener changes the current session. */
    synchronized void sessionChanged(String nextSession) {
        if (nextSession == null || !nextSession.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid_media_session");
        if (closed || Objects.equals(session, nextSession)) return;
        epoch++;
        for (Lease lease : active.values()) lease.revoked = true;
        active.clear(); used.clear(); session = nextSession;
    }

    /** Called only by the actual cast's onStreaming callback, with its native owner/peer retained.
     * Repeated streaming callbacks must reuse the returned Lease, never create another grant.
     * Empty selectedPackage is volume-only; no local-to-remote package-name inference is allowed. */
    Lease bind(MediaControlNativeRoute.Scope scope, Object originalPeer, Object originalOwner,
            String selectedPackage, Supplier<NativeState> currentNativeCast) {
        Objects.requireNonNull(scope); Objects.requireNonNull(originalPeer);
        Objects.requireNonNull(originalOwner); Objects.requireNonNull(currentNativeCast);
        String pkg = Objects.requireNonNull(selectedPackage);
        if (!pkg.isEmpty()) new MediaControlSession.Request(MediaControlSession.Action.QUERY, pkg, "", 0, -1);
        final Lease lease;
        synchronized (this) {
            if (closed || !scope.sessionId().equals(session)) throw new IllegalStateException("stale_session");
            if (used.contains(scope.streamId())) throw new IllegalStateException("media_scope_reused");
            if (used.size() >= 256 || active.size() >= 8) throw new IllegalStateException("media_scope_capacity");
            lease = new Lease(scope, originalPeer, originalOwner, pkg, currentNativeCast, epoch);
        }
        NativeState initial = lease.snapshot();
        if (!lease.matches(initial) || (!initial.volumeAllowed() && (!initial.mediaAllowed() || pkg.isEmpty())))
            throw new IllegalStateException("cast_inactive");
        synchronized (this) {
            if (closed || epoch != lease.epoch || !scope.sessionId().equals(session))
                throw new IllegalStateException("stale_session");
            if (used.contains(scope.streamId())) throw new IllegalStateException("media_scope_reused");
            if (used.size() >= 256 || active.size() >= 8) throw new IllegalStateException("media_scope_capacity");
            lease.volume = initial.volumeAllowed(); lease.media = initial.mediaAllowed() && !pkg.isEmpty();
            used.add(scope.streamId()); active.put(scope.streamId(), lease);
        }
        // A scope revoked while inspecting the native owner is not revived by this insertion.
        if (!lease.current()) { lease.close(); throw new IllegalStateException("cast_inactive"); }
        return lease;
    }

    @Override public boolean allowed(String projectionId, String sessionId, long streamId, int screenId,
            MediaControlSession.Request request) {
        if (request == null) return false;
        final Lease lease;
        synchronized (this) { lease = active.get(streamId); }
        return lease != null && lease.scope.projectionId().equals(projectionId)
                && lease.scope.sessionId().equals(sessionId) && lease.scope.screenId() == screenId
                && lease.allowed(request);
    }
    boolean hasActive() { return hasActive(request -> true); }
    /** Permission/readiness preflight before advertising an executor. A volume-only cast does not
     * need MEDIA_CONTENT_CONTROL; a media-only cast must not advertise if that OS right is absent. */
    boolean hasActive(Predicate<MediaControlSession.Request> permitted) {
        Objects.requireNonNull(permitted);
        Lease[] leases;
        synchronized (this) { leases = active.values().toArray(Lease[]::new); }
        boolean result = false;
        for (Lease lease : leases) {
            if (!lease.current()) continue;
            var volume = new MediaControlSession.Request(MediaControlSession.Action.VOLUME_QUERY, "", "", 0, -1);
            if (lease.allowed(volume) && permitted.test(volume)) result = true;
            if (!lease.pkg.isEmpty()) {
                var media = new MediaControlSession.Request(MediaControlSession.Action.QUERY, lease.pkg, "", 0, -1);
                if (lease.allowed(media) && permitted.test(media)) result = true;
            }
        }
        return result;
    }
    /** Revocation preserves session/stream tombstones, including when the projection is withdrawn. */
    synchronized void revokeAll() {
        epoch++;
        for (Lease lease : active.values()) lease.revoked = true;
        active.clear();
    }
    @Override public synchronized void close() { if (!closed) { revokeAll(); closed = true; } }

    final class Lease implements AutoCloseable {
        private final MediaControlNativeRoute.Scope scope;
        private final Object peer, owner;
        private final String pkg;
        private final Supplier<NativeState> nativeCast;
        private final long epoch;
        private boolean revoked, volume, media;
        private Lease(MediaControlNativeRoute.Scope scope, Object peer, Object owner, String pkg,
                Supplier<NativeState> nativeCast, long epoch) {
            this.scope = scope; this.peer = peer; this.owner = owner; this.pkg = pkg;
            this.nativeCast = nativeCast; this.epoch = epoch;
        }
        private NativeState snapshot() {
            try { return nativeCast.get(); } catch (RuntimeException invalid) { return null; }
        }
        private boolean matches(NativeState state) {
            return state != null && scope.equals(state.scope()) && state.peer() == peer && state.owner() == owner
                    && state.streaming() && state.screenAllowed() && pkg.equals(state.selectedPackage());
        }
        boolean current() {
            NativeState now = snapshot();
            synchronized (MediaControlCastRegistry.this) {
                if (closed || revoked || epoch != MediaControlCastRegistry.this.epoch
                        || !scope.sessionId().equals(session) || active.get(scope.streamId()) != this) return false;
                if (!matches(now)) { close(); return false; }
                // Rights may shrink in place. Re-enabling one requires a fresh native cast/stream.
                volume &= now.volumeAllowed(); media &= now.mediaAllowed();
                if (!volume && !media) { close(); return false; }
                return true;
            }
        }
        boolean allowed(MediaControlSession.Request request) {
            if (!current()) return false;
            synchronized (MediaControlCastRegistry.this) {
                return !revoked && (MediaControlSession.isMedia(request.action())
                        ? media && pkg.equals(request.packageName()) : volume);
            }
        }
        @Override public void close() {
            synchronized (MediaControlCastRegistry.this) {
                revoked = true; active.remove(scope.streamId(), this);
            }
        }
        @Override public String toString() { return "NativeMediaCastLease"; }
    }
}
