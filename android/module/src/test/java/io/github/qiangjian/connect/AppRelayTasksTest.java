package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class AppRelayTasksTest {
    private static AppRelayRequest request(String id) {
        return new AppRelayRequest(id, "com.example.synthetic", AppRelayRequest.Kind.BROWSER,
                List.of(), "https://example.invalid/relay-test", "", "", "", 0, 0, "");
    }
    private static class Endpoint implements AppRelayTasks.Endpoint {
        int launches, releases, task = -1, closed;
        String error;
        public AppRelayTasks.Prepared prepare(AppRelayRequest r) {
            if (error != null) throw new IllegalStateException(error);
            return () -> "com.example.synthetic";
        }
        public void launch(AppRelayTasks.Prepared p) { launches++; }
        public int observe(AppRelayTasks.Prepared p) { return task; }
        public void release(AppRelayTasks.Prepared p) { releases++; }
        public void close() { closed++; }
    }
    @Test public void dispatchedIsNotCompleted() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        assertEquals(AppRelayTasks.State.PREPARED, tasks.prepare(request("a")).state());
        assertEquals(AppRelayTasks.State.LAUNCHING, tasks.launch("a").state());
        assertEquals(AppRelayTasks.State.LAUNCHING, tasks.status("a").state());
        e.task = 42;
        assertEquals(42, tasks.status("a").taskId());
        assertEquals(AppRelayTasks.State.COMPLETED, tasks.status("a").state());
        assertEquals(1, e.releases);
    }
    @Test public void retriesNeverLaunchTwice() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        tasks.prepare(request("a")); tasks.launch("a"); tasks.prepare(request("a")); tasks.launch("a");
        e.task = 1; tasks.status("a"); tasks.prepare(request("a")); tasks.launch("a");
        assertEquals(1, e.launches);
    }
    @Test public void cancellationBeforeLaunchHasNoSideEffect() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        tasks.prepare(request("a"));
        assertEquals(AppRelayTasks.State.CANCELLED, tasks.cancel("a").state());
        tasks.launch("a"); tasks.cancel("a"); assertEquals(0, e.launches); assertEquals(1, e.releases);
    }
    @Test public void cancellationAfterDispatchCannotPretendToRollback() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        tasks.prepare(request("a")); tasks.launch("a");
        assertEquals("too_late_to_cancel", tasks.cancel("a").code());
        e.task = 12; assertEquals(AppRelayTasks.State.COMPLETED, tasks.status("a").state());
    }
    @Test public void absentAppPropagatesRealFailure() {
        Endpoint e = new Endpoint(); e.error = "target_app_missing";
        AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        assertEquals("target_app_missing", tasks.prepare(request("a")).code());
        tasks.launch("a"); assertEquals(0, e.launches);
    }
    @Test public void revokedAuthorizationPreventsPreparedLaunch() {
        AtomicBoolean authorized = new AtomicBoolean(true); Endpoint e = new Endpoint();
        AppRelayTasks tasks = new AppRelayTasks(e, authorized::get);
        tasks.prepare(request("a")); authorized.set(false);
        assertEquals("session_revoked", tasks.launch("a").code()); assertEquals(0, e.launches); assertEquals(1, e.releases);
    }
    @Test public void deadlineReleasesUnlaunchedGrant() {
        AtomicLong clock = new AtomicLong(1); Endpoint e = new Endpoint();
        AppRelayTasks tasks = new AppRelayTasks(e, () -> true, clock::get);
        tasks.prepare(request("a")); clock.set(16002); tasks.expire();
        assertEquals("request_timeout", tasks.launch("a").code()); assertEquals(0, e.launches); assertEquals(1, e.releases);
    }
    @Test public void sameIdDifferentPayloadIsConflict() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        tasks.prepare(request("a"));
        AppRelayRequest different = new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.BROWSER,
                List.of(), "https://example.invalid/other", "", "", "", 0, 0, "");
        assertEquals("request_conflict", tasks.prepare(different).code()); assertEquals(0, e.launches);
    }
    @Test public void closeReleasesPreparedAndDispatchedExactlyOnce() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        tasks.prepare(request("a")); tasks.prepare(request("b")); tasks.launch("b"); tasks.close();
        tasks.close(); assertEquals(2, e.releases);
        assertEquals(AppRelayTasks.State.FAILED, tasks.status("a").state());
    }
    @Test public void boundedLedgerDoesNotEvictIdempotencyRecords() {
        Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
        for (int i = 0; i < 128; i++) { tasks.prepare(request("id" + i)); tasks.cancel("id" + i); }
        assertEquals("session_capacity", tasks.prepare(request("overflow")).code());
        assertEquals(AppRelayTasks.State.CANCELLED, tasks.prepare(request("id0")).state());
    }
    @Test public void simultaneousCancelAndLaunchHaveOneConsistentOutcome() throws Exception {
        for (int i = 0; i < 30; i++) {
            Endpoint e = new Endpoint(); AppRelayTasks tasks = new AppRelayTasks(e, () -> true);
            tasks.prepare(request("a")); CountDownLatch start = new CountDownLatch(1);
            Thread launch = new Thread(() -> { await(start); tasks.launch("a"); });
            Thread cancel = new Thread(() -> { await(start); tasks.cancel("a"); });
            launch.start(); cancel.start(); start.countDown(); launch.join(); cancel.join();
            assertEquals(e.launches == 0 ? AppRelayTasks.State.CANCELLED : AppRelayTasks.State.LAUNCHING, tasks.status("a").state());
        }
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
}
