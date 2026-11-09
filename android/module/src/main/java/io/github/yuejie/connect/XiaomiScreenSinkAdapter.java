package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Replaces only explicitly bound native sink instances. Unrelated OEM sessions remain native. */
public final class XiaomiScreenSinkAdapter implements AutoCloseable {
    public interface Listener extends ScreenCastVideoDecoder.Listener {
        void onPrepared();
        void onStopped();
    }
    /** Called before native start. Return false to reject; true may bind or leave an unrelated sink native. */
    public interface RequestResolver {
        boolean beforeStart(Object nativeSink) throws ReflectiveOperationException;
    }
    public record Scope(String sessionId, long streamId) {
        public Scope {
            if (sessionId == null || !sessionId.matches("[0-9a-f]{64}") || streamId <= 0)
                throw new IllegalArgumentException("invalid_screen_scope");
        }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, Binding> bindings = new IdentityHashMap<>();
    private final Set<Object> releasedSinks = Collections.newSetFromMap(new WeakHashMap<>());
    private Class<?> sinkType;
    private Field mirror, mirrorHandle, option;
    private Method closeOption, releaseLog, connected, disconnected, firstFrame;
    private boolean closed;
    private final RequestResolver requests;

    private static final class Binding {
        final Object sink;
        final Scope scope;
        final ScreenCastFormat format;
        final BooleanSupplier current;
        final ScreenCastVideoDecoder.Listener listener;
        ScreenCastVideoDecoder decoder;
        boolean stopped, announced, optionReleaseReported;
        long generation;
        Binding(Object sink, Scope scope, ScreenCastFormat format, BooleanSupplier current, ScreenCastVideoDecoder.Listener listener) {
            this.sink = sink; this.scope = scope; this.format = format; this.current = current; this.listener = listener;
        }
    }
    public XiaomiScreenSinkAdapter(XposedModule module, ClassLoader loader) { this(module, loader, null); }
    public XiaomiScreenSinkAdapter(XposedModule module, ClassLoader loader, RequestResolver requests) {
        this.module = module; this.loader = loader; this.requests = requests;
    }
    public void install() throws ReflectiveOperationException {
        sinkType = Class.forName("com.xiaomi.mirrorcontrol.MirrorControlSink", false, loader);
        Class<?> meta = Class.forName("com.xiaomi.miplay.report.MirrorMetaInfo", false, loader);
        Class<?> control = Class.forName("com.xiaomi.mirrorcontrol.MirrorControl", false, loader);
        mirror = field("mirror"); mirrorHandle = field("mirrorHandle"); option = field("optionHandle");
        closeOption = control.getMethod("closeMirrorOption", long.class);
        releaseLog = control.getMethod("releaseMirrorLogInfo", long.class);
        connected = sinkType.getMethod("onSinkConnected"); disconnected = sinkType.getMethod("onSinkDisconnected");
        firstFrame = sinkType.getMethod("onSinkInfo", int.class, long.class);
        try {
            hooks.add(module.hook(sinkType.getMethod("startMirror", meta, String.class, int.class, int.class, Surface.class))
                    .setId("yj.screen.xiaomi.sink.start").intercept(chain -> {
                        Binding b = binding(chain.getThisObject());
                        if (wasReleased(chain.getThisObject())) return false;
                        if (b == null && requests != null) {
                            if (!requests.beforeStart(chain.getThisObject())) return false;
                            b = binding(chain.getThisObject());
                        }
                        return b == null ? chain.proceed() : start(b, (Surface) chain.getArg(4));
                    }));
            hooks.add(module.hook(sinkType.getMethod("updateMirrorSurface", Surface.class))
                    .setId("yj.screen.xiaomi.sink.surface").intercept(chain -> {
                        Binding b = binding(chain.getThisObject());
                        if (wasReleased(chain.getThisObject())) return false;
                        if (b == null) return chain.proceed();
                        boolean result = start(b, (Surface) chain.getArg(0));
                        if (result) b.listener.onKeyFrameNeeded();
                        return result;
                    }));
            for (String name : new String[]{"stopMirror", "closeMirror", "releaseVideoCodec"}) {
                hooks.add(module.hook(sinkType.getMethod(name)).setId("yj.screen.xiaomi.sink." + name).intercept(chain -> {
                    Binding b = binding(chain.getThisObject());
                    if (wasReleased(chain.getThisObject())) return "releaseVideoCodec".equals(name) ? null : Boolean.TRUE;
                    if (b == null) return chain.proceed();
                    stop(b);
                    if ("closeMirror".equals(name)) return release(b);
                    return "releaseVideoCodec".equals(name) ? null : Boolean.TRUE;
                }));
            }
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    private Field field(String name) throws ReflectiveOperationException {
        Field f = sinkType.getDeclaredField(name); f.setAccessible(true); return f;
    }
    /** Must be called by the authenticated native entry BEFORE startMirror and AFTER screen permission.
     * Identity must come from that native request; never infer identity from arbitrary IP/port alone. */
    public synchronized void bind(Object nativeSink, Scope scope, ScreenCastFormat format,
                                   BooleanSupplier currentAndScreenAllowed, ScreenCastVideoDecoder.Listener listener)
            throws ReflectiveOperationException {
        if (closed || sinkType == null || !sinkType.isInstance(nativeSink) || bindings.size() >= 4
                || bindings.containsKey(nativeSink) || releasedSinks.contains(nativeSink) || mirrorHandle.getLong(nativeSink) != 0
                || !currentAndScreenAllowed.getAsBoolean()) throw new IllegalStateException("native_sink_not_bindable");
        for (Binding existing : bindings.values()) if (existing.scope.equals(scope))
            throw new IllegalStateException("native_screen_stream_already_bound");
        bindings.put(nativeSink, new Binding(nativeSink, scope, format, currentAndScreenAllowed, listener));
    }
    private synchronized Binding binding(Object sink) { return bindings.get(sink); }
    private synchronized boolean wasReleased(Object sink) { return releasedSinks.contains(sink); }
    private synchronized boolean current(Binding b) {
        return !closed && !b.stopped && bindings.get(b.sink) == b && b.current.getAsBoolean();
    }
    private synchronized boolean start(Binding b, Surface surface) {
        if (!current(b)) { stop(b); return false; }
        long generation = ++b.generation;
        if (b.decoder != null) { b.decoder.close(); b.decoder = null; }
        try {
            b.decoder = new ScreenCastVideoDecoder(b.scope.streamId, b.format, surface, new ScreenCastVideoDecoder.Listener() {
                public void onFrameRendered(long pts, long nano) {
                    main.post(() -> {
                        synchronized (XiaomiScreenSinkAdapter.this) {
                            if (!current(b) || b.generation != generation) return;
                            if (!b.announced) {
                                try {
                                    b.announced = true;
                                    connected.invoke(b.sink);
                                    if (!current(b) || b.generation != generation) return;
                                    // Verified in SinkView and MirrorSinkController: native first-picture event.
                                    firstFrame.invoke(b.sink, 100002, pts);
                                } catch (ReflectiveOperationException error) { stop(b); main.post(()->b.listener.onFailure("native_render_callback_failed")); return; }
                            }
                        }
                        // The media reader holds the session monitor before entering receive().
                        // Never acquire that monitor while holding this adapter's monitor.
                        b.listener.onFrameRendered(pts, nano);
                    });
                }
                public void onKeyFrameNeeded() { b.listener.onKeyFrameNeeded(); }
                public void onFailure(String code) { main.post(() -> {
                    synchronized (XiaomiScreenSinkAdapter.this) {
                        if (b.generation != generation)return;stop(b);
                    }
                    b.listener.onFailure(code);
                }); }
                public void onEnd() { main.post(() -> {
                    synchronized (XiaomiScreenSinkAdapter.this) {
                        if (b.generation != generation)return;stop(b);
                    }
                    b.listener.onEnd();
                }); }
            });
            if (b.listener instanceof Listener prepared) main.post(prepared::onPrepared);
            return current(b) && b.decoder != null; // No native success/first-frame callback until actual render.
        } catch (Exception error) { stop(b); main.post(()->b.listener.onFailure("native_sink_prepare_failed")); return false; }
    }
    public synchronized ScreenCastFrameQueue.Offer receive(Scope scope, ScreenCastPacket packet) {
        for (Binding b : bindings.values()) {
            if (!b.scope.equals(scope)) continue;
            if (!current(b)) { stop(b); return ScreenCastFrameQueue.Offer.CLOSED; }
            if (b.decoder == null) return ScreenCastFrameQueue.Offer.CLOSED;
            return b.decoder.offer(packet);
        }
        return ScreenCastFrameQueue.Offer.STALE;
    }
    private synchronized void stop(Binding b) {
        boolean wasStopped = b.stopped;
        b.stopped = true; b.generation++;
        if (b.decoder != null) { b.decoder.close(); b.decoder = null; }
        if (b.announced) {
            b.announced = false;
            main.post(() -> { try { disconnected.invoke(b.sink); } catch (ReflectiveOperationException ignored) { } });
        }
        releaseOption(b);
        if (!wasStopped && b.listener instanceof Listener listener) main.post(listener::onStopped);
    }
    private void releaseOption(Binding b) {
        try {
            long handle = option.getLong(b.sink);
            if (handle != 0) {
                releaseLog.invoke(mirror.get(b.sink), handle);
                if (Boolean.TRUE.equals(closeOption.invoke(mirror.get(b.sink), handle))) option.setLong(b.sink, 0);
                else reportOptionFailure(b);
            }
        } catch (ReflectiveOperationException error) { reportOptionFailure(b); }
    }
    private void reportOptionFailure(Binding b) {
        if (!b.optionReleaseReported) {
            b.optionReleaseReported = true; main.post(()->b.listener.onFailure("native_option_release_failed"));
        }
    }
    private synchronized boolean release(Binding b) {
        stop(b);
        try {
            if (option.getLong(b.sink) != 0) return false;
        } catch (ReflectiveOperationException error) { return false; }
        bindings.remove(b.sink); releasedSinks.add(b.sink); return true;
    }
    public synchronized void sessionChanged() {
        for (Binding b : new ArrayList<>(bindings.values())) release(b);
    }
    /** Release one negotiated stream without tearing down other native windows. */
    public synchronized void stop(Scope scope) {
        for (Binding b : new ArrayList<>(bindings.values())) if (b.scope.equals(scope)) release(b);
    }
    @Override public synchronized void close() {
        closed = true;
        for (Binding b : new ArrayList<>(bindings.values())) release(b);
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
    }
}
