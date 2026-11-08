package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Serial execution at the current native focus. No payload/text is retained or logged. */
public final class RemoteInputSession implements AutoCloseable {
    public record Scope(String sessionId, long streamId, long focusEpoch) {
        public Scope {
            if (sessionId == null || !sessionId.matches("[0-9a-f]{64}") || streamId <= 0 || focusEpoch <= 0)
                throw new IllegalArgumentException("invalid_input_scope");
        }
    }
    public enum Result { ACCEPTED, DENIED, STALE_SESSION, STALE_FOCUS, REPLAY, NO_FOCUS, INVALID_PAYLOAD, NATIVE_REJECTED }
    public interface Target {
        boolean hasFocus(long epoch);
        boolean commitText(String text);
        boolean key(int keyCode, boolean down);
        void cancel();
    }
    private final Scope scope;
    private final BooleanSupplier allowed;
    private final Target target;
    private long lastSequence;
    private boolean closed;

    public RemoteInputSession(Scope scope, BooleanSupplier allowed, Target target) {
        this.scope = java.util.Objects.requireNonNull(scope);
        this.allowed = java.util.Objects.requireNonNull(allowed); this.target = java.util.Objects.requireNonNull(target);
    }
    private Result check(Scope request, long sequence) {
        if (request == null || sequence <= 0) return Result.INVALID_PAYLOAD;
        if (closed || !scope.sessionId.equals(request.sessionId) || scope.streamId != request.streamId) return Result.STALE_SESSION;
        if (scope.focusEpoch != request.focusEpoch) return Result.STALE_FOCUS;
        if (sequence <= lastSequence) return Result.REPLAY;
        lastSequence = sequence; // Every attempted operation is one-shot, including native failures.
        boolean permitted;
        try { permitted = allowed.getAsBoolean(); } catch (RuntimeException error) { permitted = false; }
        if (!permitted) { close(); return Result.DENIED; }
        boolean focused;
        try { focused = target.hasFocus(scope.focusEpoch); } catch (RuntimeException error) { focused = false; }
        if (!focused) { close(); return Result.NO_FOCUS; }
        return Result.ACCEPTED;
    }
    public synchronized Result text(Scope request, long sequence, String text) {
        Result result = check(request, sequence); if (result != Result.ACCEPTED) return result;
        if (text == null || text.isEmpty() || text.length() > 8192 || text.indexOf('\0') >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > 8192 || !validUnicode(text)) return Result.INVALID_PAYLOAD;
        try { return target.commitText(text) ? Result.ACCEPTED : Result.NATIVE_REJECTED; }
        catch (RuntimeException error) { return Result.NATIVE_REJECTED; }
    }
    public synchronized Result key(Scope request, long sequence, int keyCode, boolean down) {
        Result result = check(request, sequence); if (result != Result.ACCEPTED) return result;
        if (keyCode < 1 || keyCode > 304) return Result.INVALID_PAYLOAD;
        try { return target.key(keyCode, down) ? Result.ACCEPTED : Result.NATIVE_REJECTED; }
        catch (RuntimeException error) { return Result.NATIVE_REJECTED; }
    }
    private static boolean validUnicode(String text) {
        for (int n = 0; n < text.length(); n++) {
            char c = text.charAt(n);
            if (Character.isHighSurrogate(c)) {
                if (++n == text.length() || !Character.isLowSurrogate(text.charAt(n))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            try { target.cancel(); } catch (RuntimeException ignored) { }
        }
    }
}
