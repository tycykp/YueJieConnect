package io.github.qiangjian.connect;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.NetworkShareSession.*;

public class NetworkShareRecoveryTest {
    private static final String OWNER = "com.oplus.linker", SSID = "QJ-0123456789abcdef", KEY = "b".repeat(32), HASH = "d".repeat(64);
    private static final Ticket TICKET = new Ticket("a".repeat(64), "synthetic:recovery", 7);
    private static final class Store implements NetworkShareRecovery.Store {
        byte[] bytes; boolean fail;
        public byte[] read() { return bytes == null ? null : bytes.clone(); }
        public void write(byte[] next) throws IOException { if (fail) throw new IOException("synthetic_disk_failure"); bytes = next.clone(); }
        public void close() {}
    }
    private NetworkShareRecovery.Entry entry(int added, boolean settled) {
        return new NetworkShareRecovery.Entry(OWNER, 1000, TICKET, Role.CONSUMER, Upstream.WIFI,
                SSID, KEY, added, 23, HASH, true, true, settled);
    }
    private NetworkShareRecoveryPlan.Config own(int id) {
        return new NetworkShareRecoveryPlan.Config(id, SSID, "*", "e".repeat(64), 1000, OWNER, true);
    }
    private NetworkShareRecoveryPlan.Config previous(String hash) {
        return new NetworkShareRecoveryPlan.Config(23, "synthetic-original", "*", hash, 1000, "android", true);
    }
    @Test public void crashBeforeReturnedNetworkIdStillRecoversOnlyRecordedSyntheticResource() throws Exception {
        Store store = new Store(); NetworkShareRecovery first = new NetworkShareRecovery(store, OWNER, 1000);
        first.prepare(entry(-1, false));
        NetworkShareRecovery restarted = new NetworkShareRecovery(store, OWNER, 1000);
        assertEquals(entry(-1, false), restarted.entry());
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(restarted.entry(),
                List.of(own(42), previous(HASH)), 42, SSID);
        assertEquals(42, plan.remove()); assertEquals(23, plan.reconnect()); assertFalse(plan.restored());
    }
    @Test public void checkpointAndAtomicTombstoneSurviveRestart() throws Exception {
        Store store = new Store(); NetworkShareRecovery ledger = new NetworkShareRecovery(store, OWNER, 1000);
        ledger.prepare(entry(-1, false)); ledger.checkpoint(entry(42, false));
        assertEquals(42, new NetworkShareRecovery(store, OWNER, 1000).entry().addedNetwork());
        assertThrows(IllegalStateException.class, () -> ledger.clearVerified(false));
        ledger.clearVerified(true); assertNull(new NetworkShareRecovery(store, OWNER, 1000).entry());
    }
    @Test public void persistenceFailureDoesNotReleaseOwnershipOrPermitAnotherShare() throws Exception {
        Store store = new Store(); NetworkShareRecovery ledger = new NetworkShareRecovery(store, OWNER, 1000);
        ledger.prepare(entry(-1, false)); store.fail = true;
        assertThrows(IOException.class, () -> ledger.checkpoint(entry(42, false)));
        assertThrows(IOException.class, () -> ledger.clearVerified(true));
        assertEquals(entry(-1, false), ledger.entry());
        assertThrows(IllegalStateException.class, () -> ledger.prepare(entry(99, false)));
    }
    @Test public void prepareFailureCannotClaimDurableOwnership() throws Exception {
        Store store = new Store(); store.fail = true;
        NetworkShareRecovery ledger = new NetworkShareRecovery(store, OWNER, 1000);
        assertThrows(IOException.class, () -> ledger.prepare(entry(-1, false))); assertNull(ledger.entry());
    }
    @Test public void corruptTruncatedAndForeignOwnerLedgersAreNeverOverwritten() throws Exception {
        Store store = new Store(); new NetworkShareRecovery(store, OWNER, 1000).prepare(entry(-1, false));
        byte[] original = store.bytes.clone();
        assertThrows(IOException.class, () -> new NetworkShareRecovery(store, "com.milink.service", 1000));
        assertThrows(IOException.class, () -> new NetworkShareRecovery(store, OWNER, 1001));
        store.bytes[40] ^= 1;
        assertThrows(IOException.class, () -> new NetworkShareRecovery(store, OWNER, 1000));
        assertNotEquals(original[40], store.bytes[40]);
        store.bytes = new byte[12]; assertThrows(IOException.class, () -> new NetworkShareRecovery(store, OWNER, 1000));
    }
    @Test public void checkpointCannotChangeIdentityOrForgetAppliedNetworkId() throws Exception {
        Store store = new Store(); NetworkShareRecovery ledger = new NetworkShareRecovery(store, OWNER, 1000);
        ledger.prepare(entry(42, false));
        assertThrows(IllegalStateException.class, () -> ledger.checkpoint(entry(-1, false)));
        assertThrows(IllegalStateException.class, () -> ledger.checkpoint(entry(43, false)));
    }
    @Test public void unrelatedNetworkIdReuseCannotDeleteOrReconnectUserChoice() {
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false),
                List.of(new NetworkShareRecoveryPlan.Config(42, "synthetic-user-choice", "*", HASH, 1000, "android", true)),
                42, "synthetic-user-choice");
        assertEquals(-1, plan.remove()); assertEquals(-1, plan.reconnect()); assertTrue(plan.restored());
    }
    @Test public void sameSsidWithDifferentCreatorOrReturnedIdIsAConflict() {
        for (NetworkShareRecoveryPlan.Config config : List.of(own(43),
                new NetworkShareRecoveryPlan.Config(42, SSID, "*", HASH, 1001, OWNER, true),
                new NetworkShareRecoveryPlan.Config(42, SSID, "*", HASH, 1000, "android", true),
                new NetworkShareRecoveryPlan.Config(42, SSID, "f".repeat(32), HASH, 1000, OWNER, true),
                new NetworkShareRecoveryPlan.Config(42, SSID, "*", HASH, 1000, OWNER, false))) {
            NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(config), -1, null);
            assertEquals(-1, plan.remove()); assertEquals(-1, plan.reconnect()); assertFalse(plan.restored()); assertNotNull(plan.error());
        }
    }
    @Test public void originalNetworkIdReuseDoesNotReconnectAnUnrelatedConfiguration() {
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false),
                List.of(own(42), previous("f".repeat(64))), 42, SSID);
        assertEquals(42, plan.remove()); assertEquals(-1, plan.reconnect()); assertFalse(plan.restored());
        assertEquals("network_restore_previous_changed", plan.error());
    }
    @Test public void observedExternalChoiceIsPreservedWhileDeletingOwnConfiguration() {
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(own(42)), 77, "synthetic-user-choice");
        assertEquals(42, plan.remove()); assertEquals(-1, plan.reconnect()); assertFalse(plan.restored());
        assertTrue(NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(), 77, "synthetic-user-choice").restored());
    }
    @Test public void removeAcknowledgementAloneCannotProveRestoration() {
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(previous(HASH)), 42, SSID);
        assertFalse(plan.restored()); assertEquals(23, plan.reconnect());
        assertTrue(NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(previous(HASH)), 23, "synthetic-original").restored());
    }
    @Test public void missingOriginalConfigurationKeepsDisconnectedRecoveryUnverified() {
        NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(), -1, null);
        assertFalse(plan.restored()); assertEquals(-1, plan.reconnect()); assertNotNull(plan.error());
    }
    @Test public void privateRecordsDoNotExposeTemporaryCredentialsInDiagnostics() {
        String text = entry(42, false).toString() + own(42);
        assertFalse(text.contains(SSID)); assertFalse(text.contains(KEY)); assertFalse(text.contains(TICKET.session()));
    }
    @Test public void unsettledProviderCrashCannotBeClearedFromAnEmptyInitialSnapshot() {
        var pending = new NetworkShareRecoveryPlan.ProviderProgress(true, true, false, false, false, false, false, false);
        var empty = new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, true, true);
        assertFalse(NetworkShareRecoveryPlan.provider(pending, empty).restored());
        assertFalse(NetworkShareRecoveryPlan.provider(pending, empty).stop());
        var owned = new NetworkShareRecoveryPlan.ProviderSnapshot(true, true, true, true);
        assertTrue(NetworkShareRecoveryPlan.provider(pending, owned).stop());
    }
    @Test public void fuzzyTetherStopRequiresActualExclusiveSyntheticApObservation() {
        var progress = new NetworkShareRecoveryPlan.ProviderProgress(true, true, true, false, false, false, false, false);
        assertFalse(NetworkShareRecoveryPlan.provider(progress, new NetworkShareRecoveryPlan.ProviderSnapshot(false, true, true, true)).stop());
        assertFalse(NetworkShareRecoveryPlan.provider(progress, new NetworkShareRecoveryPlan.ProviderSnapshot(true, true, false, true)).stop());
        assertFalse(NetworkShareRecoveryPlan.provider(progress, new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, false, true)).stop());
        assertTrue(NetworkShareRecoveryPlan.provider(progress, new NetworkShareRecoveryPlan.ProviderSnapshot(true, true, true, true)).stop());
    }
    @Test public void tetherStopCallbackWithoutNewNativeAbsenceCannotProveRestore() {
        var stopped = new NetworkShareRecoveryPlan.ProviderProgress(true, false, true, true, false, true, true, false);
        assertFalse(NetworkShareRecoveryPlan.provider(stopped, new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, true, true)).restored());
        var observedStop = new NetworkShareRecoveryPlan.ProviderProgress(true, false, true, true, false, true, true, true);
        assertTrue(NetworkShareRecoveryPlan.provider(observedStop, new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, true, true)).restored());
        assertFalse(NetworkShareRecoveryPlan.provider(observedStop, new NetworkShareRecoveryPlan.ProviderSnapshot(true, true, true, true)).restored());
    }
    @Test public void settledJournalCanAcceptVerifiedSystemCleanupAfterDeathWithoutStoppingAnotherAp() {
        var progress = new NetworkShareRecoveryPlan.ProviderProgress(true, true, true, false, false, false, false, false);
        var anotherAp = new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, false, true);
        assertTrue(NetworkShareRecoveryPlan.provider(progress, anotherAp).restored());
        assertFalse(NetworkShareRecoveryPlan.provider(progress, anotherAp).stop());
        assertFalse(NetworkShareRecoveryPlan.provider(progress, new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, true, false)).restored());
    }
    @Test public void redactedTetherConfigurationCannotBeMistakenForOwnApAbsence() {
        var progress = new NetworkShareRecoveryPlan.ProviderProgress(true, true, true, false, false, false, false, false);
        var redacted = new NetworkShareRecoveryPlan.ProviderSnapshot(true, false, false, true, false);
        assertFalse(NetworkShareRecoveryPlan.provider(progress, redacted).stop());
        assertFalse(NetworkShareRecoveryPlan.provider(progress, redacted).restored());
    }
    @Test public void redactedWifiIdentityCannotProveRestorationOrOverrideUserConnection() {
        var missingConfig = new NetworkShareRecoveryPlan.Config(42, null, "*", HASH, 1000, OWNER, true);
        var plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(missingConfig), -1, null);
        assertEquals(-1, plan.remove()); assertFalse(plan.restored()); assertNotNull(plan.error());
        plan = NetworkShareRecoveryPlan.consumer(entry(42, false), List.of(own(42)), 42, "<unknown ssid>");
        assertEquals(42, plan.remove()); assertEquals(-1, plan.reconnect()); assertFalse(plan.restored());
    }
}
