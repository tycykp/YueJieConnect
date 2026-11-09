package io.github.yuejie.connect;

import android.media.Image;
import android.media.ImageReader;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observes original acquisitions in an already authorized OEM client process. No reader replacement. */
final class XiaomiCameraStillResultHooks implements AutoCloseable {
    private final XposedModule module;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<ImageReader, XiaomiCameraStillConsumer> readers = new IdentityHashMap<>();
    private final ThreadLocal<Integer> acquisitionDepth = ThreadLocal.withInitial(() -> 0);
    private boolean closed;

    XiaomiCameraStillResultHooks(XposedModule module) { this.module = Objects.requireNonNull(module); }

    synchronized void install() throws NoSuchMethodException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("camera_result_hooks_already_installed");
        try {
            hooks.add(module.hook(ImageReader.class.getMethod("acquireNextImage"))
                    .setId("yj.xiaomi.camera.result.next").intercept(this::acquire));
            hooks.add(module.hook(ImageReader.class.getMethod("acquireLatestImage"))
                    .setId("yj.xiaomi.camera.result.latest").intercept(this::acquire));
            hooks.add(module.hook(ImageReader.class.getMethod("close"))
                    .setId("yj.xiaomi.camera.result.close").intercept(chain -> {
                        XiaomiCameraStillConsumer consumer = remove((ImageReader) chain.getThisObject());
                        // Revoke before the original native reader/Surface can be released or replaced.
                        if (consumer != null) consumer.close();
                        return chain.proceed();
                    }));
        } catch (NoSuchMethodException | RuntimeException failure) {
            close(); throw failure;
        }
    }

    /** Caller already owns this exact original reader in this process; a Surface proxy is insufficient. */
    AutoCloseable bind(ImageReader reader, XiaomiCameraStillConsumer consumer) throws ReflectiveOperationException {
        Objects.requireNonNull(reader); Objects.requireNonNull(consumer);
        synchronized (this) {
            readers.entrySet().removeIf(entry -> entry.getValue().isClosed());
            if (closed || hooks.size() != 3 || readers.containsKey(reader) || readers.size() >= 8)
                throw new IllegalStateException("native_camera_result_observer_unavailable");
        }
        // Never hold the registry lock while calling permission/native-consumer code.
        consumer.bindOriginalReader(reader);
        boolean registered;
        synchronized (this) {
            registered = !closed && !readers.containsKey(reader) && readers.size() < 8 && !consumer.isClosed();
            if (registered) readers.put(reader, consumer);
        }
        if (!registered) { consumer.close(); throw new IllegalStateException("native_camera_result_binding_changed"); }
        return () -> {
            synchronized (XiaomiCameraStillResultHooks.this) { readers.remove(reader, consumer); }
            consumer.close();
        };
    }

    private Object acquire(Chain chain) throws Throwable {
        int depth = acquisitionDepth.get();
        acquisitionDepth.set(depth + 1);
        Object result;
        try { result = chain.proceed(); }
        finally {
            if (depth == 0) acquisitionDepth.remove(); else acquisitionDepth.set(depth);
        }
        // acquireLatestImage can acquire and discard several older images internally. Only the
        // outermost successful original call's returned Image is evidence of this consumer result.
        if (depth == 0 && result instanceof Image image) {
            ImageReader reader = (ImageReader) chain.getThisObject();
            XiaomiCameraStillConsumer consumer;
            synchronized (this) { consumer = closed ? null : readers.get(reader); }
            if (consumer != null) {
                try { consumer.acquired(reader, image); }
                catch (RuntimeException | LinkageError observerFailure) { consumer.close(); }
                // No changes to the original result or Image ownership, including on observer failure.
                if (consumer.isClosed()) synchronized (this) { readers.remove(reader, consumer); }
            }
        }
        return result;
    }

    private synchronized XiaomiCameraStillConsumer remove(ImageReader reader) { return readers.remove(reader); }

    @Override public void close() {
        List<XiaomiCameraStillConsumer> consumers;
        List<HookHandle> installed;
        synchronized (this) {
            if (closed) return;
            closed = true;
            consumers = List.copyOf(readers.values()); readers.clear();
            installed = List.copyOf(hooks); hooks.clear();
        }
        for (XiaomiCameraStillConsumer consumer : consumers) consumer.close();
        for (HookHandle hook : installed) hook.unhook();
    }
}
