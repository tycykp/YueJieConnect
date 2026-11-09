package io.github.yuejie.connect;

import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves the actual phone/pad native owner from its SinkCallback, never from an IP address.
 * The authenticated control plane must supply a fresh, approved native request before startMirror.
 * This adapter neither grants screen permission nor creates a native window or channel. */
public final class XiaomiScreenRequestAdapter implements AutoCloseable {
    public interface NativeWindowDelegate {
        /** Called at the actual MirrorControlSink start, after the OEM created this window.
         * Resolve against the current authenticated request AND the source's native consent.
         * Returning null leaves an unrelated OEM request alone. Never grant from a screenId alone. */
        Expectation resolve(NativeWindow window) throws ReflectiveOperationException;
    }
    public record NativeWindow(Object window, Object nativePeer, String deviceId, int screenId, boolean mainScreen,
                               BooleanSupplier nativeCurrent) {
        /** Legacy DTOs alone cannot assert an actual currently owned native window. */
        public NativeWindow(Object window, Object nativePeer, String deviceId, int screenId, boolean mainScreen) {
            this(window, nativePeer, deviceId, screenId, mainScreen, () -> false);
        }
        public NativeWindow { Objects.requireNonNull(nativeCurrent); }
        public boolean current() {
            try { return nativeCurrent.getAsBoolean(); } catch (RuntimeException error) { return false; }
        }
        @Override public String toString() { return "NativeScreenWindow"; }
    }
    public record Expectation(XiaomiScreenSinkAdapter.Scope scope, ScreenCastFormat format, long deadlineElapsedMs,
                              BooleanSupplier currentAndConsented, ScreenCastVideoDecoder.Listener listener) { }
    private record Request(String deviceId, int screenId) {
        Request {
            if (deviceId == null || deviceId.isBlank() || deviceId.length() > 256 || screenId < 0)
                throw new IllegalArgumentException("invalid_native_screen_request");
        }
    }
    private record Grant(XiaomiScreenSinkAdapter.Scope scope, ScreenCastFormat format,
                         BooleanSupplier allowed, ScreenCastVideoDecoder.Listener listener, long deadline) { }
    private record Owner(Object window, Object peer, Request request, boolean main) { }
    private final ClassLoader loader;
    private final XiaomiScreenSinkAdapter sinks;
    private final NativeWindowDelegate delegate;
    private final Map<Request, Grant> pending = new HashMap<>();
    private Class<?> viewCallback, controllerCallback;
    private Field callback, viewOwner, viewPeer, controllerOwner, controllerPeer, controllerDevice, controllerScreen, controllerSink;
    private Method viewDevice, viewScreen, viewAuth, viewLocked, peerId;
    private boolean installed;
    private volatile boolean closed;
    private volatile long generation;

    public XiaomiScreenRequestAdapter(XposedModule module, ClassLoader loader) {
        this(module, loader, null);
    }
    public XiaomiScreenRequestAdapter(XposedModule module, ClassLoader loader, NativeWindowDelegate delegate) {
        this.loader = Objects.requireNonNull(loader);
        this.delegate = delegate;
        sinks = new XiaomiScreenSinkAdapter(module, loader, this::beforeStart);
    }

    public synchronized void install() throws ReflectiveOperationException {
        if (closed || installed) throw new IllegalStateException("screen_request_adapter_not_installable");
        Class<?> sink = Class.forName("com.xiaomi.mirrorcontrol.MirrorControlSink", false, loader);
        Class<?> view = Class.forName("com.xiaomi.mirror.sink.SinkView", false, loader);
        Class<?> controller = Class.forName("com.xiaomi.mirror.sink.d", false, loader);
        viewCallback = Class.forName("com.xiaomi.mirror.sink.SinkView$d", false, loader);
        controllerCallback = Class.forName("com.xiaomi.mirror.sink.d$a", false, loader);
        callback = field(sink, "mCallback", Class.forName("com.xiaomi.mirrorcontrol.MirrorControlSink$SinkCallback", false, loader));
        viewOwner = field(viewCallback, "a", view);
        Class<?> peer = Class.forName("com.xiaomi.mirror.l0", false, loader);
        viewPeer = field(view, "p", peer); controllerPeer = field(controller, "g", peer);
        peerId = Class.forName("com.xiaomi.mirror.k0", false, loader).getMethod("h");
        controllerOwner = field(controllerCallback, "a", controller);
        controllerDevice = field(controller, "a", String.class);
        controllerScreen = field(controller, "b", int.class);
        controllerSink = field(controller, "e", sink);
        viewDevice = view.getMethod("getDeviceId"); viewScreen = view.getMethod("getScreenId");
        viewAuth = view.getMethod("getIsShowAuth"); viewLocked = view.getMethod("getRemoteDeviceKeyguardLock");
        sinks.install(); installed = true;
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        if (result.getType() != expected) throw new NoSuchFieldException("native_screen_field_type_changed");
        result.setAccessible(true); return result;
    }

    /** Call only after BOTH current peer approval and that native request's screen permission.
     * screenId must be the real ScreenConfigurationChangedMessage/native screen ID, not streamId.
     * The deadline is local elapsedRealtime, at most ten seconds away. No permission is inferred
     * from PREPARE_RESULT=0 (the OEM main-screen handler itself does not treat it as a grant). */
    public synchronized void authorizeRequest(String nativeDeviceId, int nativeScreenId,
            XiaomiScreenSinkAdapter.Scope scope, ScreenCastFormat format, long deadlineElapsedMs,
            BooleanSupplier currentRequestAndScreenAllowed, ScreenCastVideoDecoder.Listener listener) {
        Objects.requireNonNull(scope); Objects.requireNonNull(format);
        Objects.requireNonNull(currentRequestAndScreenAllowed); Objects.requireNonNull(listener);
        prune(); long remaining = deadlineElapsedMs - SystemClock.elapsedRealtime();
        Request request = new Request(nativeDeviceId, nativeScreenId);
        if (!installed || closed || remaining <= 0 || remaining > 10_000 || pending.size() >= 4
                || pending.containsKey(request) || !currentRequestAndScreenAllowed.getAsBoolean())
            throw new IllegalStateException("native_screen_request_not_authorized");
        for (Grant grant : pending.values()) if (grant.scope.equals(scope))
            throw new IllegalStateException("screen_stream_already_pending");
        pending.put(request, new Grant(scope, format, currentRequestAndScreenAllowed, listener, deadlineElapsedMs));
    }

