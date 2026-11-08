package io.github.qiangjian.connect;

import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Resolves the actual HAL startCapture targets without manufacturing output streams or client identity. */
final class XiaomiCameraNativeRequests {
    static final String CAMERA = "com.xiaomi.vtcamera.injection.VirtualCamera2";
    interface NativePermit {
        // Retain OEM cloud/client checks and current selected-camera business consent on every request.
        boolean valid(Object nativeCamera, int ownerUid, String ownerPackage,
                      XiaomiCameraHardwareBinding.Selection selection, CameraShareStillRequest request);
    }
    record Still(Object nativeCamera, Object nativeStream, long nativeSurfaceId, IBinder consumer,
                 int ownerUid, String ownerPackage) { }

    /** Recheck this exact configuration generation, including native close/reconfigure, on each use. */
    static boolean current(ClassLoader loader, Still binding, CameraShareStillRequest request) {
        if (binding == null || request == null) return false;
        try {
            Class<?> camera = Class.forName(CAMERA, false, loader);
            Class<?> stream = Class.forName(XiaomiCameraStillConsumer.STREAM, false, loader);
            if (!camera.isInstance(binding.nativeCamera()) || !stream.isInstance(binding.nativeStream())
                    || !binding.consumer().isBinderAlive()
                    || camera.getField("mClientUid").getInt(binding.nativeCamera()) != binding.ownerUid()
                    || !binding.ownerPackage().equals(camera.getField("mPackageName").get(binding.nativeCamera()))) return false;
            Object callback = camera.getField("mVirtualCameraCallback").get(binding.nativeCamera());
            if (!(callback instanceof IInterface remote) || !binding.consumer().equals(remote.asBinder())) return false;
            Object list = camera.getMethod("getRequestedOutputStreams").invoke(binding.nativeCamera());
            if (!(list instanceof List<?> outputs) || outputs.size() > 16) return false;
            int matches = 0;
            for (Object output : outputs) if (output == binding.nativeStream()) matches++;
            if (matches != 1 || (int) stream.getMethod("usage").invoke(binding.nativeStream()) != 5
                    || (int) stream.getMethod("format").invoke(binding.nativeStream()) != 256
                    || (int) stream.getMethod("width").invoke(binding.nativeStream()) != request.width()
                    || (int) stream.getMethod("height").invoke(binding.nativeStream()) != request.height()
                    || (long) stream.getMethod("getId").invoke(binding.nativeStream()) != binding.nativeSurfaceId()) return false;
            Surface surface = (Surface) stream.getMethod("surface").invoke(binding.nativeStream());
            Class<?> utils = Class.forName(XiaomiCameraStillConsumer.SURFACE_UTILS, false, loader);
            return surface != null && surface.isValid()
                    && (long) utils.getMethod("getSurfaceId", Surface.class).invoke(null, surface) == binding.nativeSurfaceId();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalidated) { return false; }
    }

    /** Call in the real authorized startCapture(Surface[]) entry, before handing a matched request off. */
    static Still still(ClassLoader loader, Context oemContext, Object nativeCamera, Surface[] requested,
                       XiaomiCameraHardwareBinding.Selection selection, CameraShareStillRequest request,
                       XiaomiCameraHardwareBinding.PeerPermit peerPermit, NativePermit nativePermit)
            throws ReflectiveOperationException {
        Objects.requireNonNull(selection); Objects.requireNonNull(request);
        Class<?> camera = Class.forName(CAMERA, false, loader);
        Class<?> stream = Class.forName(XiaomiCameraStillConsumer.STREAM, false, loader);
        if (!camera.isInstance(nativeCamera)) throw new IllegalArgumentException("invalid_native_camera_client");
        if (requested == null || requested.length != 1 || requested[0] == null || !requested[0].isValid())
            throw new IllegalArgumentException("camera_still_single_native_target_required");
        selection.validate(request.sessionId(), request.peerId(), selection.nativeDeviceId(), selection.dhId(),
                selection.nativeCameraId(), peerPermit);
        if (!selection.source().id().equals(request.cameraId()))
            throw new SecurityException("camera_still_source_mapping_mismatch");
        int owner = camera.getField("mClientUid").getInt(nativeCamera);
        String ownerPackage = (String) camera.getField("mPackageName").get(nativeCamera);
        String[] packages = Objects.requireNonNull(oemContext).getPackageManager().getPackagesForUid(owner);
        if (owner < 0 || ownerPackage == null || packages == null || !Arrays.asList(packages).contains(ownerPackage)
                || !nativePermit.valid(nativeCamera, owner, ownerPackage, selection, request))
            throw new SecurityException("native_camera_client_not_authorized");
        Object callback = camera.getField("mVirtualCameraCallback").get(nativeCamera);
        if (!(callback instanceof IInterface remote) || remote.asBinder() == null || !remote.asBinder().isBinderAlive())
            throw new IllegalArgumentException("native_camera_consumer_dead");
        Class<?> surfaceUtils = Class.forName(XiaomiCameraStillConsumer.SURFACE_UTILS, false, loader);
        Method surfaceId = surfaceUtils.getMethod("getSurfaceId", Surface.class);
        long target = (long) surfaceId.invoke(null, requested[0]);
        if (target == 0) throw new IllegalArgumentException("native_camera_surface_missing");
        Object list = camera.getMethod("getRequestedOutputStreams").invoke(nativeCamera);
        if (!(list instanceof List<?> outputs) || outputs.size() > 16)
            throw new IllegalArgumentException("invalid_native_camera_outputs");
        Object match = null;
        for (Object output : outputs) {
            if (!stream.isInstance(output)) throw new IllegalArgumentException("invalid_native_camera_stream");
            if ((long) stream.getMethod("getId").invoke(output) != target) continue;
            if (match != null) throw new IllegalArgumentException("ambiguous_native_camera_surface");
            if ((int) stream.getMethod("usage").invoke(output) != 5
                    || (int) stream.getMethod("format").invoke(output) != 256
                    || (int) stream.getMethod("width").invoke(output) != request.width()
                    || (int) stream.getMethod("height").invoke(output) != request.height())
                throw new IllegalArgumentException("native_camera_jpeg_stream_mismatch");
            Surface configured = (Surface) stream.getMethod("surface").invoke(output);
            if (configured == null || !configured.isValid() || (long) surfaceId.invoke(null, configured) != target)
                throw new IllegalArgumentException("native_camera_surface_replaced");
            match = output;
        }
        if (match == null) throw new IllegalArgumentException("native_camera_target_not_configured");
        return new Still(nativeCamera, match, target, remote.asBinder(), owner, ownerPackage);
    }
}
