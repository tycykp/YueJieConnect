package io.github.qiangjian.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Real Camera2 -> encoder Surface source. No screen projection, images on disk, or permission grants. */
final class CameraShareSource implements AutoCloseable {
    interface Permit {
        // Must include authenticated current session, per-camera user consent and foreground eligibility.
        boolean valid(String sessionId, String streamId, String cameraId);
    }
    interface Listener {
        void captured(CameraShareLease lease, long sensorTimestampUs);
        void closed(CameraShareLease lease, String reason);
        default void availabilityChanged(String cameraId, boolean available) { }
    }

    private final Context context;
    private final CameraManager manager;
    private final Permit permit;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("QjCameraSource");
    private final Handler worker;
    private final ConcurrentHashMap<String, Boolean> availability = new ConcurrentHashMap<>();
    private final CameraManager.AvailabilityCallback availabilityCallback = new CameraManager.AvailabilityCallback() {
        @Override public void onCameraAvailable(String cameraId) { available(cameraId, true); }
        @Override public void onCameraUnavailable(String cameraId) { available(cameraId, false); }
        private void available(String id, boolean value) {
            synchronized (CameraShareSource.this) { if (disposed) return; }
            availability.put(id, value);
            try { listener.availabilityChanged(id, value); } catch (RuntimeException ignored) { }
        }
    };
    private CameraShareLease active;
    private CameraDevice device;
    private CameraCaptureSession capture;
    private boolean disposed;
    private long lastCaptureAt;
    // Keep the callback looper alive until delayed onOpened/onClosed has released every device.
    private int pendingDevices;
    private final List<CameraShareLease> closing = new ArrayList<>();
    private final Map<CameraShareLease, CameraShareStillSession> stillSessions = new IdentityHashMap<>();
    private final CameraShareStillRequest.History stillHistory = new CameraShareStillRequest.History();

    CameraShareSource(Context context, Permit permit, Listener listener) {
        Context app = Objects.requireNonNull(context).getApplicationContext();
        this.context = app == null ? context : app;
        manager = Objects.requireNonNull(this.context.getSystemService(CameraManager.class));
        this.permit = Objects.requireNonNull(permit);
        this.listener = Objects.requireNonNull(listener);
        thread.start();
        worker = new Handler(thread.getLooper());
        try { manager.registerAvailabilityCallback(availabilityCallback, worker); }
        catch (RuntimeException error) { thread.quitSafely(); throw error; }
    }

    /** Null until CameraManager has delivered an initial availability callback. */
    Boolean available(String cameraId) { return availability.get(cameraId); }

