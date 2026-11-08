package io.github.qiangjian.connect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelaySyncClientTest {
    private static final String PKG = "test.synthetic.app";
    private static final class Queue implements AppRelaySender.Scheduler {
        final ArrayDeque<Runnable> now = new ArrayDeque<>(), later = new ArrayDeque<>();
        public void execute(Runnable value) { now.add(value); }
        public void later(Runnable value, long delay) { later.add(value); }
        void drain() { for (int i=0; !now.isEmpty() && i<100; i++) now.remove().run(); assertTrue(now.isEmpty()); }
        void tick() { assertFalse(later.isEmpty()); later.remove().run(); drain(); }
    }
    private static final class Transport implements AppRelaySender.Transport {
        final List<JSONObject> calls = new ArrayList<>();
        final List<CompletableFuture<JSONObject>> replies = new ArrayList<>();
        boolean current = true;
        public boolean current(String peer, String session) { return current; }
        public CompletableFuture<JSONObject> request(String peer, String session, JSONObject payload) {
            calls.add(payload); var reply = new CompletableFuture<JSONObject>(); replies.add(reply); return reply;
        }
        void reply(int index, String state, String code, String pkg, int task) throws Exception {
            replies.get(index).complete(AppRelayCodec.encode(new AppRelayTasks.Result(calls.get(index).getString("request_id"),
                    AppRelayTasks.State.valueOf(state), code, pkg, task)));
        }
    }
    @Test public void sourcePendingIsPolledUntilActualForegroundReceipt() throws Exception {
        Queue queue = new Queue(); Transport rpc = new Transport();
        AppRelaySyncClient client = new AppRelaySyncClient(rpc, queue, () -> 0);
        var result = client.request("peer", "session", PKG, ""); queue.drain();
        assertEquals("sync", rpc.calls.get(0).getString("action"));
        rpc.reply(0, "LAUNCHING", "native_source_requested", PKG, -1); queue.drain(); assertFalse(result.isDone());
        queue.tick(); assertEquals("status", rpc.calls.get(1).getString("action"));
        rpc.reply(1, "COMPLETED", "foreground_observed", PKG, 7); queue.drain();
        assertEquals(AppRelayTasks.State.COMPLETED, result.join().state());
    }
    @Test public void documentTransferIsNotKilledByOneRpcDeadline() throws Exception {
        Queue queue = new Queue(); Transport rpc = new Transport(); AtomicLong time = new AtomicLong();
        AppRelaySyncClient client = new AppRelaySyncClient(rpc, queue, time::get);
        var result = client.request("peer", "session", PKG, "a".repeat(32)); queue.drain();
        assertEquals("a".repeat(32), rpc.calls.get(0).getString("native_owner_token"));
        rpc.reply(0, "LAUNCHING", "native_source_requested", PKG, -1); queue.drain(); time.set(16000); queue.tick();
        assertFalse(result.isDone()); assertEquals("status", rpc.calls.get(1).getString("action"));
        rpc.reply(1, "COMPLETED", "foreground_observed", PKG, 7); queue.drain(); assertEquals(7, result.join().taskId());
    }
    @Test public void ordinarySourceTimeoutSendsTypedCancel() throws Exception {
        Queue queue = new Queue(); Transport rpc = new Transport(); AtomicLong time = new AtomicLong();
        var result = new AppRelaySyncClient(rpc, queue, time::get).request("peer", "session", PKG, ""); queue.drain();
        rpc.reply(0, "LAUNCHING", "native_source_requested", PKG, -1); queue.drain(); time.set(15000); queue.tick();
        assertEquals("request_timeout", result.join().code()); assertEquals("cancel", rpc.calls.get(1).getString("action"));
        assertEquals(rpc.calls.get(0).getString("request_id"), rpc.calls.get(1).getString("request_id"));
    }
    @Test public void cancelledObserverReachesRemoteSourceAndNeverReportsSuccess() throws Exception {
        Queue queue = new Queue(); Transport rpc = new Transport();
        var result = new AppRelaySyncClient(rpc, queue, () -> 0).request("peer", "session", PKG, ""); queue.drain();
        result.cancel(true); queue.drain(); assertTrue(result.isCancelled());
        assertEquals("cancel", rpc.calls.get(1).getString("action"));
        rpc.reply(0, "COMPLETED", "foreground_observed", PKG, 7); queue.drain(); assertTrue(result.isCancelled());
    }
    @Test public void wrongSourcePendingAndPrepareAckAreNotLaunchCompletion() throws Exception {
        for (String state : List.of("PREPARED", "LAUNCHING")) {
            Queue queue = new Queue(); Transport rpc = new Transport();
            var result = new AppRelaySyncClient(rpc, queue, () -> 0).request("peer", "session", PKG, ""); queue.drain();
            rpc.reply(0, state, state.equals("PREPARED") ? "ready" : "native_source_requested", "test.synthetic.other", -1); queue.drain();
            assertEquals("invalid_remote_receipt", result.join().code());
        }
    }
    @Test public void ownerTokenAndSessionAreValidatedBeforeNativeSync() {
        Queue queue = new Queue(); Transport rpc = new Transport();
        AppRelaySyncClient client = new AppRelaySyncClient(rpc, queue, () -> 0);
        assertThrows(IllegalArgumentException.class, () -> client.request("peer", "session", PKG, "A".repeat(32)));
        rpc.current = false; var revoked = client.request("peer", "session", PKG, ""); queue.drain();
        assertEquals("session_revoked", revoked.join().code()); assertTrue(rpc.calls.isEmpty());
    }
}
