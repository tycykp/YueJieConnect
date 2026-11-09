package io.github.yuejie.connect;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Installed as the real Cast request's ConsentListener. Native consent starts asynchronous media
 * assembly; neither an SDK return nor early Surface preparation can produce streaming success.
 * The shared control owner supplies Factory after verifying the original caller/request proof. */
public final class OplusScreenSessionLease implements OplusScreenRequestAdapter.ConsentListener, AutoCloseable {
    public enum State { WAITING_NATIVE_CONSENT, OPENING_MEDIA, ACTIVE, CLOSED }
    public interface Factory {
        /** Nonblocking. Only called from the actual matching OEM allow callback. Verify/renew
         * this request's local proof, grant ScreenVideo after consent and establish the approved
         * authenticated media path. Returned session must use actualDeviceInfo as its source owner.
         * Queueing this work is not permission or media success. */
        CompletionStage<ScreenCastNativeSession> openAfterNativeConsent(Object actualDeviceInfo, BooleanSupplier currentNativePermit);
        default void nativeSource(Object actualDeviceInfo,Object actualPadManager,String actualBusId) { }
        /** Fence this request and dispatch scoped native/proof/media cancellation without waiting. */
        void cancel();
        /** Fixed local failure, never user text or an untrusted OEM exception message. */
        void failed(String code);
    }
    private final Object device;
    private final ScreenCastSession.Scope scope;
    private final BooleanSupplier currentRequest;
    private final LongSupplier elapsed;
    private final Factory factory;
    private final long consentDeadline;
    private volatile State state = State.WAITING_NATIVE_CONSENT;
    private volatile long mediaDeadline;
    private int preparedDisplay = -1;
    private Object sourceManager;
    private String sourceBus;
    private volatile ScreenCastNativeSession session;
    private volatile OplusScreenSourceAdapter.Listener source;

