package io.github.yuejie.connect;

import android.util.Pair;
import android.view.Surface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Supplies an AVC input Surface to a real, approved PadCastDisplayManager request.
 * Runs inside com.oplus.cast. Native capture, mirage selection and privacy UI remain OEM-owned. */
public final class OplusScreenSourceAdapter implements AutoCloseable {
    public interface Listener extends ScreenCastVideoEncoder.Listener {
        void onPrepared(int nativeDisplayId);
        void onStopped();
    }
    public record Scope(String sessionId, long streamId) {
        public Scope {
            if (sessionId == null || !sessionId.matches("[0-9a-f]{64}") || streamId <= 0)
                throw new IllegalArgumentException("invalid_screen_scope");
        }
    }
    private static final class Binding {
        final Object manager;
        final String busId;
        final Scope scope;
        final ScreenCastFormat format;
        final BooleanSupplier allowed;
        final Executor nativeExecutor;
        final ScreenCastVideoEncoder.Listener listener;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean stopReported = new AtomicBoolean();
        final AtomicBoolean releaseQueued = new AtomicBoolean();
        final AtomicBoolean releaseFailureReported = new AtomicBoolean();
        volatile ScreenCastVideoEncoder encoder;
        Binding(Object manager, String busId, Scope scope, ScreenCastFormat format,
                BooleanSupplier allowed, Executor executor, ScreenCastVideoEncoder.Listener listener) {
            this.manager = manager; this.busId = busId; this.scope = scope; this.format = format;
            this.allowed = allowed; nativeExecutor = executor; this.listener = listener;
        }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Map<Object, Binding> bindings = new IdentityHashMap<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private Class<?> padManager;
    private Field busId, released;
    private Method width, height, deliverSurface, release;
    private volatile boolean closed;

    public OplusScreenSourceAdapter(XposedModule module, ClassLoader loader) {
        this.module = Objects.requireNonNull(module); this.loader = Objects.requireNonNull(loader);
    }

    public void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("screen_source_not_installable");
        String prefix = "com.oplus.cast.engine.impl.odmf.display.castdisplaymanager.";
        Class<?> base = Class.forName(prefix + "CastDisplayManager", false, loader);
        padManager = Class.forName(prefix + "PadCastDisplayManager", false, loader);
        Class<?> size = Class.forName("z2.l", false, loader);
        width = size.getMethod("c"); height = size.getMethod("b");
        busId = field(base, "g", String.class); released = field(base, "q", boolean.class);
        deliverSurface = base.getDeclaredMethod("T", Pair.class, Consumer.class, Surface.class);
        deliverSurface.setAccessible(true); release = base.getMethod("k0");
        try {
            hooks.add(module.hook(base.getDeclaredMethod("F", size, size, int.class, Consumer.class))
                    .setId("yj.screen.oplus.pad.surface").intercept(chain -> {
                        Binding binding = binding(chain.getThisObject());
                        if (binding == null) return chain.proceed();
                        return provide(binding, chain.getArg(0), (Integer) chain.getArg(2), (Consumer<?>) chain.getArg(3));
                    }));
            hooks.add(module.hook(release).setId("yj.screen.oplus.pad.release").intercept(chain -> {
                Binding binding = binding(chain.getThisObject());
                if (binding == null) return chain.proceed();
                binding.stopped.set(true);
                try {
                    Object result = chain.proceed();
                    if (released.getBoolean(binding.manager)) finish(binding);
                    else releaseFailed(binding, "native_source_release_unconfirmed");
                    return result;
                } catch (Throwable error) {
                    releaseFailed(binding, "native_source_release_failed"); throw error;
                }
            }));
            Class<?> displayConfig = Class.forName("com.oplus.cast.engine.impl.odmf.display.b", false, loader);
            hooks.add(module.hook(base.getDeclaredMethod("y", Surface.class, displayConfig))
                    .setId("yj.screen.oplus.pad.prepared").intercept(chain -> {
                        Object result = chain.proceed();
                        Binding binding = binding(chain.getThisObject());
                        ScreenCastVideoEncoder encoder = binding == null ? null : binding.encoder;
                        if (binding != null && current(binding) && encoder != null
                                && chain.getArg(0) == encoder.inputSurface()) {
                            if (result instanceof Integer id && id >= 0) {
                                if (binding.listener instanceof Listener listener) listener.onPrepared(id);
                            } else stop(binding, "native_source_display_failed");
                        }
                        return result;
                    }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field value = type.getDeclaredField(name);
        if (value.getType() != expected) throw new NoSuchFieldException("native_cast_field_type_changed");
        value.setAccessible(true); return value;
    }

    /** Bind the actual PadCastDisplayManager created after CastService's normal onAllow path.
     * transportBusId must be supplied by the authenticated native session mapping. The gate must
     * include native permission/revocation, current Noise session and screen-specific approval.
     * nativeExecutor must dispatch onto the OEM cast worker, where k0 normally releases capture. */
    public synchronized void bind(Object nativePadManager, String transportBusId, Scope scope,
            ScreenCastFormat format, BooleanSupplier currentRequestAndScreenAllowed, Executor nativeExecutor,
            ScreenCastVideoEncoder.Listener listener) throws ReflectiveOperationException {
        Objects.requireNonNull(scope); Objects.requireNonNull(format); Objects.requireNonNull(listener);
        Objects.requireNonNull(nativeExecutor); Objects.requireNonNull(currentRequestAndScreenAllowed);
        if (closed || hooks.isEmpty() || nativePadManager == null || nativePadManager.getClass() != padManager
                || transportBusId == null || transportBusId.isBlank() || transportBusId.length() > 256
                || !transportBusId.equals(busId.get(nativePadManager)) || released.getBoolean(nativePadManager)
                || bindings.size() >= 4 || bindings.containsKey(nativePadManager) || !currentRequestAndScreenAllowed.getAsBoolean())
            throw new IllegalStateException("native_pad_source_not_bindable");
        for (Binding binding : bindings.values()) if (binding.scope.equals(scope))
            throw new IllegalStateException("native_screen_stream_already_bound");
        bindings.put(nativePadManager, new Binding(nativePadManager, transportBusId, scope, format,
                currentRequestAndScreenAllowed, nativeExecutor, listener));
    }

    private synchronized Binding binding(Object manager) { return bindings.get(manager); }
    private boolean current(Binding binding) {
        try {
            return !closed && !binding.stopped.get() && binding.allowed.getAsBoolean()
                    && binding.busId.equals(busId.get(binding.manager)) && !released.getBoolean(binding.manager);
        } catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }

    private boolean provide(Binding binding, Object size, int fps, Consumer<?> consumer) {
        synchronized (binding) { return provideSerial(binding, size, fps, consumer); }
    }
    private boolean provideSerial(Binding binding, Object size, int fps, Consumer<?> consumer) {
        if (!current(binding)) { stop(binding, "screen_permission_revoked"); return false; }
        try {
            if ((Integer) width.invoke(size) != binding.format.width() || (Integer) height.invoke(size) != binding.format.height()
                    || fps != binding.format.fps()) {
                stop(binding, "screen_renegotiation_required"); return false;
            }
            ScreenCastVideoEncoder encoder = binding.encoder;
            if (encoder == null) {
                encoder = new ScreenCastVideoEncoder(binding.scope.streamId, binding.format, new ScreenCastVideoEncoder.Listener() {
                    public boolean offer(ScreenCastPacket packet) {
                        if (!current(binding)) { stop(binding, "screen_permission_revoked"); return false; }
                        return binding.listener.offer(packet);
                    }
                    public void onFailure(String code) { stop(binding, code); }
                });
                binding.encoder = encoder;
            }
            if (!current(binding) || !encoder.inputSurface().isValid()) {
                stop(binding, "native_source_surface_invalid"); return false;
            }
            // OEM T stores bitrate metadata then invokes the original continuation. That continuation
            // creates the real native display; obtaining this Surface is not a remote render receipt.
            deliverSurface.invoke(binding.manager, Pair.create(binding.format.bitrate(), binding.format.bitrate()),
                    consumer, encoder.inputSurface());
            encoder.requestKeyFrame();
            return true;
        } catch (Exception error) { stop(binding, "native_source_prepare_failed"); return false; }
    }

    public void requestKeyFrame(Scope scope) {
        List<Binding> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bindings.values()); }
        for (Binding binding : snapshot) if (binding.scope.equals(scope) && current(binding)) {
            ScreenCastVideoEncoder encoder = binding.encoder;
            if (encoder != null) encoder.requestKeyFrame();
        }
    }

