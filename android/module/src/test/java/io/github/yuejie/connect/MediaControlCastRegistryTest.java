package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MediaControlCastRegistryTest {
    private static final String SESSION = "b".repeat(64), PACKAGE = "test.synthetic.player";
    private static final MediaControlSession.Request VOLUME = new MediaControlSession.Request(
            MediaControlSession.Action.VOLUME_QUERY, "", "", 0, -1);
    private static final MediaControlSession.Request QUERY = new MediaControlSession.Request(
            MediaControlSession.Action.QUERY, PACKAGE, "", 0, -1);
    private static class Harness {
        final MediaControlCastRegistry registry = new MediaControlCastRegistry();
        final Object owner = new Object(), peer = new Object();
        final MediaControlNativeRoute.Scope scope = scope(SESSION, 7);
        final AtomicReference<MediaControlCastRegistry.NativeState> state = new AtomicReference<>();
        Harness() { registry.sessionChanged(SESSION); update(true, true, true, true); }
        void update(boolean streaming, boolean screen, boolean volume, boolean media) {
            state.set(new MediaControlCastRegistry.NativeState(scope, peer, owner,
                    streaming, screen, volume, media, PACKAGE));
        }
        MediaControlCastRegistry.Lease bind() { return registry.bind(scope, peer, owner, PACKAGE, state::get); }
        boolean allowed(MediaControlSession.Request request) {
            return registry.allowed(scope.projectionId(), SESSION, scope.streamId(), scope.screenId(), request);
        }
    }
    private static MediaControlNativeRoute.Scope scope(String session, long stream) {
        return new MediaControlNativeRoute.Scope("test-projection", session, "test-native-peer", stream, 0);
    }
    @Test public void pairingAloneNeverAllowsMedia() {
        Harness h = new Harness(); assertFalse(h.registry.hasActive()); assertFalse(h.allowed(VOLUME));
        h.bind(); assertTrue(h.registry.hasActive()); assertTrue(h.allowed(VOLUME)); assertTrue(h.allowed(QUERY));
    }
    @Test public void dispatchAndPreparationAreNotRender() {
        Harness h = new Harness(); h.update(false, true, true, true);
        assertThrows(IllegalStateException.class, h::bind); assertFalse(h.registry.hasActive());
        h.update(true, false, true, true); assertThrows(IllegalStateException.class, h::bind);
    }
    @Test public void allWireIdentityPartsMustMatch() {
        Harness h = new Harness(); h.bind();
        assertFalse(h.registry.allowed("other", SESSION, 7, 0, VOLUME));
        assertFalse(h.registry.allowed(h.scope.projectionId(), "c".repeat(64), 7, 0, VOLUME));
        assertFalse(h.registry.allowed(h.scope.projectionId(), SESSION, 8, 0, VOLUME));
        assertFalse(h.registry.allowed(h.scope.projectionId(), SESSION, 7, 1, VOLUME));
        assertFalse(h.registry.allowed(h.scope.projectionId(), SESSION, 7, 0, null));
    }
    @Test public void replacementOwnerPermanentlyRevokesOldGrant() {
        Harness h = new Harness(); h.bind();
        h.state.set(new MediaControlCastRegistry.NativeState(h.scope, h.peer, new Object(), true, true, true, true, PACKAGE));
        assertFalse(h.allowed(VOLUME)); h.update(true, true, true, true); assertFalse(h.allowed(VOLUME));
        assertEquals("media_scope_reused", assertThrows(IllegalStateException.class, h::bind).getMessage());
    }
    @Test public void replacementPeerPermanentlyRevokesOldGrant() {
        Harness h = new Harness(); h.bind();
        h.state.set(new MediaControlCastRegistry.NativeState(h.scope, new Object(), h.owner, true, true, true, true, PACKAGE));
        assertFalse(h.allowed(QUERY)); h.update(true, true, true, true); assertFalse(h.allowed(QUERY));
    }
    @Test public void changedScreenOrStreamInNativeStateRevokesTheGrant() {
        Harness h = new Harness(); h.bind();
        h.state.set(new MediaControlCastRegistry.NativeState(scope(SESSION, 8), h.peer, h.owner, true, true, true, true, PACKAGE));
        assertFalse(h.allowed(VOLUME)); assertFalse(h.registry.hasActive());
    }
    @Test public void volumeAndMediaRightsStayIndependent() {
        Harness h = new Harness(); h.update(true, true, true, false); h.bind();
        assertTrue(h.allowed(VOLUME)); assertFalse(h.allowed(QUERY));
        h.update(true, true, true, true); assertFalse(h.allowed(QUERY));
        Harness media = new Harness(); media.update(true, true, false, true); media.bind();
        assertTrue(media.allowed(QUERY)); assertFalse(media.allowed(VOLUME));
    }
    @Test public void revokedRightCannotReturnWhileAnotherRightRemains() {
        Harness h = new Harness(); h.bind(); h.update(true, true, false, true);
        assertFalse(h.allowed(VOLUME)); assertTrue(h.allowed(QUERY));
        h.update(true, true, true, true); assertFalse(h.allowed(VOLUME));
    }
    @Test public void mediaOnlyTargetsExactNativeSelectedPackage() {
        Harness h = new Harness(); h.bind();
        var other = new MediaControlSession.Request(MediaControlSession.Action.QUERY, "test.synthetic.other", "", 0, -1);
        assertFalse(h.allowed(other)); assertTrue(h.allowed(QUERY));
        h.state.set(new MediaControlCastRegistry.NativeState(h.scope, h.peer, h.owner, true, true, true, true, "test.synthetic.other"));
        assertFalse(h.allowed(QUERY)); assertFalse(h.allowed(VOLUME));
    }
    @Test public void noPackageMeansVolumeOnly() {
        Harness h = new Harness(); h.state.set(new MediaControlCastRegistry.NativeState(h.scope, h.peer, h.owner,
                true, true, true, true, ""));
        h.registry.bind(h.scope, h.peer, h.owner, "", h.state::get);
        assertTrue(h.allowed(VOLUME)); assertFalse(h.allowed(QUERY));
    }
    @Test public void closeAndProjectionWithdrawalPreserveTombstones() {
        Harness h = new Harness(); h.bind().close();
        assertThrows(IllegalStateException.class, h::bind);
        h.registry.revokeAll(); h.registry.sessionChanged(SESSION);
        assertThrows(IllegalStateException.class, h::bind);
        assertThrows(IllegalArgumentException.class, () -> h.registry.sessionChanged(null));
    }
    @Test public void aNewSessionCannotBorrowOldLeaseOrBeRemovedByItsClose() {
        Harness h = new Harness(); var old = h.bind(); String next = "c".repeat(64);
        h.registry.sessionChanged(next); assertFalse(h.allowed(VOLUME));
        assertThrows(IllegalStateException.class, h::bind);
        var freshScope = scope(next, 7);
        var freshState = new MediaControlCastRegistry.NativeState(freshScope, h.peer, h.owner, true, true, true, true, PACKAGE);
        h.registry.bind(freshScope, h.peer, h.owner, PACKAGE, () -> freshState);
        old.close(); assertTrue(h.registry.allowed(freshScope.projectionId(), next, 7, 0, VOLUME));
    }
    @Test public void stoppedScreenCannotRegainTheSameLease() {
        Harness h = new Harness(); h.bind(); h.update(false, true, true, true);
        assertFalse(h.registry.hasActive()); h.update(true, true, true, true); assertFalse(h.allowed(VOLUME));
    }
    @Test public void unavailableNativeSnapshotIsTerminal() {
        Harness h = new Harness(); h.bind(); h.state.set(null);
        assertFalse(h.allowed(VOLUME)); h.update(true, true, true, true); assertFalse(h.allowed(VOLUME));
    }
    @Test public void nativeInspectionNeverRunsInsideRegistryMonitor() {
        Harness h = new Harness(); AtomicBoolean locked = new AtomicBoolean();
        h.registry.bind(h.scope, h.peer, h.owner, PACKAGE, () -> {
            locked.set(locked.get() || Thread.holdsLock(h.registry)); return h.state.get();
        });
        h.allowed(VOLUME); h.registry.hasActive(); assertFalse(locked.get());
    }
    @Test public void revocationDuringNativeInspectionCannotResurrectABinding() {
        Harness h = new Harness();
        assertThrows(IllegalStateException.class, () -> h.registry.bind(h.scope, h.peer, h.owner, PACKAGE, () -> {
            h.registry.revokeAll(); return h.state.get();
        }));
        assertFalse(h.registry.hasActive()); assertFalse(h.allowed(VOLUME));
    }
    @Test public void historyIsBoundedWithoutEvictionOrReplay() {
        Harness h = new Harness();
        for (int stream = 1; stream <= 256; stream++) {
            var scope = scope(SESSION, stream);
            var state = new MediaControlCastRegistry.NativeState(scope, h.peer, h.owner, true, true, true, true, PACKAGE);
            h.registry.bind(scope, h.peer, h.owner, PACKAGE, () -> state).close();
        }
        assertEquals("media_scope_capacity", assertThrows(IllegalStateException.class,
                () -> h.registry.bind(scope(SESSION, 257), h.peer, h.owner, PACKAGE, h.state::get)).getMessage());
        assertThrows(IllegalStateException.class, h::bind);
    }
    @Test public void closingRegistryCannotBeReopenedByProjectionOrNativeCallback() {
        Harness h = new Harness(); h.bind(); h.registry.close(); h.registry.sessionChanged("c".repeat(64));
        assertFalse(h.registry.hasActive()); assertFalse(h.allowed(VOLUME)); assertThrows(IllegalStateException.class, h::bind);
    }
    @Test public void readinessRequiresAnOsPermissionForAnActuallyGrantedAction() {
        Harness h = new Harness(); h.update(true, true, true, false); h.bind();
        assertFalse(h.registry.hasActive(request -> MediaControlSession.isMedia(request.action())));
        assertTrue(h.registry.hasActive(request -> !MediaControlSession.isMedia(request.action())));
        Harness media = new Harness(); media.update(true, true, false, true); media.bind();
        assertFalse(media.registry.hasActive(request -> !MediaControlSession.isMedia(request.action())));
        assertTrue(media.registry.hasActive(request -> MediaControlSession.isMedia(request.action())));
    }
}
