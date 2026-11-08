package io.github.qiangjian.connect;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelaySyncTest {
    private static final String PKG = "test.synthetic.app";
    private static final class Source implements AppRelaySync.Native {
        int starts, cancels;
        String id;
        final CompletableFuture<AppRelayTasks.Result> nativeResult = new CompletableFuture<>();
        public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { starts++; this.id = id; return nativeResult; }
        public void cancel(String id) { cancels++; }
        void success(String code, int task) { nativeResult.complete(new AppRelayTasks.Result(id, AppRelayTasks.State.COMPLETED, code, PKG, task)); }
    }
    private AppRelayCatalog.Update catalog(boolean visible) {
        return new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(PKG, "Synthetic", AppRelayCatalog.Kind.APP, visible, visible, 1, "")));
    }
    @Test public void onlyAdvertisedCurrentSourceCanBeRequested() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        assertEquals("native_source_unavailable", sync.request("unadvertised", PKG).join().code()); assertEquals(0, source.starts);
        sync.observe(catalog(true)); assertFalse(sync.request("synthetic", PKG).isDone()); assertEquals(1, source.starts);
        source.success("foreground_observed", 7); assertEquals(AppRelayTasks.State.COMPLETED, sync.request("synthetic", PKG).join().state());
        assertEquals(1, source.starts);
    }
    @Test public void hideWithdrawsSourceAndRequestIdentityCannotChangePackage() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        sync.observe(catalog(true)); sync.observe(catalog(false));
        assertEquals("native_source_unavailable", sync.request("hidden", PKG).join().code());
        assertEquals("request_conflict", sync.request("hidden", "test.synthetic.other").join().code());
        assertEquals(0, source.starts);
    }
    @Test public void acknowledgementAndIntermediateLaunchAreNotSuccess() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        sync.observe(catalog(true)); var result = sync.request("synthetic", PKG);
        source.success("native_sync_requested", 7);
        assertEquals("invalid_remote_receipt", result.join().code());
    }
    @Test public void cancelledFutureDoesNotCancelTheSharedDeduplicationRecord() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        sync.observe(catalog(true)); var observer = sync.request("synthetic", PKG); observer.cancel(true);
        var replay = sync.request("synthetic", PKG); source.success("foreground_observed", 7);
        assertEquals(AppRelayTasks.State.COMPLETED, replay.join().state()); assertEquals(1, source.starts);
    }
    @Test public void cancellationWaitsForActualResultAndCannotRebindLateData() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        sync.observe(catalog(true)); var result = sync.request("synthetic", PKG); var cancellation = sync.cancel("synthetic");
        assertFalse(cancellation.isDone()); assertEquals(1, source.cancels);
        source.success("foreground_observed", 7); assertEquals(AppRelayTasks.State.COMPLETED, result.join().state());
        assertEquals("native_source_already_requested", sync.request("later", PKG).join().code()); assertEquals(1, source.starts);
    }
    @Test public void cancelBeforeRequestLeavesTerminalTombstone() {
        Source source = new Source(); AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0);
        sync.cancel("synthetic"); sync.observe(catalog(true));
        assertEquals(AppRelayTasks.State.CANCELLED, sync.request("synthetic", PKG).join().state()); assertEquals(0, source.starts);
    }
    @Test public void expiryAndSessionRevocationRejectLateNativeSuccess() {
        Source source = new Source(); AtomicLong time = new AtomicLong();
        AppRelaySync sync = new AppRelaySync(source, () -> true, time::get); sync.observe(catalog(true));
        var result = sync.request("synthetic", PKG); time.set(15000); sync.expire(); source.success("foreground_observed", 7);
        assertEquals("request_timeout", result.join().code()); assertEquals(1, source.cancels);
        Source other = new Source(); AppRelaySync scope = new AppRelaySync(other, () -> true, time::get);
        scope.observe(catalog(true)); var revoked = scope.request("revoked", PKG); scope.close(); other.success("foreground_observed", 7);
        assertEquals("session_revoked", revoked.join().code());
    }
    @Test public void strictSyncSchemaCannotCarryNativeIdsOrIntents() throws Exception {
        JSONObject valid = new JSONObject().put("action", "sync").put("request_id", "synthetic").put("source_package", PKG);
        AppRelayCodec.validateAction(valid);
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject(valid.toString()).put("native_peer", "guessed")));
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject(valid.toString()).put("source_package", 7)));
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject(valid.toString()).put("source_package", "intent://synthetic")));
    }
    @Test public void nativeProcessPidIsLocalMetadataAndCannotBecomeRemotePackage() throws Exception {
        assertEquals(PKG, XiaomiRelayCatalog.packageOfProcess(PKG + "~1234"));
        assertThrows(IllegalArgumentException.class, () -> XiaomiRelayCatalog.packageOfProcess(PKG + "~1234~5678"));
        assertThrows(IllegalArgumentException.class, () -> XiaomiRelayCatalog.packageOfProcess(PKG + "~0"));
    }
    @Test public void nativeCompletionUnderSdkLockDoesNotDeadlockConcurrentExpiry() throws Exception {
        Object nativeLock = new Object();
        var holdingNative = new java.util.concurrent.CountDownLatch(1);
        var cancelling = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<AppRelayTasks.Result> nativeResult = new CompletableFuture<>();
        AtomicLong time = new AtomicLong();
        AppRelaySync.Native source = new AppRelaySync.Native() {
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { return nativeResult; }
            public void cancel(String id) { cancelling.countDown(); synchronized (nativeLock) { /* Actual SDK cancellation lock. */ } }
        };
        AppRelaySync sync = new AppRelaySync(source, () -> true, time::get); sync.observe(catalog(true));
        var result = sync.request("synthetic", PKG);
        Thread nativeThread = new Thread(() -> { synchronized (nativeLock) {
            holdingNative.countDown();
            try { if (!cancelling.await(2, java.util.concurrent.TimeUnit.SECONDS)) return; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            nativeResult.complete(new AppRelayTasks.Result("synthetic", AppRelayTasks.State.COMPLETED, "foreground_observed", PKG, 7));
        }});
        nativeThread.setDaemon(true); nativeThread.start();
        assertTrue(holdingNative.await(2, java.util.concurrent.TimeUnit.SECONDS)); time.set(15000);
        Thread expiry = new Thread(sync::expire); expiry.setDaemon(true); expiry.start();
        nativeThread.join(2000); expiry.join(2000);
        assertFalse("native callback waited for registry while holding SDK lock", nativeThread.isAlive());
        assertFalse("expiry waited for native lock while holding registry", expiry.isAlive());
        assertEquals("request_timeout", result.join().code());
    }
    @Test public void documentSyncRequiresLocalClickTokenAndHasItsOwnBoundedLifetime() {
        Source source = new Source(); AtomicLong time = new AtomicLong();
        AppRelaySync.Native documentSource = new AppRelaySync.Native() {
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { fail("document token lost"); return null; }
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg, String token) { assertEquals("a".repeat(32), token); return source.request(id, pkg); }
            public void cancel(String id) { source.cancel(id); }
        };
        AppRelaySync sync = new AppRelaySync(documentSource, () -> true, time::get);
        sync.observe(new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(PKG, "Synthetic", AppRelayCatalog.Kind.DOCUMENT, true, true, 1, ""))));
        assertEquals("native_document_consent_required", sync.request("no-click", PKG).join().code());
        var result = sync.request("synthetic", PKG, "a".repeat(32));
        assertEquals("native_source_requested", sync.status("synthetic").code()); time.set(16000); sync.expire(); assertFalse(result.isDone());
        time.set(30*60*1000); sync.expire(); assertEquals("request_timeout", result.join().code());
    }
    @Test public void aFreshOriginalSourcePublicationAfterNativeResultAllowsNormalRepeatedHandoff() {
        var completions = new java.util.ArrayList<CompletableFuture<AppRelayTasks.Result>>();
        AppRelaySync.Native source = new AppRelaySync.Native() {
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { var future = new CompletableFuture<AppRelayTasks.Result>(); completions.add(future); return future; }
            public void cancel(String id) {}
        };
        AppRelaySync sync = new AppRelaySync(source, () -> true, () -> 0); sync.observe(catalog(true));
        var first = sync.request("first", PKG);
        sync.observe(catalog(true)); // An update while the first SDK request still owns the slot is insufficient.
        completions.get(0).complete(new AppRelayTasks.Result("first", AppRelayTasks.State.COMPLETED, "foreground_observed", PKG, 7)); first.join();
        assertEquals("native_source_already_requested", sync.request("old-epoch", PKG).join().code());
        sync.observe(catalog(true)); var second = sync.request("second", PKG); assertEquals(2, completions.size());
        completions.get(1).complete(new AppRelayTasks.Result("second", AppRelayTasks.State.COMPLETED, "foreground_observed", PKG, 8));
        assertEquals(8, second.join().taskId()); assertEquals(7, sync.request("first", PKG).join().taskId());
    }
    @Test public void evenFreshPublicationCannotRebindTimedOutNativeReply() {
        Source source = new Source(); AtomicLong time = new AtomicLong();
        AppRelaySync sync = new AppRelaySync(source, () -> true, time::get); sync.observe(catalog(true));
        sync.request("first", PKG); time.set(15000); sync.expire(); sync.observe(catalog(true));
        assertEquals("native_source_already_requested", sync.request("later", PKG).join().code()); assertEquals(1, source.starts);
    }
    @Test public void documentOwnerTokenCannotBeNullEmptyOrReplacedWithExtraFields() throws Exception {
        JSONObject valid = new JSONObject().put("action", "sync").put("request_id", "synthetic").put("source_package", PKG).put("native_owner_token", "a".repeat(32));
        AppRelayCodec.validateAction(valid);
        for (Object invalid : java.util.List.of("", "A".repeat(32), "a".repeat(31), 7, JSONObject.NULL))
            assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject(valid.toString()).put("native_owner_token", invalid)));
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject(valid.toString()).put("purpose", "drag")));
    }
}
