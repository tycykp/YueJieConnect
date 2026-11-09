package io.github.yuejie.connect;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Actual desktop lane plus the already active OEM IME. main must dispatch onto Android's real
 * main Looper; OplusScreenInputAdapter enforces this. No IME is enabled or switched here. */
public final class RemoteInputOplusTarget implements RemoteInputReceiver.Target {
    private final RemoteInputDesktopExecutor desktop;
    private final OplusScreenInputAdapter ime;
    private final RemoteInputSession.Scope imeScope;
    private final Executor main;
    private final BooleanSupplier currentAndInputAllowed;
    private final LongSupplier elapsed;
    private final AtomicBoolean closed = new AtomicBoolean();
    public RemoteInputOplusTarget(RemoteInputDesktopExecutor desktop, OplusScreenInputAdapter ime, RemoteInputSession.Scope actualImeFocus,
            Executor main, BooleanSupplier currentAndInputAllowed, LongSupplier elapsedRealtimeMs) {
        this.desktop = Objects.requireNonNull(desktop); this.ime = ime; this.main = Objects.requireNonNull(main);
        if ((ime == null) != (actualImeFocus == null)) throw new IllegalArgumentException("native_ime_binding_missing");
        imeScope = actualImeFocus;
        this.currentAndInputAllowed = Objects.requireNonNull(currentAndInputAllowed); elapsed = Objects.requireNonNull(elapsedRealtimeMs);
    }
    public CompletionStage<RemoteInputSession.Result> desktop(RemoteInputSession.Scope scope, long sequence,
            XiaomiScreenInputCodec.Event event, long deadlineElapsedMs) {
        if (closed.get()) return CompletableFuture.completedStage(RemoteInputSession.Result.STALE_SESSION);
        return desktop.submit(scope, sequence, event, deadlineElapsedMs);
    }
    public CompletionStage<RemoteInputSession.Result> text(RemoteInputSession.Scope scope, long sequence,
            XiaomiScreenInputCodec.TextEvent event, long deadlineElapsedMs) {
        CompletableFuture<RemoteInputSession.Result> result = new CompletableFuture<>();
        if (ime == null) return CompletableFuture.completedStage(RemoteInputSession.Result.NO_FOCUS);
        if (!imeScope.equals(scope)) return CompletableFuture.completedStage(RemoteInputSession.Result.STALE_FOCUS);
        try { main.execute(() -> {
            try {
                if (closed.get()) result.complete(RemoteInputSession.Result.STALE_SESSION);
                else if (elapsed.getAsLong() >= deadlineElapsedMs)
                    result.completeExceptionally(new IllegalStateException("native_input_expired_before_execution"));
                else if (!currentAndInputAllowed.getAsBoolean()) result.complete(RemoteInputSession.Result.DENIED);
                else result.complete(ime.text(scope, sequence, event.text()));
            } catch (RuntimeException error) { result.completeExceptionally(new IllegalStateException("native_ime_execution_unconfirmed")); }
        }); } catch (RuntimeException error) { result.completeExceptionally(new IllegalStateException("native_ime_dispatch_failed")); }
        return result.minimalCompletionStage();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        desktop.close();
        if (ime != null) main.execute(() -> ime.revoke(imeScope));
    }
}
