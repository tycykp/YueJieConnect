package io.github.yuejie.connect;

import java.util.ArrayDeque;

/** Identity and completion accounting, independent of Android callback scheduling. */
final class CameraShareLease {
    enum State { OPENING, CAPTURING, STREAMING, CLOSED }
    final String sessionId, streamId;
    private State state = State.OPENING;
    private long lastCapturedUs = -1, lastRenderedUs = -1;
    private String terminalReason;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();

    CameraShareLease(String sessionId, String streamId) {
        this.sessionId = identity(sessionId);
        this.streamId = identity(streamId);
    }

    private static String identity(String id) {
        if (id == null || id.isBlank() || id.length() > 128)
            throw new IllegalArgumentException("invalid_camera_identity");
        return id;
    }

    synchronized boolean matches(String session, String stream) {
        return state != State.CLOSED && sessionId.equals(session) && streamId.equals(stream);
    }

    synchronized boolean captured(long timestampUs) {
        if (state == State.CLOSED || timestampUs < 0 || timestampUs <= lastCapturedUs) return false;
        lastCapturedUs = timestampUs;
        if (state == State.OPENING) state = State.CAPTURING;
        return true;
    }

    synchronized boolean sent(long timestampUs) {
        if (state == State.CLOSED || timestampUs < 0 || sent.contains(timestampUs)) return false;
        if (sent.size() == 64) sent.removeFirst();
        sent.addLast(timestampUs);
        return true;
    }

    synchronized void dropped(long timestampUs) { sent.remove(timestampUs); }

    // Call only for an authenticated peer's actual decoder-render callback, not send/enqueue success.
    synchronized boolean rendered(String session, String stream, long timestampUs) {
        if (!matches(session, stream) || state == State.OPENING || timestampUs < 0
                || timestampUs > lastCapturedUs || timestampUs <= lastRenderedUs || !sent.remove(timestampUs)) return false;
        lastRenderedUs = timestampUs;
        state = State.STREAMING;
        return true;
    }

    synchronized boolean close(String reason) {
        if (state == State.CLOSED) return false;
        terminalReason = reason;
        sent.clear();
        state = State.CLOSED;
        return true;
    }

    synchronized State state() { return state; }
    synchronized String terminalReason() { return terminalReason; }
}
