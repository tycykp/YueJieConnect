package io.github.qiangjian.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Real single Camera2 JPEG exposure. No preview snapshots, implicit grants, files or final native ack. */
final class CameraShareStillSource implements AutoCloseable {
    interface Permit { boolean valid(CameraShareStillRequest request); }
    interface Listener {
        // Ownership of the in-memory JPEG transfers to the caller after CameraDevice.onClosed.
        void captured(CameraShareStillRequest request, long sensorTimestampNs, byte[] jpeg);
        void closed(CameraShareStillRequest request, String reason);
    }

    private final Context context;
    private final CameraManager manager;
    private final Permit permit;
    private final Listener listener;
    private final CameraShareStillRequest.History history = new CameraShareStillRequest.History();
    private final HandlerThread thread = new HandlerThread("QjCameraStill");
    private final Handler worker;
    private volatile Shot active;
    private volatile boolean disposed;

    private static final class Shot {
        final CameraShareStillRequest request;
        final long started = SystemClock.elapsedRealtime();
        volatile boolean cancelled;
        boolean opening, ending;
        CameraDevice device;
        CameraCaptureSession session;
        ImageReader reader;
        long captureTimestamp = -1, imageTimestamp = -1;
        byte[] jpeg;
        String reason;
        Shot(CameraShareStillRequest request) { this.request = request; }
    }

    CameraShareStillSource(Context context, Permit permit, Listener listener) {
        Context app = Objects.requireNonNull(context).getApplicationContext();
        this.context = app == null ? context : app;
        manager = Objects.requireNonNull(this.context.getSystemService(CameraManager.class));
        this.permit = Objects.requireNonNull(permit);
        this.listener = Objects.requireNonNull(listener);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    synchronized void capture(CameraShareStillRequest request) {
        Objects.requireNonNull(request);
        if (disposed) throw new IllegalStateException("camera_source_closed");
        if (active != null) throw new IllegalStateException("camera_busy");
        requirePermission();
        if (!permit.valid(request)) throw new SecurityException("camera_still_consent_required");
        if (!history.claim(request)) throw new IllegalStateException("camera_still_request_replayed");
        Shot shot = new Shot(request);
        active = shot;
        worker.post(() -> open(shot));
    }

    private void requirePermission() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("camera_permission_denied");
    }

    private boolean valid(Shot shot) {
        if (active != shot || shot.ending || shot.cancelled || disposed) return false;
        try { requirePermission(); if (permit.valid(shot.request)) return true; }
        catch (RuntimeException ignored) { }
        end(shot, "camera_still_consent_revoked");
        return false;
    }