    public OplusScreenSessionLease(Object actualDeviceInfo, ScreenCastSession.Scope scope, long consentDeadlineElapsedMs,
            BooleanSupplier currentAuthenticatedRequest, LongSupplier elapsedRealtimeMs, Factory factory) {
        device = Objects.requireNonNull(actualDeviceInfo); this.scope = Objects.requireNonNull(scope);
        currentRequest = Objects.requireNonNull(currentAuthenticatedRequest);
        elapsed = Objects.requireNonNull(elapsedRealtimeMs); this.factory = Objects.requireNonNull(factory);
        long now = elapsed.getAsLong();
        if (now < 0 || consentDeadlineElapsedMs <= now || consentDeadlineElapsedMs - now > 120_000)
            throw new IllegalArgumentException("invalid_native_consent_deadline");
        consentDeadline = consentDeadlineElapsedMs;
    }
    /** Use as Expectation.current before and after consent. It does not itself grant capture.
     * Its upstream gate binds the exact native request, current authenticated session and peer. */
    public boolean current() {
        if (state == State.CLOSED) return false;
        boolean live;
        try { live = currentRequest.getAsBoolean(); } catch (RuntimeException error) { live = false; }
        if (!live) { end("screen_native_request_revoked"); return false; }
        long now = elapsed.getAsLong();
        if (state == State.WAITING_NATIVE_CONSENT && now >= consentDeadline
                || state == State.OPENING_MEDIA && now >= mediaDeadline) {
            end(state == State.WAITING_NATIVE_CONSENT ? "screen_native_consent_timeout" : "screen_media_open_timeout"); return false;
        }
        return true;
    }
    @Override public void onNativeAllowed(Object actualDeviceInfo) {
        if (state == State.CLOSED) return;
        if (actualDeviceInfo != device) { end("screen_native_owner_mismatch"); return; }
        if (!current()) return;
        synchronized (this) {
            if (state != State.WAITING_NATIVE_CONSENT) return;
            mediaDeadline = elapsed.getAsLong() + 10_000; state = State.OPENING_MEDIA;
        }
        try {
            CompletionStage<ScreenCastNativeSession> opening = factory.openAfterNativeConsent(device,
                    () -> state != State.WAITING_NATIVE_CONSENT && current());
            if (opening == null) { end("screen_media_open_failed"); return; }
            opening.whenComplete(this::opened);
        } catch (RuntimeException error) { end("screen_media_open_failed"); }
    }
    private void opened(ScreenCastNativeSession actual, Throwable error) {
        if (!current() || state != State.OPENING_MEDIA) { if (actual != null) actual.close(); return; }
        if (error != null || actual == null) {
            if (actual != null) actual.close(); end("screen_media_open_failed"); return;
        }
        if (!scope.equals(actual.scope())) { actual.close(); end("screen_native_stream_mismatch"); return; }
        try {
            OplusScreenSourceAdapter.Listener actualSource = actual.sourceListener(device);
            int prepared;
            synchronized (this) {
                if (state == State.OPENING_MEDIA) {
                    source = actualSource; session = actual; state = State.ACTIVE; prepared = preparedDisplay;
                } else prepared = -2;
            }
            if (prepared == -2) actual.close();
            else if (prepared >= 0) actualSource.onPrepared(prepared);
        } catch (RuntimeException failure) { actual.close(); end("screen_native_owner_mismatch"); }
    }
    @Override public void onPrepared(int nativeDisplayId) {
        if (!current()) return;
        OplusScreenSourceAdapter.Listener actualSource;
        boolean mismatch;
        synchronized (this) {
            if (state == State.CLOSED) return;
            mismatch = state == State.WAITING_NATIVE_CONSENT || nativeDisplayId < 0
                    || preparedDisplay >= 0 && preparedDisplay != nativeDisplayId;
            if (!mismatch) preparedDisplay = nativeDisplayId;
            actualSource = source;
        }
        // The OEM may create its real display while media IPC is still opening. Keep only the
        // display identity, never pixels. Encoder offer=false requests fresh CONFIG+IDR later.
        if (mismatch) end("screen_native_preparation_mismatch");
        else if (actualSource != null) actualSource.onPrepared(nativeDisplayId);
    }
    @Override public boolean offer(ScreenCastPacket packet) {
        if (!current()) return false;
        OplusScreenSourceAdapter.Listener actual = source;
        return actual != null && actual.offer(packet);
    }
    @Override public void onNativeSource(Object actualDeviceInfo,Object actualPadManager,String actualBusId) {
        if(!current()) return;
        boolean mismatch;
        synchronized(this) {
            mismatch=state==State.WAITING_NATIVE_CONSENT || actualDeviceInfo!=device || actualPadManager==null
                    || actualBusId==null || actualBusId.isBlank() || actualBusId.length()>256
                    || sourceManager!=null && (sourceManager!=actualPadManager || !sourceBus.equals(actualBusId));
            if(!mismatch) { sourceManager=actualPadManager; sourceBus=actualBusId; }
        }
        if(mismatch) { end("screen_native_source_mismatch"); return; }
        try { factory.nativeSource(actualDeviceInfo,actualPadManager,actualBusId); }
        catch(RuntimeException error) { end("screen_native_source_mismatch"); }
    }
    @Override public void onStopped() { end("screen_native_stopped"); }
    @Override public void onFailure(String ignored) { end("screen_native_failed"); }
    public void tick() {
        if (current()) {
            ScreenCastNativeSession actual = session;
            if (actual != null) {
                actual.tick();
                if (actual.state() == ScreenCastSession.State.CLOSED) end("screen_stream_closed");
            }
        }
    }
    public State state() { return state; }
    private void end(String reason) {
        ScreenCastNativeSession previous;
        synchronized (this) {
            if (state == State.CLOSED) return;
            state = State.CLOSED; previous = session; session = null; source = null;
        }
        if (previous != null) previous.close();
        try { factory.cancel(); } catch (RuntimeException ignored) { }
        try { factory.failed(reason); } catch (RuntimeException ignored) { }
    }
    @Override public void close() { end("screen_native_request_closed"); }
}
