package io.github.qiangjian.connect;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.RemoteInputSession.Result.*;

public class RemoteInputReceiverTest {
    private final RemoteInputSession.Scope desktop = new RemoteInputSession.Scope("a".repeat(64), 7, 11);
    private final RemoteInputSession.Scope ime = new RemoteInputSession.Scope("a".repeat(64), 7, 12);
    private final AtomicLong clock = new AtomicLong(100);
    private final AtomicBoolean granted = new AtomicBoolean(true);
    private record Execution(String kind, long sequence, long deadline, Thread thread,
                             CompletableFuture<RemoteInputSession.Result> result) { }
    private static final class Target implements RemoteInputReceiver.Target {
        final BlockingQueue<Execution> executions = new LinkedBlockingQueue<>();
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger releases = new AtomicInteger();
        volatile Thread cleanupThread;
        public CompletionStage<RemoteInputSession.Result> desktop(RemoteInputSession.Scope scope, long seq,
                XiaomiScreenInputCodec.Event event, long deadline) { return add("desktop", seq, deadline); }
        public CompletionStage<RemoteInputSession.Result> text(RemoteInputSession.Scope scope, long seq,
                XiaomiScreenInputCodec.TextEvent event, long deadline) { return add("ime", seq, deadline); }
        private CompletionStage<RemoteInputSession.Result> add(String kind, long seq, long deadline) {
            var result = new CompletableFuture<RemoteInputSession.Result>();
            executions.add(new Execution(kind,seq,deadline,Thread.currentThread(),result)); return result;
        }
        public void close() { releases.incrementAndGet(); cleanupThread = Thread.currentThread(); released.countDown(); }
        Execution next() throws Exception { Execution e = executions.poll(1,TimeUnit.SECONDS); assertNotNull(e); return e; }
    }
    private RemoteInputReceiver receiver(Target target) { return new RemoteInputReceiver(desktop,ime,granted::get,clock::get,target); }
    private RemoteInputSender.Command desktop(long sequence) {
        return new RemoteInputSender.Command(desktop,sequence,new RemoteInputSender.Desktop(
                new XiaomiScreenInputCodec.Event(XiaomiScreenInputCodec.Kind.MOVE,0,0,false,.5f,.5f,0,0,0)));
    }
    private RemoteInputSender.Command text(long sequence) {
        return new RemoteInputSender.Command(ime,sequence,new RemoteInputSender.Text(new XiaomiScreenInputCodec.TextEvent("synthetic")));
    }
    private RemoteInputSession.Result result(CompletionStage<RemoteInputSession.Result> stage) throws Exception {
        return stage.toCompletableFuture().get(1,TimeUnit.SECONDS);
    }
    private void ended(RemoteInputReceiver r, Target t) throws Exception {
        r.termination().toCompletableFuture().get(1,TimeUnit.SECONDS); assertEquals(1,t.releases.get());
    }
    @Test public void desktopAndImeStayInOneFifoUntilActualNativeResults() throws Exception {
        Target target = new Target();
        try (var r = receiver(target)) {
            var first = r.receive(desktop(1),1500); Execution a = target.next();
            var second = r.receive(text(2),1000); var third = r.receive(desktop(3),500);
            assertFalse(first.toCompletableFuture().isDone()); assertFalse(second.toCompletableFuture().isDone());
            assertNotEquals(Thread.currentThread(),a.thread); assertEquals(1600,a.deadline);
            assertTrue(target.executions.isEmpty()); a.result.complete(ACCEPTED); assertEquals(ACCEPTED,result(first));
            Execution b = target.next(); assertEquals("ime",b.kind); assertEquals(2,b.sequence); assertEquals(1100,b.deadline);
            assertTrue(target.executions.isEmpty()); b.result.complete(ACCEPTED); assertEquals(ACCEPTED,result(second));
            Execution c = target.next(); assertEquals("desktop",c.kind); assertEquals(3,c.sequence);
            c.result.complete(ACCEPTED); assertEquals(ACCEPTED,result(third)); r.close(); ended(r,target);
            assertEquals(Thread.currentThread(),target.cleanupThread);
        }
    }
    @Test public void sharedReplayLedgerRejectsDuplicateAcrossInputKinds() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var first = r.receive(desktop(3),1000); var nativeFirst = t.next();
            assertEquals(REPLAY,result(r.receive(text(3),1000)));
            assertEquals(REPLAY,result(r.receive(desktop(2),1000)));
            nativeFirst.result.complete(ACCEPTED); assertEquals(ACCEPTED,result(first)); assertTrue(t.executions.isEmpty());
        }
    }
    @Test public void scopeAndFocusMismatchCannotConsumeLegitimateSequence() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var payload = desktop(1).payload();
            assertEquals(STALE_SESSION,result(r.receive(new RemoteInputSender.Command(
                    new RemoteInputSession.Scope("b".repeat(64),7,11),1,payload),1000)));
            assertEquals(STALE_FOCUS,result(r.receive(new RemoteInputSender.Command(ime,1,payload),1000)));
            var valid = r.receive(desktop(1),1000); t.next().result.complete(ACCEPTED); assertEquals(ACCEPTED,result(valid));
        }
    }
    @Test public void closeDistinguishesQueuedFromDispatchedUnconfirmed() throws Exception {
        Target t = new Target(); var r = receiver(t);
        try {
            var running = r.receive(desktop(1),1000); t.next(); var queued = r.receive(text(2),1000);
            r.close(); assertEquals(STALE_SESSION,result(queued));
            var error = assertThrows(java.util.concurrent.ExecutionException.class,() -> result(running));
            assertTrue(error.getCause() instanceof RemoteInputSender.Unconfirmed);
            ended(r,t); assertTrue(t.executions.isEmpty());
        } finally { r.close(); }
    }
    @Test public void actualNativeRejectionRemainsSpecificAndClosesLease() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var first = r.receive(text(1),1000); Execution e = t.next(); var second = r.receive(desktop(2),1000);
            e.result.complete(NATIVE_REJECTED); assertEquals(NATIVE_REJECTED,result(first));
            assertEquals(STALE_SESSION,result(second)); ended(r,t);
        }
    }
    @Test public void capacityIncludesRunningAndOverflowRevokesBothLanes() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var running = r.receive(desktop(1),1000); t.next(); CompletionStage<RemoteInputSession.Result> last = null;
            for(int i=2;i<=32;i++) last = r.receive(i % 2 == 0 ? text(i) : desktop(i),1000);
            var overflow = r.receive(text(33),1000);
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result(overflow));
            assertEquals(STALE_SESSION,result(last));
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result(running)); ended(r,t);
            assertTrue(t.executions.isEmpty());
        }
    }
    @Test public void timeoutCannotTurnUnknownNativeExecutionIntoRejectionOrSuccess() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var running = r.receive(desktop(1),1000); Execution dispatched = t.next();
            var queued = r.receive(text(2),1000); clock.set(1100);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,() -> result(running));
            assertTrue(error.getCause() instanceof RemoteInputSender.Unconfirmed);
            assertEquals(STALE_SESSION,result(queued)); ended(r,t);
            dispatched.result.complete(ACCEPTED); assertTrue(running.toCompletableFuture().isCompletedExceptionally());
        }
    }
    @Test public void expiredQueuedImeNeverReachesNativeTarget() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var first = r.receive(desktop(1),1000); Execution dispatched = t.next();
            var queued = r.receive(text(2),100); clock.set(300); dispatched.result.complete(ACCEPTED);
            assertEquals(ACCEPTED,result(first));
            var error = assertThrows(java.util.concurrent.ExecutionException.class,() -> result(queued));
            assertTrue(error.getCause() instanceof java.util.concurrent.TimeoutException); ended(r,t); assertTrue(t.executions.isEmpty());
        }
    }
    @Test public void authorityLossClosesSilentNativeRequestAndRejectsLaterInput() throws Exception {
        Target t = new Target();
        try (var r = receiver(t)) {
            var first = r.receive(text(1),1000); t.next(); granted.set(false);
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result(first)); ended(r,t);
            assertEquals(STALE_SESSION,result(r.receive(desktop(2),1000)));
        }
    }
}