    private void open(Shot shot) {
        if (!valid(shot)) { end(shot, "camera_still_cancelled"); return; }
        try {
            CameraCharacteristics info = manager.getCameraCharacteristics(shot.request.cameraId());
            Integer facing = info.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || (facing != CameraCharacteristics.LENS_FACING_FRONT
                    && facing != CameraCharacteristics.LENS_FACING_BACK))
                throw new IllegalArgumentException("camera_still_physical_source_required");
            StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            Size requested = new Size(shot.request.width(), shot.request.height());
            if (sizes == null || !Arrays.asList(sizes).contains(requested))
                throw new IllegalArgumentException("camera_still_size_unavailable");
            shot.reader = ImageReader.newInstance(requested.getWidth(), requested.getHeight(), ImageFormat.JPEG, 2);
            shot.reader.setOnImageAvailableListener(reader -> image(shot, reader), worker);
            shot.opening = true;
            try { openAuthorized(shot, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    shot.opening = false;
                    shot.device = camera;
                    if (!valid(shot)) { camera.close(); end(shot, "camera_still_cancelled"); return; }
                    try {
                        camera.createCaptureSession(List.of(shot.reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                if (!valid(shot)) { session.close(); end(shot, "camera_still_cancelled"); return; }
                                shot.session = session;
                                try {
                                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    request.addTarget(shot.reader.getSurface());
                                    request.set(CaptureRequest.JPEG_ORIENTATION, shot.request.orientation());
                                    request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                    session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                                            if (!valid(shot)) return;
                                            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                            if (timestamp == null || timestamp <= 0) { end(shot, "camera_still_timestamp_missing"); return; }
                                            shot.captureTimestamp = timestamp;
                                            ready(shot);
                                        }
                                        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                                            end(shot, "camera_still_capture_failed");
                                        }
                                    }, worker);
                                } catch (CameraAccessException e) { end(shot, accessError(e)); }
                                catch (SecurityException e) { end(shot, "camera_permission_denied"); }
                                catch (RuntimeException e) { end(shot, "camera_still_capture_failed"); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                session.close(); end(shot, "camera_still_configuration_failed");
                            }
                        }, worker);
                    } catch (CameraAccessException e) { end(shot, accessError(e)); }
                    catch (RuntimeException e) { end(shot, "camera_still_configuration_failed"); }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    shot.opening = false; shot.device = camera;
                    camera.close(); end(shot, "camera_disconnected");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    shot.opening = false; shot.device = camera;
                    camera.close();
                    end(shot, error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                            ? "camera_busy" : error == ERROR_CAMERA_DISABLED ? "camera_disabled" : "camera_device_error");
                }
                @Override public void onClosed(CameraDevice camera) {
                    shot.opening = false; shot.device = null;
                    if (!shot.ending) {
                        shot.ending = true;
                        shot.reason = "camera_device_closed";
                        if (shot.session != null) { shot.session.close(); shot.session = null; }
                    }
                    release(shot);
                }
            }); } catch (CameraAccessException | RuntimeException e) { shot.opening = false; throw e; }
            watch(shot);
        } catch (CameraAccessException e) { end(shot, accessError(e)); }
        catch (SecurityException e) { end(shot, "camera_permission_denied"); }
        catch (IllegalArgumentException e) { end(shot, "camera_still_configuration_unavailable"); }
        catch (RuntimeException e) { end(shot, "camera_still_open_failed"); }
    }

    // OEM-process Context/manifest; this narrow lint boundary grants no permission or foreground access.
    @SuppressLint("MissingPermission")
    private void openAuthorized(Shot shot, CameraDevice.StateCallback callback) throws CameraAccessException {
        requirePermission();
        if (!permit.valid(shot.request)) throw new SecurityException("camera_still_consent_required");
        manager.openCamera(shot.request.cameraId(), callback, worker);
    }

    private void image(Shot shot, ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null || !valid(shot)) return;
            if (shot.jpeg != null || image.getFormat() != ImageFormat.JPEG || image.getPlanes().length != 1)
                throw new IllegalArgumentException("invalid_camera_jpeg");
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            int length = buffer.remaining();
            if (length < 12 || length > CameraShareStillRequest.MAX_JPEG_BYTES)
                throw new IllegalArgumentException("camera_still_too_large");
            shot.jpeg = new byte[length];
            buffer.get(shot.jpeg);
            shot.imageTimestamp = image.getTimestamp();
            CameraShareJpeg.validate(shot.jpeg, shot.request);
            ready(shot);
        } catch (RuntimeException e) { end(shot, "camera_still_image_failed"); }
    }

    private void ready(Shot shot) {
        if (!valid(shot) || shot.jpeg == null || shot.captureTimestamp < 0) return;
        if (shot.imageTimestamp != shot.captureTimestamp) { end(shot, "camera_still_timestamp_mismatch"); return; }
        end(shot, "camera_still_captured");
    }

    private void watch(Shot shot) {
        worker.postDelayed(() -> {
            if (!valid(shot)) return;
            if (SystemClock.elapsedRealtime() - shot.started >= 10_000) end(shot, "camera_still_timeout");
            else watch(shot);
        }, 250);
    }

    private void end(Shot shot, String reason) {
        if (active != shot) return;
        if (!shot.ending) { shot.ending = true; shot.reason = reason; }
        if (shot.session != null) { shot.session.close(); shot.session = null; }
        if (shot.device != null) shot.device.close();
        else if (!shot.opening) release(shot);
    }

    private synchronized void release(Shot shot) {
        if (active != shot) return;
        if (shot.reader != null) { shot.reader.close(); shot.reader = null; }
        boolean success = !shot.cancelled && !disposed && "camera_still_captured".equals(shot.reason);
        try { success = success && permit.valid(shot.request); requirePermission(); }
        catch (RuntimeException e) { success = false; }
        if (active == shot) active = null;
        byte[] jpeg = shot.jpeg;
        shot.jpeg = null;
        try {
            if (success) listener.captured(shot.request, shot.captureTimestamp, jpeg);
        } catch (RuntimeException e) { shot.reason = "camera_still_consumer_failed"; success = false; }
        if (!success && jpeg != null) Arrays.fill(jpeg, (byte) 0);
        try { listener.closed(shot.request, success ? "camera_still_captured" :
                "camera_still_captured".equals(shot.reason) ? "camera_still_cancelled" : shot.reason); }
        catch (RuntimeException ignored) { }
        if (disposed) thread.quitSafely();
    }

    synchronized void cancel(CameraShareStillRequest request) {
        Shot shot = active;
        if (shot != null && shot.request.equals(request)) {
            shot.cancelled = true;
            worker.post(() -> end(shot, "camera_still_cancelled"));
        }
    }

    void sessionInvalidated(String sessionId) {
        Shot shot = active;
        if (shot != null && shot.request.sessionId().equals(sessionId)) cancel(shot.request);
    }

    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        Shot shot = active;
        if (shot == null) thread.quitSafely();
        else { shot.cancelled = true; worker.post(() -> end(shot, "camera_source_closed")); }
    }

    private static String accessError(CameraAccessException error) {
        return switch (error.getReason()) {
            case CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> "camera_busy";
            case CameraAccessException.CAMERA_DISABLED -> "camera_disabled";
            case CameraAccessException.CAMERA_DISCONNECTED -> "camera_disconnected";
            default -> "camera_service_error";
        };
    }
}