    List<CameraShareConfig.Camera> cameras() throws CameraAccessException {
        requirePermission();
        List<CameraShareConfig.Camera> result = new ArrayList<>();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            Integer facing = info.get(CameraCharacteristics.LENS_FACING);
            // Phone/pad sources are their physical front/back cameras. Never recapture an OEM
            // injected external camera (HyperOS IDs 2/3 also expose incomplete stream metadata).
            if (facing == null || (facing != CameraCharacteristics.LENS_FACING_FRONT
                    && facing != CameraCharacteristics.LENS_FACING_BACK)) continue;
            StreamConfigurationMap streams = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (streams == null || ranges == null || orientation == null) continue;
            Size[] sizes = streams.getOutputSizes(MediaCodec.class);
            if (sizes == null) continue;
            List<CameraShareConfig.Mode> modes = new ArrayList<>();
            for (Size size : sizes) {
                int width = size.getWidth(), height = size.getHeight();
                if (width < 160 || height < 120 || width > 1920 || height > 1920
                        || (long) width * height > 1920L * 1080 || ((width | height) & 1) != 0) continue;
                long minDuration = streams.getOutputMinFrameDuration(MediaCodec.class, size);
                for (Range<Integer> range : ranges) {
                    int min = range.getLower(), max = range.getUpper();
                    if (min < 1 || max > 30 || (minDuration > 0 && minDuration > (1_000_000_000L + max - 1) / max)) continue;
                    CameraShareConfig.Mode mode = new CameraShareConfig.Mode(width, height, min, max);
                    if (!modes.contains(mode)) modes.add(mode);
                }
            }
            if (!modes.isEmpty()) result.add(new CameraShareConfig.Camera(id, facing, orientation, modes));
        }
        return List.copyOf(result);
    }

    /** Caller owns output and must configure it to config dimensions. Busy never preempts another user. */
    synchronized CameraShareLease start(String sessionId, String streamId, CameraShareConfig config, Surface output) {
        return startWithStill(sessionId, null, streamId, config, output, null);
    }

    synchronized CameraShareLease startWithStill(String sessionId, String peerId, String streamId,
            CameraShareConfig config, Surface output, CameraShareStillSession.Size stillSize) {
        if (disposed) throw new IllegalStateException("camera_source_closed");
        if (active != null) throw new IllegalStateException("camera_busy");
        Objects.requireNonNull(config);
        if (output == null || !output.isValid()) throw new IllegalArgumentException("camera_surface_unavailable");
        requirePermission();
        if (!permit.valid(sessionId, streamId, config.cameraId)) throw new SecurityException("camera_consent_required");
        Boolean available = available(config.cameraId);
        if (available == null) throw new IllegalStateException("camera_availability_pending");
        if (!available) throw new IllegalStateException("camera_busy");
        if (stillSize != null) new CameraShareLease(sessionId, peerId);
        CameraShareLease lease = new CameraShareLease(sessionId, streamId);
        active = lease;
        worker.post(() -> open(lease, peerId, config, output, stillSize));
        return lease;
    }

    CompletableFuture<CameraShareStillSession.Frame> captureStill(CameraShareStillRequest request, CameraShareStillSource.Permit permit) {
        CompletableFuture<CameraShareStillSession.Frame> result = new CompletableFuture<>();
        CameraShareLease lease;
        synchronized (this) { lease = active; }
        if (lease == null || !worker.post(() -> {
            CameraShareStillSession still = stillSessions.get(lease);
            if (!current(lease) || still == null) result.completeExceptionally(new IllegalStateException("camera_still_session_unavailable"));
            else still.capture(request, permit, result);
        })) result.completeExceptionally(new IllegalStateException("camera_still_session_unavailable"));
        return result;
    }

    private void requirePermission() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("camera_permission_denied");
    }

    private synchronized boolean current(CameraShareLease lease) {
        return !disposed && active == lease && lease.state() != CameraShareLease.State.CLOSED;
    }

    private boolean permitted(CameraShareLease lease, CameraShareConfig config) {
        if (!current(lease)) return false;
        try {
            requirePermission();
            if (permit.valid(lease.sessionId, lease.streamId, config.cameraId)) return true;
        } catch (RuntimeException ignored) { }
        finish(lease, "camera_consent_revoked");
        return false;
    }

    private void open(CameraShareLease lease, String peerId, CameraShareConfig config, Surface output,
                      CameraShareStillSession.Size stillSize) {
        if (!permitted(lease, config)) return;
        try {
            CameraShareConfig.Mode mode = config.negotiate(cameras());
            List<Surface> outputs = new ArrayList<>(); outputs.add(output);
            CameraShareStillSession still = null;
            if (stillSize != null) {
                still = new CameraShareStillSession(lease, peerId, config.cameraId, stillSize,
                        manager.getCameraCharacteristics(config.cameraId), worker, () -> permitted(lease, config),
                        reason -> finish(lease, reason), stillHistory);
                stillSessions.put(lease, still); outputs.add(still.surface());
            }
            final CameraShareStillSession stillOutput = still;
            lastCaptureAt = SystemClock.elapsedRealtime();
            watchdog(lease, config);
            pendingDevices++;
            try {
                openAuthorizedCamera(config.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!permitted(lease, config)) { camera.close(); return; }
                    device = camera;
                    try {
                        camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                if (!permitted(lease, config)) { session.close(); return; }
                                capture = session;
                                if (stillOutput != null) stillOutput.configured(camera, session);
                                try {
                                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                    request.addTarget(output);
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mode.minFps(), mode.maxFps()));
                                    session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureCompleted(CameraCaptureSession ignored, CaptureRequest req, TotalCaptureResult result) {
                                            if (!permitted(lease, config)) return;
                                            Long time = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                            if (time != null && lease.captured(time / 1000)) {
                                                lastCaptureAt = SystemClock.elapsedRealtime();
                                                try { listener.captured(lease, time / 1000); }
                                                catch (RuntimeException error) { finish(lease, "camera_consumer_failed"); }
                                            }
                                        }
                                        @Override public void onCaptureFailed(CameraCaptureSession ignored, CaptureRequest req, CaptureFailure failure) {
                                            if (current(lease)) finish(lease, "camera_capture_failed");
                                        }
                                    }, worker);
                                } catch (CameraAccessException error) { finish(lease, accessError(error)); }
                                catch (SecurityException error) { finish(lease, "camera_permission_denied"); }
                                catch (RuntimeException error) { finish(lease, "camera_configuration_failed"); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                session.close();
                                finish(lease, "camera_configuration_failed");
                            }
                        }, worker);
                    } catch (CameraAccessException error) { finish(lease, accessError(error)); }
                    catch (RuntimeException error) { finish(lease, "camera_configuration_failed"); }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    finish(lease, "camera_disconnected");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    finish(lease, switch (error) {
                        case ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "camera_busy";
                        case ERROR_CAMERA_DISABLED -> "camera_disabled";
                        default -> "camera_device_error";
                    });
                }
                @Override public void onClosed(CameraDevice camera) {
                    pendingDevices--;
                    notifyClosed();
                    maybeQuit();
                }
            }); }
            catch (CameraAccessException | RuntimeException error) {
                pendingDevices--;
                maybeQuit();
                throw error;
            }
        } catch (CameraAccessException error) { finish(lease, accessError(error)); }
        catch (SecurityException error) { finish(lease, "camera_permission_denied"); }
        catch (IllegalArgumentException error) { finish(lease, "camera_configuration_unavailable"); }
        catch (RuntimeException error) { finish(lease, "camera_open_failed"); }
    }

    // This code executes in an OEM process using its actual Context/manifest, not the module APK.
    // Lint cannot infer that manifest. The narrowly scoped annotation does not grant CAMERA:
    // recheck at the actual async call site; Camera2 still enforces AppOps/privacy/foreground access,
    // and open() maps any resulting SecurityException to camera_permission_denied and releases the lease.
    @SuppressLint("MissingPermission")
    private void openAuthorizedCamera(String cameraId, CameraDevice.StateCallback callback) throws CameraAccessException {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("camera_permission_denied");
        manager.openCamera(cameraId, callback, worker);
    }

    private void watchdog(CameraShareLease lease, CameraShareConfig config) {
        worker.postDelayed(() -> {
            if (!permitted(lease, config)) return;
            if (SystemClock.elapsedRealtime() - lastCaptureAt > 8_000) finish(lease, "camera_frame_timeout");
            else watchdog(lease, config);
        }, 500);
    }

    void stop(String sessionId, String streamId) {
        CameraShareLease lease;
        synchronized (this) { lease = active; }
        if (lease != null && lease.matches(sessionId, streamId)) cancel(lease, "camera_stopped");
    }

    void sessionInvalidated(String sessionId) {
        CameraShareLease lease;
        synchronized (this) { lease = active; }
        if (lease != null && lease.sessionId.equals(sessionId)) cancel(lease, "session_invalidated");
    }

    void abort(String sessionId, String streamId, String reason) {
        CameraShareLease lease;
        synchronized (this) { lease = active; }
        if (lease != null && lease.matches(sessionId, streamId)) cancel(lease, reason);
    }

    private void cancel(CameraShareLease lease, String reason) {
        lease.close(reason); // Invalidate before asynchronous callbacks can publish another result.
        worker.post(() -> finish(lease, reason));
    }

    private void finish(CameraShareLease lease, String reason) {
        synchronized (this) { if (active != lease) return; }
        lease.close(reason);
        CameraShareStillSession still = stillSessions.get(lease);
        if (still != null) still.revoke();
        if (capture != null) {
            try { capture.stopRepeating(); } catch (CameraAccessException | RuntimeException ignored) { }
            capture.close();
            capture = null;
        }
        if (device != null) { device.close(); device = null; }
        synchronized (this) { if (active == lease) active = null; }
        closing.add(lease);
        notifyClosed();
    }

    private void notifyClosed() {
        if (pendingDevices != 0) return;
        List<CameraShareLease> done = List.copyOf(closing);
        closing.clear();
        for (CameraShareLease lease : done) {
            CameraShareStillSession still = stillSessions.remove(lease);
            if (still != null) still.releaseAfterDeviceClosed();
            try { listener.closed(lease, lease.terminalReason()); } catch (RuntimeException ignored) { }
        }
    }

    private static String accessError(CameraAccessException error) {
        return switch (error.getReason()) {
            case CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> "camera_busy";
            case CameraAccessException.CAMERA_DISABLED -> "camera_disabled";
            case CameraAccessException.CAMERA_DISCONNECTED -> "camera_disconnected";
            default -> "camera_service_error";
        };
    }

    private synchronized void maybeQuit() {
        if (disposed && pendingDevices == 0) thread.quitSafely();
    }

    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        try { manager.unregisterAvailabilityCallback(availabilityCallback); } catch (RuntimeException ignored) { }
        availability.clear();
        CameraShareLease lease = active;
        if (lease != null) lease.close("camera_source_closed");
        worker.post(() -> {
            if (lease != null) finish(lease, "camera_source_closed");
            maybeQuit();
        });
    }
}
