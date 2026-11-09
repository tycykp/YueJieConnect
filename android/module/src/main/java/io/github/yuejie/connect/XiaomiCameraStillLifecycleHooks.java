package io.github.yuejie.connect;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Revokes only bound still work before original VirtualCamera2 close/reconfigure releases surfaces. */
final class XiaomiCameraStillLifecycleHooks implements AutoCloseable {
    private final XposedModule module;
    private final ClassLoader loader;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, XiaomiCameraStillConsumer> cameras = new IdentityHashMap<>();
    private final Map<Object, Integer> invalidating = new IdentityHashMap<>();
    private boolean closed;

    XiaomiCameraStillLifecycleHooks(XposedModule module, ClassLoader loader) {
        this.module = Objects.requireNonNull(module); this.loader = Objects.requireNonNull(loader);
    }

    synchronized void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("camera_lifecycle_hooks_already_installed");
        Class<?> camera = Class.forName(XiaomiCameraNativeRequests.CAMERA, false, loader);
        Class<?> output = Class.forName("vendor.xiaomi.hardware.camera.synthetic.OutputConfiguration", false, loader);
        try {
            hooks.add(module.hook(camera.getMethod("close")).setId("yj.xiaomi.camera.still.close").intercept(chain -> {
                Object owner = chain.getThisObject();
                beginInvalidation(owner);
                try { return chain.proceed(); }
                finally { endInvalidation(owner); }
            }));
            hooks.add(module.hook(camera.getMethod("configureOutputStreams", Array.newInstance(output, 0).getClass()))
                    .setId("yj.xiaomi.camera.still.reconfigure").intercept(chain -> {
                        Object owner = chain.getThisObject();
                        beginInvalidation(owner);
                        try { return chain.proceed(); }
                        finally { endInvalidation(owner); }
                    }));
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    /** Bind after the real startCapture target/client/selection checks, before async capture/transport. */
    AutoCloseable bind(XiaomiCameraNativeRequests.Still nativeRequest, CameraShareStillRequest request,
                       XiaomiCameraStillConsumer consumer) {
        Objects.requireNonNull(nativeRequest); Objects.requireNonNull(consumer);
        if (!consumer.boundTo(nativeRequest, request) || !XiaomiCameraNativeRequests.current(loader, nativeRequest, request))
            throw new IllegalStateException("native_camera_configuration_expired");
        synchronized (this) {
            cameras.entrySet().removeIf(entry -> entry.getValue().isClosed());
            if (closed || hooks.size() != 2 || consumer.isClosed() || cameras.containsKey(nativeRequest.nativeCamera())
                    || invalidating.containsKey(nativeRequest.nativeCamera()) || cameras.size() >= 8)
                throw new IllegalStateException("native_camera_lifecycle_binding_unavailable");
            cameras.put(nativeRequest.nativeCamera(), consumer);
        }
        // Covers original close/reconfigure running between the first validation and registration.
        if (!XiaomiCameraNativeRequests.current(loader, nativeRequest, request)) {
            remove(nativeRequest.nativeCamera(), consumer);
            throw new IllegalStateException("native_camera_configuration_expired");
        }
        return () -> {
            remove(nativeRequest.nativeCamera(), consumer);
        };
    }

    private void remove(Object nativeCamera, XiaomiCameraStillConsumer consumer) {
        synchronized (this) { cameras.remove(nativeCamera, consumer); }
        consumer.close();
    }

    private void beginInvalidation(Object nativeCamera) {
        XiaomiCameraStillConsumer consumer;
        synchronized (this) {
            invalidating.merge(nativeCamera, 1, Integer::sum);
            consumer = cameras.remove(nativeCamera);
        }
        if (consumer != null) consumer.close();
    }

    private synchronized void endInvalidation(Object nativeCamera) {
        Integer depth = invalidating.get(nativeCamera);
        if (depth != null && depth > 1) invalidating.put(nativeCamera, depth - 1);
        else invalidating.remove(nativeCamera);
    }

    @Override public void close() {
        List<XiaomiCameraStillConsumer> consumers;
        List<HookHandle> installed;
        synchronized (this) {
            if (closed) return;
            closed = true;
            consumers = List.copyOf(cameras.values()); cameras.clear();
            installed = List.copyOf(hooks); hooks.clear();
        }
        for (XiaomiCameraStillConsumer consumer : consumers) consumer.close();
        for (HookHandle hook : installed) hook.unhook();
    }
}
