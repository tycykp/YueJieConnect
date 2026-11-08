package io.github.qiangjian.connect;

import org.junit.Test;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.qiangjian.connect.RemoteInputSession.Result.*;
import static org.junit.Assert.*;

public class RemoteInputDesktopExecutorTest {
    private final RemoteInputSession.Scope scope = new RemoteInputSession.Scope("e".repeat(64), 15, 7);
    private static final class Native implements RemoteInputDesktopSession.Target {
        final CountDownLatch entered = new CountDownLatch(1);
        final Semaphore proceed = new Semaphore(0);
        final AtomicInteger calls = new AtomicInteger(), releases = new AtomicInteger();
        public boolean hasFocus(long epoch) { return true; }
        public boolean mouse(RemoteInputDesktopSession.Mouse event) { calls.incrementAndGet(); return true; }
        public boolean key(RemoteInputDesktopSession.Key event) {
            calls.incrementAndGet(); entered.countDown(); proceed.acquireUninterruptibly(); return true;
        }
        public RemoteInputDesktopSession.ReleaseResult release(RemoteInputDesktopSession.Reset reset) {
            releases.incrementAndGet(); return RemoteInputDesktopSession.ReleaseResult.RELEASED;
        }
    }
    private static XiaomiScreenInputCodec.Event key() {
        return new XiaomiScreenInputCodec.Event(XiaomiScreenInputCodec.Kind.KEY,29,0,true,0,0,0,0,0);
    }
    private static XiaomiScreenInputCodec.Event move() {
        return new XiaomiScreenInputCodec.Event(XiaomiScreenInputCodec.Kind.MOVE,0,0,false,.5f,.5f,0,0,0);
    }
    private RemoteInputDesktopExecutor executor(Native target, AtomicLong clock) {
        return new RemoteInputDesktopExecutor(scope, () -> true, clock::get, clock::get, target);
    }
    @Test public void enqueueIsNotSuccessAndCloseDoesNotFalsifyRunningResult() throws Exception {
        Native target = new Native(); AtomicLong clock = new AtomicLong(100);
        try (var executor = executor(target, clock)) {
            var running = executor.submit(scope,1,key(),1000).toCompletableFuture();
            assertTrue(target.entered.await(1,TimeUnit.SECONDS)); assertFalse(running.isDone());
            var queued = executor.submit(scope,2,move(),1000).toCompletableFuture();
            executor.close();
            assertEquals(STALE_SESSION,queued.get(1,TimeUnit.SECONDS)); assertFalse(running.isDone());
            target.proceed.release(); assertEquals(ACCEPTED,running.get(1,TimeUnit.SECONDS));
            assertEquals(RemoteInputDesktopSession.ReleaseResult.RELEASED,executor.termination().toCompletableFuture().get(1,TimeUnit.SECONDS));
            assertEquals(1,target.calls.get()); assertEquals(1,target.releases.get());
        } finally { target.proceed.release(); }
    }
    @Test public void capacityIncludesInFlightAndOverloadCancelsHeldInput() throws Exception {
        Native target = new Native(); AtomicLong clock = new AtomicLong(100);
        try (var executor = executor(target, clock)) {
            var running = executor.submit(scope,1,key(),1000).toCompletableFuture();
            assertTrue(target.entered.await(1,TimeUnit.SECONDS));
            CompletionStage<?> queued = null;
            for(int i=2;i<=RemoteInputDesktopExecutor.MAX_PENDING;i++) queued=executor.submit(scope,i,move(),1000);
            var rejected = executor.submit(scope,33,move(),1000).toCompletableFuture();
            assertTrue(rejected.isCompletedExceptionally()); assertEquals(STALE_SESSION,queued.toCompletableFuture().get(1,TimeUnit.SECONDS));
            target.proceed.release(); assertEquals(ACCEPTED,running.get(1,TimeUnit.SECONDS));
            executor.termination().toCompletableFuture().get(1,TimeUnit.SECONDS); assertEquals(1,target.calls.get());
        } finally { target.proceed.release(); }
    }
    @Test public void queuedExpiredReleaseNeverExecutesAndClosesLease() throws Exception {
        Native target = new Native(); AtomicLong clock = new AtomicLong(100);
        try (var executor = executor(target, clock)) {
            var running = executor.submit(scope,1,key(),1000).toCompletableFuture();
            assertTrue(target.entered.await(1,TimeUnit.SECONDS));
            var queued = executor.submit(scope,2,move(),200).toCompletableFuture();
            clock.set(300); target.proceed.release(); assertEquals(ACCEPTED,running.get(1,TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> queued.get(1,TimeUnit.SECONDS));
            executor.termination().toCompletableFuture().get(1,TimeUnit.SECONDS);
            assertEquals(1,target.calls.get()); assertEquals(1,target.releases.get());
        } finally { target.proceed.release(); }
    }
}
