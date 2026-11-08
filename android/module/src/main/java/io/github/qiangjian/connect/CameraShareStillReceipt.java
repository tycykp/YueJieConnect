package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Correlates a queued JPEG with bytes actually acquired by the original native reader. */
final class CameraShareStillReceipt implements AutoCloseable {
    record Evidence(CameraShareStillRequest request, long nativeSurfaceId, long sourceTimestampNs,
                    long acquiredTimestampNs, int jpegBytes) { }

    private final CameraShareStillRequest request;
    private final long surfaceId, sourceTimestampNs;
    private final int length;
    private final byte[] digest;
    private long enqueueStartedNs;
    private boolean queued, closed;

    CameraShareStillReceipt(CameraShareStillRequest request, long nativeSurfaceId,
                           long sourceTimestampNs, byte[] authenticatedJpeg) {
        this.request = Objects.requireNonNull(request);
        if (nativeSurfaceId == 0 || sourceTimestampNs <= 0)
            throw new IllegalArgumentException("invalid_camera_still_receipt");
        CameraShareJpeg.validate(authenticatedJpeg, request);
        surfaceId = nativeSurfaceId;
        this.sourceTimestampNs = sourceTimestampNs;
        length = authenticatedJpeg.length;
        digest = sha256().digest(authenticatedJpeg);
    }

    /** Native enqueue is a prerequisite, never a consumption receipt. */
    synchronized void queued(long enqueueStartedNs) {
        if (!closed && !queued) {
            if (enqueueStartedNs <= 0) throw new IllegalArgumentException("invalid_camera_enqueue_timestamp");
            this.enqueueStartedNs = enqueueStartedNs;
            queued = true;
        }
    }

    /** The adapter must first verify the original reader/surface and live business permission. */
    synchronized Evidence acquired(CameraShareStillRequest response, long nativeSurfaceId,
                                   long acquiredTimestampNs, long observedAtNs, ByteBuffer originalPlane) {
        if (closed || !queued || !request.equals(response) || nativeSurfaceId != surfaceId
                || acquiredTimestampNs < enqueueStartedNs || acquiredTimestampNs > observedAtNs
                || originalPlane == null || originalPlane.remaining() != length)
            return null;
        // Equal JPEG bytes may occur in successive exposures. A buffered earlier image must not
        // complete this one. Require a local monotonic producer timestamp inside this enqueue's
        // observation interval; an unproven/other clock domain fails closed, with no offset guessing.
        // A duplicate preserves the OEM's position/limit, including direct, sliced and read-only planes.
        MessageDigest hash = sha256();
        hash.update(originalPlane.duplicate());
        byte[] actual = hash.digest();
        boolean same = MessageDigest.isEqual(digest, actual);
        Arrays.fill(actual, (byte) 0);
        if (!same) return null;
        Evidence evidence = new Evidence(request, surfaceId, sourceTimestampNs, acquiredTimestampNs, length);
        close(); // Exactly one native acquisition may complete this request.
        return evidence;
    }

    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(digest, (byte) 0);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
