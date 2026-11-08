package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.Objects;

/** Native VirtualCamera2 JPEG stream consumer; BufferQueue enqueue is explicitly not rendered success. */
final class XiaomiCameraStillConsumer implements AutoCloseable, IBinder.DeathRecipient {
    static final String STREAM = "com.xiaomi.vtcamera.injection.VirtualCamera2$StreamConfiguration";
    static final String SURFACE_UTILS = "com.xiaomi.camera.companion.util.SurfaceUtils";
    interface Listener {
        void queued(CameraShareStillRequest request, long nativeSurfaceId, long sensorTimestampNs);
        void consumed(CameraShareStillReceipt.Evidence evidence);
        void closed(CameraShareStillRequest request, String reason);
    }

    private final CameraShareStillRequest request;
    private final ClassLoader nativeLoader;
    private final XiaomiCameraNativeRequests.Still nativeBinding;
    private final CameraShareStillSource.Permit permit;
    private final Listener listener;
    private final IBinder consumer;
    private final Surface surface;
    private final long surfaceId;
    private final Method footerSize, setDimensions, produceFrame, getSurfaceId;
    private final HandlerThread thread = new HandlerThread("QjCameraStillConsumer");
    private final Handler worker;
    private final long started = SystemClock.elapsedRealtime();
    private volatile boolean closed, revoked;
    private boolean submitted;
    private ImageReader originalReader;
    private CameraShareStillReceipt receipt;

    /** Call after OEM configureOutputStreams connected this usage=5 Surface and authorized the client. */
    XiaomiCameraStillConsumer(ClassLoader nativeLoader, CameraShareStillRequest request,
                             XiaomiCameraNativeRequests.Still nativeBinding,
                             CameraShareStillSource.Permit permit, Listener listener)
            throws ReflectiveOperationException, RemoteException {
        this.request = Objects.requireNonNull(request);
        this.nativeLoader = Objects.requireNonNull(nativeLoader);
        this.nativeBinding = Objects.requireNonNull(nativeBinding);
        this.permit = Objects.requireNonNull(permit);
        this.listener = Objects.requireNonNull(listener);
        if (!XiaomiCameraNativeRequests.current(nativeLoader, nativeBinding, request))
            throw new IllegalArgumentException("native_camera_configuration_expired");
        Object nativeStream = nativeBinding.nativeStream();
        Class<?> stream = Class.forName(STREAM, false, nativeLoader);
        if (!stream.isInstance(nativeStream)) throw new IllegalArgumentException("invalid_native_camera_stream");
        if ((int) stream.getMethod("usage").invoke(nativeStream) != 5
                || (int) stream.getMethod("format").invoke(nativeStream) != 256
                || (int) stream.getMethod("width").invoke(nativeStream) != request.width()
                || (int) stream.getMethod("height").invoke(nativeStream) != request.height())
            throw new IllegalArgumentException("native_camera_jpeg_stream_mismatch");
        surface = (Surface) stream.getMethod("surface").invoke(nativeStream);
        surfaceId = (long) stream.getMethod("getId").invoke(nativeStream);
        if (surface == null || !surface.isValid() || surfaceId == 0)
            throw new IllegalArgumentException("native_camera_surface_missing");
        Class<?> utils = Class.forName(SURFACE_UTILS, false, nativeLoader);
        footerSize = utils.getMethod("nativeGetJpegFooterSize");
        setDimensions = utils.getMethod("setSurfaceDimens", Surface.class, int.class, int.class);
        produceFrame = utils.getMethod("produceFrame", Surface.class, byte[].class, int.class, int.class, int.class);
        getSurfaceId = utils.getMethod("getSurfaceId", Surface.class);
        consumer = nativeBinding.consumer();
        if (!consumer.isBinderAlive()) throw new RemoteException("native_camera_consumer_dead");
        if (!permit.valid(request)) throw new SecurityException("camera_still_consumer_not_authorized");
        thread.start();
        worker = new Handler(thread.getLooper());
        try {
            consumer.linkToDeath(this, 0);
            synchronized (this) { if (closed || revoked || !consumer.isBinderAlive()) throw new RemoteException("native_camera_consumer_dead"); }
            worker.postDelayed(this::watch, 250);
        } catch (RemoteException | RuntimeException e) { close(); throw e; }
    }

