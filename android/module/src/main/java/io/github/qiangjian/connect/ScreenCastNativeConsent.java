package io.github.qiangjian.connect;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One native request/permission lifecycle. Only original OEM callbacks may call prompted/allowed.
 * A normal API return, an old projection or a callback for another DeviceInfo cannot grant this lease. */
public final class ScreenCastNativeConsent implements AutoCloseable {
    public enum State { WAITING_REQUEST, WAITING_PROMPT, PROMPTED, ALLOWED, CLOSED }
    private final BooleanSupplier currentRequest;
    private final LongSupplier elapsed;
    private final long deadline;
    private Object identity;
    private State state = State.WAITING_REQUEST;

    public ScreenCastNativeConsent(long deadlineElapsedMs, BooleanSupplier currentRequest, LongSupplier elapsedRealtimeMs) {
        this.currentRequest = Objects.requireNonNull(currentRequest); elapsed = Objects.requireNonNull(elapsedRealtimeMs);
        long remaining = deadlineElapsedMs - elapsed.getAsLong();
        if (remaining <= 0 || remaining > 120_000) throw new IllegalArgumentException("invalid_native_consent_deadline");
        deadline = deadlineElapsedMs;
    }
    public synchronized boolean current() {
        if (state == State.CLOSED) return false;
        boolean valid;
        try { valid = currentRequest.getAsBoolean() && (state == State.ALLOWED || elapsed.getAsLong() < deadline); }
        catch (RuntimeException error) { valid = false; }
        if (!valid) close();
        return valid;
    }
    public synchronized boolean attach(Object nativeRequest) {
        if (nativeRequest == null || !current() || state != State.WAITING_REQUEST) return false;
        identity = nativeRequest; state = State.WAITING_PROMPT; return true;
    }
    public synchronized boolean prompted(Object nativeRequest) {
        if (!same(nativeRequest) || state != State.WAITING_PROMPT) return false;
        state = State.PROMPTED; return true;
    }
    public synchronized boolean allowed(Object nativeRequest) {
        if (!same(nativeRequest) || state != State.PROMPTED) return false;
        state = State.ALLOWED; return true;
    }
    /** Observe the ORIGINAL OEM onAllow callback, including its existing-permission branch.
     * Only that callback may use this method; an SDK return or request registration is not consent. */
    public synchronized boolean nativeAllowed(Object nativeRequest) {
        if (!same(nativeRequest) || (state != State.WAITING_PROMPT && state != State.PROMPTED)) return false;
        state = State.ALLOWED; return true;
    }
    public synchronized boolean approved(Object nativeRequest) { return same(nativeRequest) && state == State.ALLOWED; }
    private boolean same(Object request) { return current() && identity != null && request == identity; }
    public synchronized State state() { return state; }
    @Override public synchronized void close() { state = State.CLOSED; identity = null; }
}