    private void stop(Binding binding, String code) {
        boolean firstStop = !binding.stopped.getAndSet(true);
        if (binding.stopReported.get() || !binding.releaseQueued.compareAndSet(false, true)) return;
        try {
            binding.nativeExecutor.execute(() -> {
                try {
                    release.invoke(binding.manager);
                    if (released.getBoolean(binding.manager)) finish(binding);
                    else releaseFailed(binding, "native_source_release_unconfirmed");
                } catch (ReflectiveOperationException error) { releaseFailed(binding, "native_source_release_failed"); }
                finally { binding.releaseQueued.set(false); }
            });
        } catch (RuntimeException error) {
            try { releaseFailed(binding, "native_source_release_dispatch_failed"); }
            finally { binding.releaseQueued.set(false); }
        }
        if (code != null && firstStop) binding.listener.onFailure(code);
    }

    private void releaseFailed(Binding binding, String code) {
        // Capture may still exist. Stop encoding immediately, retain ownership and retry release
        // on the OEM worker; never claim onStopped merely because k0 was attempted.
        ScreenCastVideoEncoder encoder;
        synchronized (binding) { encoder = binding.encoder; binding.encoder = null; }
        if (encoder != null) encoder.close();
        if (binding.releaseFailureReported.compareAndSet(false, true)) binding.listener.onFailure(code);
    }

    private void finish(Binding binding) {
        ScreenCastVideoEncoder encoder;
        synchronized (binding) { encoder = binding.encoder; binding.encoder = null; }
        if (encoder != null) encoder.close();
        synchronized (this) { bindings.remove(binding.manager, binding); }
        if (binding.stopReported.compareAndSet(false, true) && binding.listener instanceof Listener listener)
            listener.onStopped();
    }
    public void sessionChanged() {
        List<Binding> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bindings.values()); }
        for (Binding binding : snapshot) stop(binding, null);
    }
    public void stop(Scope scope) {
        List<Binding> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bindings.values()); }
        for (Binding binding : snapshot) if (binding.scope.equals(scope)) stop(binding, null);
    }
    /** Existing lifecycle timer, including while no frames arrive or release dispatch failed. */
    public void tick() {
        List<Binding> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bindings.values()); }
        for (Binding binding : snapshot) if (binding.stopped.get() || !current(binding)) stop(binding, null);
    }
    @Override public void close() {
        closed = true; sessionChanged();
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
    }
}