    /** Must be called on a worker with the exact authenticated response identity. One response only. */
    synchronized boolean deliver(CameraShareStillRequest response, long sensorTimestampNs, byte[] jpeg)
            throws ReflectiveOperationException {
        if (closed || revoked || submitted || !request.equals(response) || sensorTimestampNs <= 0) return false;
        if (!authorized()) { end("camera_still_consumer_revoked"); return false; }
        if (SystemClock.elapsedRealtime() - started >= 10_000) { end("camera_still_timeout"); return false; }
        if (jpeg == null || jpeg.length > CameraShareStillRequest.MAX_JPEG_BYTES) {
            end("invalid_camera_jpeg"); throw new IllegalArgumentException("invalid_camera_jpeg");
        }
        byte[] snapshot = jpeg.clone(); // Caller cannot change authenticated bytes between validation and JNI copy.
        try {
            CameraShareJpeg.validate(snapshot, request);
            if ((long) getSurfaceId.invoke(null, surface) != surfaceId) {
                end("native_camera_surface_replaced"); return false;
            }
            int footer = (int) footerSize.invoke(null);
            if (footer < 1 || footer > 4096) throw new IllegalArgumentException("invalid_native_jpeg_footer");
            int length = (snapshot.length + footer + 3) & ~3;
            receipt = new CameraShareStillReceipt(request, surfaceId, sensorTimestampNs, snapshot);
            if (!authorized()) { end("camera_still_consumer_revoked"); return false; }
            if (SystemClock.elapsedRealtime() - started >= 10_000) { end("camera_still_timeout"); return false; }
            submitted = true; // A native write failure is terminal; never replay the exposure into this surface.
            long enqueueStartedNs = System.nanoTime();
            setDimensions.invoke(null, surface, length, 1);
            produceFrame.invoke(null, surface, snapshot, length, 1, 33);
            receipt.queued(enqueueStartedNs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            end("native_camera_jpeg_write_failed");
            throw e;
        } catch (LinkageError e) {
            end("native_camera_dependency_unavailable");
            throw e;
        } finally { java.util.Arrays.fill(snapshot, (byte) 0); }
        // The sampled OEM helper catches errors internally when wrapped by ImageWriter.write().
        // Invoke SurfaceUtils directly so Java/native exceptions remain failures. A normal return
        // proves enqueue only. No CameraShareLease.rendered/STREAMING or final native success here.
        if (!authorized()) { end("camera_still_consumer_revoked"); return false; }
        try { listener.queued(request, surfaceId, sensorTimestampNs); }
        catch (RuntimeException e) { end("camera_still_consumer_callback_failed"); throw e; }
        // Keep the death link, permit watchdog and receipt until real acquisition or terminal close.
        // The sampled HAL callback is a shutter notification, not a JPEG-consumption result.
        return true;
    }

    /** Bind the OEM's existing reader in its owning process; never manufacture/replace a reader. */
    synchronized void bindOriginalReader(ImageReader reader) throws ReflectiveOperationException {
        if (closed || revoked || submitted || originalReader != null)
            throw new IllegalStateException("native_camera_reader_already_bound_or_closed");
        Objects.requireNonNull(reader);
        if (!authorized()) throw new SecurityException("camera_still_consumer_not_authorized");
        if (reader.getImageFormat() != ImageFormat.JPEG || reader.getWidth() != request.width()
                || reader.getHeight() != request.height() || !reader.getSurface().isValid()
                || (long) getSurfaceId.invoke(null, reader.getSurface()) != surfaceId)
            throw new IllegalArgumentException("native_camera_reader_surface_mismatch");
        originalReader = reader;
    }

    /**
     * Call after the original acquireNextImage/acquireLatestImage returned this image, before OEM
     * closes it. Does not acquire, close, retain, decode or save the image, or change its buffer position.
     * An ImageReader in another process needs a separately authenticated native observation path;
     * a remote Surface/Binder or shutter callback cannot supply this evidence.
     */
    synchronized boolean acquired(ImageReader reader, Image image) {
        if (closed || revoked || receipt == null || reader != originalReader || reader == null || image == null) return false;
        if (!authorized()) { end("camera_still_consumer_revoked"); return false; }
        if (SystemClock.elapsedRealtime() - started >= 10_000) { end("camera_still_timeout"); return false; }
        try {
            if ((long) getSurfaceId.invoke(null, reader.getSurface()) != surfaceId) {
                end("native_camera_surface_replaced"); return false;
            }
            if (image.getFormat() != ImageFormat.JPEG || image.getPlanes().length != 1) return false;
            CameraShareStillReceipt.Evidence evidence = receipt.acquired(request, surfaceId,
                    image.getTimestamp(), System.nanoTime(), image.getPlanes()[0].getBuffer());
            if (evidence == null) return false;
            // Check again after hashing a bounded (up to 16 MiB) original plane.
            if (!authorized()) { end("camera_still_consumer_revoked"); return false; }
            if (SystemClock.elapsedRealtime() - started >= 10_000) { end("camera_still_timeout"); return false; }
            try { listener.consumed(evidence); }
            catch (RuntimeException e) { end("camera_still_consumer_callback_failed"); return false; }
            end("camera_still_consumed");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            end("native_camera_acquisition_observation_failed"); return false;
        }
    }

    private boolean authorized() {
        try { return !closed && !revoked && consumer.isBinderAlive() && surface.isValid()
                && XiaomiCameraNativeRequests.current(nativeLoader, nativeBinding, request) && permit.valid(request); }
        catch (RuntimeException e) { return false; }
    }

    private synchronized void watch() {
        if (closed) return;
        if (!authorized()) end("camera_still_consumer_revoked");
        else if (SystemClock.elapsedRealtime() - started >= 10_000) end("camera_still_timeout");
        else worker.postDelayed(this::watch, 250);
    }

    void sessionInvalidated(String sessionId) {
        if (request.sessionId().equals(sessionId)) revoke("session_invalidated");
    }

    boolean isClosed() { return closed || revoked; }

    boolean boundTo(XiaomiCameraNativeRequests.Still binding, CameraShareStillRequest request) {
        return this.request.equals(request) && binding != null
                && nativeBinding.nativeCamera() == binding.nativeCamera()
                && nativeBinding.nativeStream() == binding.nativeStream()
                && nativeBinding.nativeSurfaceId() == binding.nativeSurfaceId()
                && consumer.equals(binding.consumer());
    }

    private synchronized void end(String reason) {
        if (closed) return;
        closed = true; revoked = true;
        if (receipt != null) { receipt.close(); receipt = null; }
        originalReader = null; // Reader/Surface/Image ownership always remains with the original OEM client.
        try { consumer.unlinkToDeath(this, 0); } catch (java.util.NoSuchElementException ignored) { }
        thread.quitSafely();
        // OEM VirtualCamera2 owns connect/disconnect/release of this Surface.
        try { listener.closed(request, reason); } catch (RuntimeException ignored) { }
    }

    private void revoke(String reason) {
        revoked = true; // Immediately blocks results, even while a worker is inside OEM produceFrame.
        // Native close/reconfigure/reader close must proceed and unblock a native producer. Waiting
        // here for deliver's monitor could deadlock the Binder thread against its own BufferQueue.
        worker.post(() -> end(reason));
    }

    @Override public void binderDied() { revoke("native_camera_consumer_dead"); }
    @Override public void close() { revoke("camera_still_cancelled"); }
}
