package io.github.qiangjian.connect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelaySenderTest {
    private static final class Scheduler implements AppRelaySender.Scheduler {
        final ArrayDeque<Runnable> immediate = new ArrayDeque<>(), delayed = new ArrayDeque<>();
        public void execute(Runnable work) { immediate.add(work); }
        public void later(Runnable work, long delay) { delayed.add(work); }
        void run() { int max = 100; while (!immediate.isEmpty() && max-- > 0) immediate.remove().run(); assertTrue(max > 0); }
    }
    private static final class Transport implements AppRelaySender.Transport {
        final List<JSONObject> calls = new ArrayList<>();
        final List<CompletableFuture<JSONObject>> replies = new ArrayList<>();
        boolean current = true;
        public boolean current(String peer, String session) { return current; }
        public CompletableFuture<JSONObject> request(String peer, String session, JSONObject payload) {
            calls.add(payload); CompletableFuture<JSONObject> reply = new CompletableFuture<>(); replies.add(reply); return reply;
        }
        void reply(int index, String state, String code, int task) throws Exception {
            replies.get(index).complete(new JSONObject().put("version", 1).put("request_id", "synthetic")
                    .put("state", state).put("code", code).put("target_package", "test.synthetic.browser").put("task_id", task));
        }
        long launches() { return calls.stream().filter(call -> "launch".equals(call.optString("action"))).count(); }
    }
    private AppRelayRequest request() {
        return new AppRelayRequest("synthetic", "test.synthetic.browser", AppRelayRequest.Kind.BROWSER,
                List.of(), "https://example.invalid/synthetic", "", "", "", 0, 0, "");
    }
    @Test public void nativeCancelBeforePrepareReceiptNeverLaunches() throws Exception {
        Scheduler queue = new Scheduler(); Transport rpc = new Transport(); AppRelaySender sender = new AppRelaySender(rpc, queue);
        var result = sender.send("peer", "session", request()); queue.run(); sender.cancel("synthetic"); queue.run();
        rpc.reply(0, "PREPARED", "ready", -1); queue.run();
        assertEquals("cancel", rpc.calls.get(1).getString("action"));
        rpc.reply(1, "CANCELLED", "cancelled_before_launch", -1); queue.run();
        assertEquals(AppRelayTasks.State.CANCELLED, result.join().state()); assertEquals(0, rpc.launches());
    }
    @Test public void cancelledFutureCleansRemotePreparationEvenIfReceiptArrivesLater() throws Exception {
        Scheduler queue = new Scheduler(); Transport rpc = new Transport(); AppRelaySender sender = new AppRelaySender(rpc, queue);
        var result = sender.send("peer", "session", request()); queue.run(); result.cancel(true); queue.run();
        rpc.reply(0, "PREPARED", "ready", -1); queue.run();
        assertEquals(0, rpc.launches()); assertTrue(rpc.calls.size() >= 2);
        assertEquals("cancel", rpc.calls.get(rpc.calls.size() - 1).getString("action"));
    }
    @Test public void sessionRevocationRejectsLateSuccessAndDoesNotLaunch() throws Exception {
        Scheduler queue = new Scheduler(); Transport rpc = new Transport(); AppRelaySender sender = new AppRelaySender(rpc, queue);
        var result = sender.send("peer", "session", request()); queue.run(); rpc.current = false;
        rpc.reply(0, "COMPLETED", "foreground_observed", 123); queue.run();
        assertEquals("session_revoked", result.join().code()); assertEquals(0, rpc.launches());
    }
    @Test public void tooLateCancellationConsumesActualResultWithoutInventingCancelSuccess() throws Exception {
        Scheduler queue = new Scheduler(); Transport rpc = new Transport(); AppRelaySender sender = new AppRelaySender(rpc, queue);
        var result = sender.send("peer", "session", request()); queue.run(); rpc.reply(0, "PREPARED", "ready", -1); queue.run();
        sender.cancel("synthetic"); queue.run(); assertEquals("cancel", rpc.calls.get(2).getString("action"));
        rpc.reply(2, "LAUNCHING", "too_late_to_cancel", -1); queue.run();
        rpc.reply(3, "COMPLETED", "foreground_observed", 123); queue.run();
        assertEquals(AppRelayTasks.State.COMPLETED, result.join().state()); assertEquals(1, rpc.launches());
    }
}
