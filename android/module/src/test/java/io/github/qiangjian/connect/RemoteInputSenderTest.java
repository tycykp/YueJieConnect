package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class RemoteInputSenderTest {
    private final RemoteInputSession.Scope desktop = new RemoteInputSession.Scope("b".repeat(64), 5, 1);
    private final RemoteInputSession.Scope ime = new RemoteInputSession.Scope("b".repeat(64), 5, 2);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicLong now = new AtomicLong(100);
    private final ArrayList<RemoteInputSender.Command> commands = new ArrayList<>();
    private final ArrayList<CompletableFuture<RemoteInputSession.Result>> remote = new ArrayList<>();
    private int revokes;
    private final RemoteInputSender.Transport transport = new RemoteInputSender.Transport() {
        public CompletionStage<RemoteInputSession.Result> send(RemoteInputSender.Command command, long budget) {
            assertEquals(2000, budget); commands.add(command);
            var result = new CompletableFuture<RemoteInputSession.Result>(); remote.add(result); return result;
        }
        public void revoke(String reason) { revokes++; }
    };
    private RemoteInputSender sender() { return new RemoteInputSender(desktop, ime, allowed::get, now::get, transport); }
    private XiaomiScreenInputCodec.Event move() {
        return new XiaomiScreenInputCodec.Event(XiaomiScreenInputCodec.Kind.MOVE, 0, 0, false, .5f, .5f, 0, 0, 0);
    }
    private static Throwable failure(CompletionStage<?> result) {
        return assertThrows(CompletionException.class, () -> result.toCompletableFuture().join()).getCause();
    }
    @Test public void oneOrderedLedgerKeepsImeAndDesktopFocusDistinctAndWaitsForRealResults() {
        var sender = sender();
        var first = sender.desktop(move()).toCompletableFuture();
        var text = sender.text(new XiaomiScreenInputCodec.TextEvent("synthetic text")).toCompletableFuture();
        assertFalse(first.isDone()); assertFalse(text.isDone());
        assertEquals(2, sender.outstanding());
        assertEquals(1, commands.get(0).sequence()); assertEquals(desktop, commands.get(0).scope());
        assertEquals(2, commands.get(1).sequence()); assertEquals(ime, commands.get(1).scope());
        assertEquals("RemoteInputCommand", commands.get(1).toString());
        remote.get(0).complete(RemoteInputSession.Result.ACCEPTED);
        assertEquals(RemoteInputSession.Result.ACCEPTED, first.join()); assertFalse(text.isDone());
        remote.get(1).complete(RemoteInputSession.Result.ACCEPTED);
        assertEquals(RemoteInputSession.Result.ACCEPTED, text.join()); assertEquals(0, sender.outstanding());
    }
    @Test public void outstandingBoundIncludesAllInFlightResultsAndOverflowRevokesWithoutRetry() {
        var sender = sender(); var first = sender.desktop(move());
        for (int i = 1; i < 32; i++) sender.desktop(move());
        assertEquals(32, sender.outstanding());
        assertTrue(failure(sender.desktop(move())) instanceof RejectedExecutionException);
        assertEquals(32, commands.size()); assertEquals(1, revokes); assertEquals(0, sender.outstanding());
        assertTrue(failure(first) instanceof RemoteInputSender.Unconfirmed);
        remote.get(0).complete(RemoteInputSession.Result.ACCEPTED); // No fabricated cancellation/native failure.
        assertTrue(failure(first) instanceof RemoteInputSender.Unconfirmed);
        assertFalse(sender.current()); sender.close(); assertEquals(1, revokes);
    }
    @Test public void missingResultDeadlineIsUnconfirmedAndDoesNotExecuteAgain() {
        var sender = sender(); var result = sender.desktop(move());
        now.set(2100); sender.tick();
        assertTrue(failure(result) instanceof RemoteInputSender.Unconfirmed);
        assertEquals(1, revokes); assertEquals(1, commands.size());
        now.set(100); assertTrue(failure(sender.desktop(move())) instanceof RejectedExecutionException);
        assertEquals(1, commands.size());
    }
    @Test public void actualNativeFailureIsPreservedAndOtherInflightResultsRemainUnconfirmed() {
        var sender = sender(); var first = sender.desktop(move()); var second = sender.desktop(move());
        remote.get(0).complete(RemoteInputSession.Result.NATIVE_REJECTED);
        assertEquals(RemoteInputSession.Result.NATIVE_REJECTED, first.toCompletableFuture().join());
        assertTrue(failure(second) instanceof RemoteInputSender.Unconfirmed);
        assertEquals(1, revokes); assertFalse(sender.current());
    }
    @Test public void revokedRouteCannotReviveAndMissingImeIsLocalRejection() {
        var sender = sender(); var first = sender.desktop(move()); allowed.set(false); sender.tick();
        assertTrue(failure(first) instanceof RemoteInputSender.Unconfirmed);
        allowed.set(true); assertFalse(sender.current());
        var noIme = new RemoteInputSender(desktop, null, allowed::get, now::get, transport);
        assertTrue(failure(noIme.text(new XiaomiScreenInputCodec.TextEvent("synthetic"))) instanceof RejectedExecutionException);
        assertEquals(1, commands.size()); assertEquals(0, noIme.outstanding());
    }
    @Test public void mismatchedSessionFocusAndThrowingAuthorityNeverDispatch() {
        var wrong = new RemoteInputSession.Scope("c".repeat(64), 5, 2);
        assertThrows(IllegalArgumentException.class, () -> new RemoteInputSender(desktop, wrong, allowed::get, now::get, transport));
        var sender = new RemoteInputSender(desktop, ime, () -> { throw new IllegalStateException(); }, now::get, transport);
        assertTrue(failure(sender.desktop(move())) instanceof RejectedExecutionException);
        assertEquals(0, commands.size()); assertEquals(1, revokes);
    }
}
