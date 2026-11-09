package io.github.yuejie.connect;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** JPEG output of the SAME actual Camera2 device/session as preview. Calls stay on its Camera2 worker. */
final class CameraShareStillSession {
    record Size(int width, int height) {
        Size {
            if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 32_000_000)
                throw new IllegalArgumentException("invalid_camera_still_size");
        }
    }
    record Frame(CameraShareStillRequest request, long sensorTimestampNs, byte[] jpeg) {
        @Override public String toString() { return "CameraShareStillSession.Frame[private JPEG]"; }
    }
    private static final class Shot {
        final CameraShareStillRequest request;
        final CameraShareStillSource.Permit permit;
        final CompletableFuture<Frame> future;
        final long started = SystemClock.elapsedRealtime();
        long captureTimestamp = -1, imageTimestamp = -1;
        byte[] jpeg;
        boolean cancelled;
        Shot(CameraShareStillRequest request, CameraShareStillSource.Permit permit, CompletableFuture<Frame> future) {
            this.request = request; this.permit = permit; this.future = future;
        }
    }
    private final CameraShareLease lease;
    private final String peerId, cameraId;
    private final Size size;
    private final Handler worker;
    private final BooleanSupplier current;
    private final Consumer<String> fatal;
    private final CameraShareStillRequest.History history;
    private final ImageReader reader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Shot active;
    private boolean revoked, released;

    CameraShareStillSession(CameraShareLease lease, String peerId, String cameraId, Size size,
                           CameraCharacteristics metadata, Handler worker, BooleanSupplier current,
                           Consumer<String> fatal, CameraShareStillRequest.History history) {
        this.lease = lease; this.peerId = peerId; this.cameraId = cameraId; this.size = size;
        this.worker = worker; this.current = current; this.fatal = fatal; this.history = history;
        StreamConfigurationMap map = metadata.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || !Arrays.asList(sizes).contains(new android.util.Size(size.width(), size.height())))
            throw new IllegalArgumentException("camera_still_size_unavailable");
        reader = ImageReader.newInstance(size.width(), size.height(), ImageFormat.JPEG, 2);
        try { reader.setOnImageAvailableListener(this::image, worker); }
        catch (RuntimeException failed) { reader.close(); throw failed; }
    }
    Surface surface() { return reader.getSurface(); }
    void configured(CameraDevice camera, CameraCaptureSession session) { this.camera = camera; this.session = session; }

    void capture(CameraShareStillRequest request, CameraShareStillSource.Permit permit, CompletableFuture<Frame> result) {
        Objects.requireNonNull(request); Objects.requireNonNull(permit);
        if (revoked || released || !current.getAsBoolean() || camera == null || session == null) {
            result.completeExceptionally(new IllegalStateException("camera_still_session_unavailable")); return;
        }
        if (active != null) { result.completeExceptionally(new IllegalStateException("camera_busy")); return; }
        if (!lease.matches(request.sessionId(), Long.toString(request.streamId())) || !peerId.equals(request.peerId())
                || !cameraId.equals(request.cameraId()) || request.width() != size.width() || request.height() != size.height()) {
            result.completeExceptionally(new SecurityException("camera_still_scope_mismatch")); return;
        }
        try {
            if (!permit.valid(request)) throw new SecurityException("camera_still_consent_required");
            if (!history.claim(request)) throw new IllegalStateException("camera_still_request_replayed");
            if (result.isCancelled()) return;
            Shot shot = new Shot(request, permit, result); active = shot;
            CaptureRequest.Builder capture = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            capture.addTarget(reader.getSurface());
            capture.set(CaptureRequest.JPEG_ORIENTATION, request.orientation()); capture.set(CaptureRequest.JPEG_QUALITY, (byte)95);
            session.capture(capture.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult metadata) {
                    if (active != shot || revoked || released) return;
                    Long stamp = metadata.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (stamp == null || stamp <= 0) { failFatal(shot, "camera_still_timestamp_missing"); return; }
                    shot.captureTimestamp = stamp; ready(shot);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    failFatal(shot, "camera_still_capture_failed");
                }
            }, worker);
            watch(shot);
        } catch (CameraAccessException | RuntimeException failure) {
            if (active != null) failFatal(active, "camera_still_capture_failed");
            else result.completeExceptionally(failure);
        }
    }

    private boolean permitted(Shot shot) {
        try { return !revoked && !released && current.getAsBoolean() && shot.permit.valid(shot.request); }
        catch (RuntimeException failure) { return false; }
    }
    private void image(ImageReader source) {
        Shot shot = active;
        try (Image image = source.acquireNextImage()) {
            if (image == null || shot == null || revoked || released) return;
            if (shot.jpeg != null || image.getFormat() != ImageFormat.JPEG || image.getPlanes().length != 1)
                throw new IllegalArgumentException("invalid_camera_jpeg");
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            if (buffer.remaining() < 12 || buffer.remaining() > CameraShareStillRequest.MAX_JPEG_BYTES)
                throw new IllegalArgumentException("invalid_camera_jpeg_length");
            shot.jpeg = new byte[buffer.remaining()]; buffer.get(shot.jpeg);
            shot.imageTimestamp = image.getTimestamp(); CameraShareJpeg.validate(shot.jpeg, shot.request);
        } catch (RuntimeException failure) {
            if (shot != null) failFatal(shot, "camera_still_image_failed"); return;
        }
        // The actual acquired Image is closed before ownership of the in-memory JPEG transfers.
        if (shot != null) ready(shot);
    }
    private void ready(Shot shot) {
        if (active != shot || shot.jpeg == null || shot.captureTimestamp < 0) return;
        if (shot.imageTimestamp != shot.captureTimestamp) { failFatal(shot, "camera_still_timestamp_mismatch"); return; }
        if (SystemClock.elapsedRealtime() - shot.started >= 10_000) { failFatal(shot, "camera_still_timeout"); return; }
        byte[] jpeg = shot.jpeg; shot.jpeg = null; active = null;
        boolean transferred = !shot.cancelled && !shot.future.isDone() && permitted(shot)
                && shot.future.complete(new Frame(shot.request, shot.captureTimestamp, jpeg));
        if (!transferred) {
            Arrays.fill(jpeg, (byte)0);
            shot.future.completeExceptionally(new SecurityException("camera_still_consent_revoked"));
        }
    }
    private void watch(Shot shot) {
        worker.postDelayed(() -> {
            if (active != shot || released || revoked) return;
            if (!permitted(shot) || shot.future.isCancelled()) {
                shot.cancelled = true;
                shot.future.completeExceptionally(new SecurityException("camera_still_consent_revoked"));
                // Keep the exposure slot until both actual callbacks drain, so its late image cannot
                // be confused with a new request. A stalled drain closes the whole owning device.
            }
            if (SystemClock.elapsedRealtime() - shot.started >= 10_000) failFatal(shot, "camera_still_timeout");
            else watch(shot);
        },250);
    }
    private void failFatal(Shot shot, String reason) {
        if (active != shot) return;
        shot.cancelled = true; shot.future.completeExceptionally(new IllegalStateException(reason));
        revoke(); fatal.accept(reason); // Owner closes the real CameraDevice before releasing reader.
    }
    void revoke() {
        revoked = true;
        if (active != null) {
            active.cancelled = true;
            active.future.completeExceptionally(new IllegalStateException("camera_still_session_closed"));
            if (active.jpeg != null) { Arrays.fill(active.jpeg,(byte)0); active.jpeg=null; }
        }
    }
    /** ONLY after actual CameraDevice.onClosed, or before openCamera ever acquired a device. */
    void releaseAfterDeviceClosed() {
        if (released) return;
        revoke(); released = true; reader.close(); session = null; camera = null; active = null;
    }
}