    private synchronized boolean beforeStart(Object sink) throws ReflectiveOperationException {
        if (closed || !installed) return true;
        prune();
        Owner owner = owner(sink);
        if (owner == null) return true;
        AtomicBoolean lifetime = new AtomicBoolean(true);
        long nativeGeneration = generation;
        Grant grant = pending.remove(owner.request);
        if (grant == null && delegate != null && owner.peer != null && matches(owner, sink)) {
            Expectation expected = delegate.resolve(new NativeWindow(owner.window, owner.peer,
                    owner.request.deviceId, owner.request.screenId, owner.main, () -> {
                        try { return lifetime.get() && !closed && generation == nativeGeneration && matches(owner, sink); }
                        catch (ReflectiveOperationException | RuntimeException error) { return false; }
                    }));
            if (expected != null) {
                authorizeRequest(owner.request.deviceId, owner.request.screenId, expected.scope, expected.format,
                        expected.deadlineElapsedMs, expected.currentAndConsented, expected.listener);
                grant = pending.remove(owner.request);
            }
        }
        if (grant == null) return true;
        Grant selected = grant;
        // Consume once even if a native precondition fails. Never let a later window reuse it.
        BooleanSupplier gate = () -> {
            try { return lifetime.get() && !closed && generation == nativeGeneration && selected.allowed.getAsBoolean() && matches(owner, sink); }
            catch (ReflectiveOperationException | RuntimeException error) { return false; }
        };
        if (!gate.getAsBoolean()) {
            lifetime.set(false);
            grant.listener.onFailure("native_screen_request_changed");
            return false;
        }
        try { sinks.bind(sink, grant.scope, grant.format, gate, listener(grant.listener, lifetime)); }
        catch (ReflectiveOperationException | RuntimeException error) {
            lifetime.set(false);
            grant.listener.onFailure("native_screen_bind_failed"); return false;
        }
        return true;
    }
    private XiaomiScreenSinkAdapter.Listener listener(ScreenCastVideoDecoder.Listener target, AtomicBoolean lifetime) {
        return new XiaomiScreenSinkAdapter.Listener() {
            public void onFrameRendered(long pts, long nanoTime) { if (lifetime.get()) target.onFrameRendered(pts, nanoTime); }
            public void onKeyFrameNeeded() { if (lifetime.get()) target.onKeyFrameNeeded(); }
            public void onFailure(String reason) { lifetime.set(false); target.onFailure(reason); }
            public void onEnd() { lifetime.set(false); target.onEnd(); }
            public void onPrepared() { if (lifetime.get() && target instanceof XiaomiScreenSinkAdapter.Listener l) l.onPrepared(); }
            public void onStopped() { lifetime.set(false); if (target instanceof XiaomiScreenSinkAdapter.Listener l) l.onStopped(); }
        };
    }

    private Owner owner(Object sink) throws ReflectiveOperationException {
        Object cb = callback.get(sink);
        if (cb != null && cb.getClass() == viewCallback) {
            Object window = viewOwner.get(cb);
            return new Owner(window, viewPeer.get(window), new Request((String) viewDevice.invoke(window), (Integer) viewScreen.invoke(window)), true);
        }
        if (cb != null && cb.getClass() == controllerCallback) {
            Object window = controllerOwner.get(cb);
            if (controllerSink.get(window) != sink) return null;
            return new Owner(window, controllerPeer.get(window), new Request((String) controllerDevice.get(window), controllerScreen.getInt(window)), false);
        }
        return null; // PC/Mac sinks and any new callback implementation retain native behavior.
    }

    private boolean matches(Owner expected, Object sink) throws ReflectiveOperationException {
        Owner now = owner(sink);
        return now != null && now.window == expected.window && now.peer != null && now.peer == expected.peer
                && now.request.deviceId.equals(peerId.invoke(now.peer)) && now.request.equals(expected.request)
                && (!now.main || (!Boolean.TRUE.equals(viewAuth.invoke(now.window))
                                  && !Boolean.TRUE.equals(viewLocked.invoke(now.window))));
    }

    private void prune() {
        long now = SystemClock.elapsedRealtime();
        pending.entrySet().removeIf(entry -> entry.getValue().deadline <= now);
    }

    public ScreenCastFrameQueue.Offer receive(XiaomiScreenSinkAdapter.Scope scope, ScreenCastPacket packet) {
        return sinks.receive(scope, packet);
    }
    /** Cancel only this request/stream; sessionChanged remains the whole-session teardown. */
    public void stop(XiaomiScreenSinkAdapter.Scope scope) {
        Objects.requireNonNull(scope);
        synchronized (this) { pending.entrySet().removeIf(entry -> entry.getValue().scope.equals(scope)); }
        sinks.stop(scope);
    }
    public void sessionChanged() {
        synchronized (this) { generation++; pending.clear(); }
        sinks.sessionChanged();
    }
    @Override public void close() {
        synchronized (this) { closed = true; pending.clear(); }
        sinks.close();
    }
}
