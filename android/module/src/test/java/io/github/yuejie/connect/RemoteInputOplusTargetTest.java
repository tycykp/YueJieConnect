package io.github.yuejie.connect;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.RemoteInputSession.Result.*;

/** Assembly tests use only a host Looper double. The real adapter/session/target implementations
 * run; injected controlled native targets do not assert any device/OEM focus or permission. */
public class RemoteInputOplusTargetTest {
    private final RemoteInputSession.Scope focus = new RemoteInputSession.Scope("c".repeat(64),7,12);
    private final AtomicBoolean granted = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(100);
    private static final class Main implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        public void execute(Runnable job) { jobs.add(job); }
        void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
    }
    private static final class Native implements RemoteInputSession.Target {
        int commits, cancels; boolean accepts = true;
        public boolean hasFocus(long epoch) { return true; }
        public boolean commitText(String text) { assertEquals("synthetic",text); commits++; return accepts; }
        public boolean key(int code,boolean down) { return true; }
        public void cancel() { cancels++; }
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance,value);
    }
    private OplusScreenInputAdapter ime(RemoteInputSession.Scope scope, Native target) throws Exception {
        var adapter = new OplusScreenInputAdapter(null,null); bind(adapter,scope,target); return adapter;
    }
    private void bind(OplusScreenInputAdapter adapter,RemoteInputSession.Scope scope,Native target) throws Exception {
        set(adapter,"boundScope",scope); set(adapter,"session",new RemoteInputSession(scope,granted::get,target));
    }
    private RemoteInputDesktopExecutor desktop() {
        return new RemoteInputDesktopExecutor(focus,granted::get,clock::get,clock::get,new RemoteInputDesktopSession.Target() {
            public boolean hasFocus(long epoch) { return true; }
            public boolean mouse(RemoteInputDesktopSession.Mouse event) { return true; }
            public boolean key(RemoteInputDesktopSession.Key event) { return true; }
            public RemoteInputDesktopSession.ReleaseResult release(RemoteInputDesktopSession.Reset reset) {
                return RemoteInputDesktopSession.ReleaseResult.RELEASED;
            }
        });
    }
    private XiaomiScreenInputCodec.TextEvent text() { return new XiaomiScreenInputCodec.TextEvent("synthetic"); }
    private RemoteInputSession.Result result(CompletionStage<RemoteInputSession.Result> stage) throws Exception {
        return stage.toCompletableFuture().get(1,TimeUnit.SECONDS);
    }
    @Test public void imeWaitsForMainAndReturnsActualNativeRejection() throws Exception {
        Main main = new Main(); Native nativeTarget = new Native(); nativeTarget.accepts = false;
        var adapter = ime(focus,nativeTarget);
        try (var target = new RemoteInputOplusTarget(desktop(),adapter,focus,main,granted::get,clock::get)) {
            var response = target.text(focus,1,text(),1000); assertFalse(response.toCompletableFuture().isDone());
            assertEquals(0,nativeTarget.commits); main.drain(); assertEquals(NATIVE_REJECTED,result(response));
            assertEquals(1,nativeTarget.commits);
        } finally { main.drain(); }
    }
    @Test public void nativeMainRechecksDeadlineAndAuthorityBeforeCommit() throws Exception {
        Main main = new Main(); Native nativeTarget = new Native(); var adapter = ime(focus,nativeTarget);
        try (var target = new RemoteInputOplusTarget(desktop(),adapter,focus,main,granted::get,clock::get)) {
            var expired = target.text(focus,1,text(),200); clock.set(200); main.drain();
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result(expired));
            var denied = target.text(focus,2,text(),1000); granted.set(false); main.drain();
            assertEquals(DENIED,result(denied)); assertEquals(0,nativeTarget.commits);
        } finally { main.drain(); }
    }
    @Test public void closeFencesQueuedTextAndOldCleanupCannotRevokeNewFocus() throws Exception {
        Main main = new Main(); Native previous = new Native(), next = new Native(); var adapter = ime(focus,previous);
        var target = new RemoteInputOplusTarget(desktop(),adapter,focus,main,granted::get,clock::get);
        var queued = target.text(focus,1,text(),1000); target.close();
        var newFocus = new RemoteInputSession.Scope(focus.sessionId(),focus.streamId(),13); bind(adapter,newFocus,next);
        main.drain(); assertEquals(STALE_SESSION,result(queued));
        assertEquals(0,previous.commits); assertEquals(0,next.cancels);
        assertEquals(ACCEPTED,adapter.text(newFocus,1,"synthetic")); assertEquals(1,next.commits);
        adapter.revoke(focus); assertEquals(0,next.cancels); adapter.revoke(newFocus); assertEquals(1,next.cancels);
    }
    @Test public void matchingCleanupCancelsOnlyItsOwnSessionOnce() throws Exception {
        Main main = new Main(); Native nativeTarget = new Native(); var adapter = ime(focus,nativeTarget);
        var target = new RemoteInputOplusTarget(desktop(),adapter,focus,main,granted::get,clock::get);
        var other = new RemoteInputSession.Scope(focus.sessionId(),focus.streamId(),13);
        assertEquals(STALE_FOCUS,result(target.text(other,1,text(),1000)));
        target.close(); target.close(); main.drain(); assertEquals(1,nativeTarget.cancels);
        assertEquals(NO_FOCUS,adapter.text(focus,1,"synthetic"));
    }
}
