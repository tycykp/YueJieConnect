package io.github.qiangjian.connect;

import android.os.Bundle;
import android.os.Binder;
import android.os.IInterface;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Correlates one real CastService request, its native onAllow path and its PadCastDisplayManager.
 * No authorization callback is fabricated. An expectation alone cannot start capture. */
public final class OplusScreenRequestAdapter implements AutoCloseable {
    public interface ConsentListener extends OplusScreenSourceAdapter.Listener {
        /** Original OEM callback has completed successfully on this exact DeviceInfo. Enqueue
         * authenticated control/media setup here; do not block the native Binder callback. */
        void onNativeAllowed(Object nativeDeviceInfo);
        default boolean usesBridgeCapture(){return false;}
        default void onNativePrompted(Object nativeDeviceInfo) { }
        /** Exact manager bound to this permitted DeviceInfo; not an inferred capture display. */
        default void onNativeSource(Object nativeDeviceInfo,Object actualPadManager,String actualBusId) { }
    }
    /** Resolve only from a verified Linker request/current authenticated peer directory.
     * UID/name/IP fields by themselves are not proof of either identity or permission. */
    public interface NativeRequestDelegate {
        Expectation resolve(NativeRequest request) throws ReflectiveOperationException;
    }
    public record NativeRequest(Object deviceInfo, String nativeUid, String deviceType, int callerUid, int callerPid, String requestProof) {
        public NativeRequest(Object deviceInfo, String nativeUid, String deviceType, int callerUid, int callerPid) {
            this(deviceInfo, nativeUid, deviceType, callerUid, callerPid, null);
        }
        public NativeRequest {
            if (requestProof != null && !requestProof.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("native_screen_request_proof_invalid");
        }
        @Override public String toString() { return "NativeScreenRequest"; }
    }
    public record Expectation(OplusScreenSourceAdapter.Scope scope, ScreenCastFormat format, long deadlineElapsedMs,
                              BooleanSupplier current, ScreenCastVideoEncoder.Listener listener) { }
    private static final class Request {
        final Object device;
        final OplusScreenSourceAdapter.Scope scope;
        final ScreenCastFormat format;
        final long deadline;
        final BooleanSupplier current;
        final ScreenCastVideoEncoder.Listener listener;
        final ScreenCastNativeConsent consent;
        Object callback, callbackBinder;
        Runnable deferredStart;
        boolean bound;
        Request(Object device, OplusScreenSourceAdapter.Scope scope, ScreenCastFormat format, long deadline,
                BooleanSupplier current, ScreenCastVideoEncoder.Listener listener) {
            this.device = device; this.scope = scope; this.format = format; this.deadline = deadline;
            this.current = current; this.listener = listener;
            consent = new ScreenCastNativeConsent(deadline, current, SystemClock::elapsedRealtime);
            if (!consent.attach(device)) throw new IllegalStateException("native_screen_request_changed");
        }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final OplusScreenSourceAdapter source;
    private final Map<Object, Request> pending = new IdentityHashMap<>();
    // Weak identity tombstones prevent a cancelled bridge request from falling back to the
    // unrelated OEM path when its delayed allow/start callbacks arrive. Never match by UID alone.
    private final List<WeakReference<Object>> retired = new ArrayList<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ThreadLocal<Object> constructing = new ThreadLocal<>();
    private Class<?> deviceType;
    private Field managerDevice, managerBus, managerDisplay;
    private Method deviceKind, post;
    private Object worker;
    private final NativeRequestDelegate delegate;
    private volatile boolean closed;

    public OplusScreenRequestAdapter(XposedModule module, ClassLoader loader) {
        this(module, loader, null);
    }
    public OplusScreenRequestAdapter(XposedModule module, ClassLoader loader, NativeRequestDelegate delegate) {
        this.module = Objects.requireNonNull(module); this.loader = Objects.requireNonNull(loader);
        this.delegate = delegate;
        source = new OplusScreenSourceAdapter(module, loader);
    }
    public void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("screen_requests_not_installable");
        deviceType = Class.forName("com.oplus.cast.service.sdk.DeviceInfo", false, loader);
        Class<?> manager = Class.forName("com.oplus.cast.engine.impl.odmf.a", false, loader);
        Class<?> display = Class.forName("com.oplus.cast.engine.impl.odmf.display.castdisplaymanager.CastDisplayManager", false, loader);
        managerDevice = field(manager,"d",deviceType); managerBus = field(manager,"h",String.class);
        managerDisplay = field(manager,"e",display); deviceKind = deviceType.getMethod("l");
        Class<?> thread = Class.forName("j3.i", false, loader);
        worker = thread.getMethod("a").invoke(null); post = thread.getMethod("c",Runnable.class);
        source.install();
        try {
            Class<?> binder = Class.forName("com.oplus.cast.service.CastService$j", false, loader);
            Class<?> callbackType = Class.forName("f3.j", false, loader);
            Class<?> callbackProxy = Class.forName("f3.j$a$a", false, loader);
            Method uid = deviceType.getMethod("o");
            Method enqueue = binder.getDeclaredMethod("q2", deviceType, Bundle.class, int.class, String.class, String.class);
            enqueue.setAccessible(true);
            // The existing-permission branch queues its start BEFORE sending onAllow. Hold only
            // our bound request until that actual callback succeeds, avoiding a handler race.
            hooks.add(module.hook(enqueue).setId("qj.screen.oplus.native.enqueue").intercept(chain -> {
                if (retired(chain.getArg(0))) return null;
                Request request = pending(chain.getArg(0));
                if (retired(chain.getArg(0))) return null;
                if(request!=null&&request.listener instanceof ConsentListener listener&&listener.usesBridgeCapture())return null;
                if (request == null || request.consent.approved(request.device)) return chain.proceed();
                Object receiver = chain.getThisObject();
                Object[] args = new Object[]{chain.getArg(0), chain.getArg(1) == null ? null : new Bundle((Bundle) chain.getArg(1)),
                        chain.getArg(2), chain.getArg(3), chain.getArg(4)};
                synchronized (this) {
                    if (request.deferredStart != null) { fail(request, "native_screen_start_replayed"); return null; }
                    request.deferredStart = () -> {
                        try { if (request.consent.approved(request.device)) enqueue.invoke(receiver, args); }
                        catch (ReflectiveOperationException error) { fail(request, "native_screen_start_failed"); }
                    };
                }
                return null;
            }));
            hooks.add(module.hook(binder.getDeclaredMethod("F0", deviceType, callbackType, Bundle.class))
                    .setId("qj.screen.oplus.native.request").intercept(chain -> {
                        Object device = chain.getArg(0), callback = chain.getArg(1);
                        if (retired(device)) return -4;
                        Bundle nativeBundle = (Bundle) chain.getArg(2);
                        String proof = OplusScreenLinkerAdapter.readRequestProof(nativeBundle);
                        // OEM F0.y2 logs all ordinary extras. This private one-shot handle is only
                        // for the verified resolver, never for OEM logs or downstream capture metadata.
                        if (nativeBundle != null) nativeBundle.remove(OplusScreenLinkerAdapter.REQUEST_PROOF_EXTRA);
                        Request request = pending(device);
                        if (retired(device)) return -4;
                        if (request == null && delegate != null && device != null) {
                            Expectation expected = delegate.resolve(new NativeRequest(device, (String) uid.invoke(device),
                                    (String) deviceKind.invoke(device), Binder.getCallingUid(), Binder.getCallingPid(),
                                    proof));
                            if (expected != null) {
                                expectRequest(device, expected.scope, expected.format, expected.deadlineElapsedMs,
                                        expected.current, expected.listener);
                                request = pending(device);
                            }
                        }
                        if (request == null) return chain.proceed();
                        // This sampled Binder proxy is the actual remote callback. Other callback shapes
                        // stay unbound; do not guess a local implementation or fabricate a callback.
                        if (callback == null || callback.getClass() != callbackProxy || !attachCallback(request, callback)) {
                            fail(request, "native_screen_callback_unavailable"); return -4;
                        }
                        try {
                            Object result = chain.proceed();
                            if (!(result instanceof Integer code) || code != 0) fail(request, "native_screen_request_failed");
                            return result; // In particular, zero does NOT grant consent.
                        } catch (Throwable error) { fail(request, "native_screen_request_failed"); throw error; }
                    }));
            for (String name : new String[]{"C0", "a", "g"}) {
                hooks.add(module.hook(callbackProxy.getDeclaredMethod(name)).setId("qj.screen.oplus.consent." + name)
                        .intercept(chain -> {
                            Request request = callbackRequest(chain.getThisObject());
                            if (request != null && "g".equals(name)) fail(request, "native_screen_consent_denied");
                            try {
                                Object result = chain.proceed();
                                if (request != null && "C0".equals(name) && request.consent.prompted(request.device)
                                        && request.listener instanceof ConsentListener listener) listener.onNativePrompted(request.device);
                                if (request != null && "a".equals(name)) {
                                    boolean newlyAllowed = request.consent.nativeAllowed(request.device);
                                    if (!newlyAllowed && !request.consent.approved(request.device)) {
                                        fail(request, "native_screen_consent_expired"); return result;
                                    }
                                    if (newlyAllowed && request.listener instanceof ConsentListener listener)
                                        listener.onNativeAllowed(request.device);
                                    Runnable start;
                                    synchronized (this) { start = request.deferredStart; request.deferredStart = null; }
                                    if (start != null) start.run();
                                }
                                return result;
                            }
                            catch (Throwable error) {
                                if (request != null) fail(request, "native_screen_consent_callback_failed");
                                throw error;
                            }
                        }));
            }
            hooks.add(module.hook(manager.getDeclaredMethod("J")).setId("qj.screen.oplus.native.construct").intercept(chain -> {
                Object previous = constructing.get(); constructing.set(chain.getThisObject());
                try { return chain.proceed(); }
                finally { if (previous == null) constructing.remove(); else constructing.set(previous); }
            }));
            hooks.add(module.hook(display.getMethod("M")).setId("qj.screen.oplus.native.bind").intercept(chain -> {
                Object owner = constructing.get();
                if (owner != null && managerDisplay.get(owner) == chain.getThisObject()) {
                    if (retired(managerDevice.get(owner))) return null;
                    Request request = pending(managerDevice.get(owner));
                    if (retired(managerDevice.get(owner))) return null;
                    if (request != null && !request.bound && request.consent.approved(request.device) && "SYNERGY_PAD".equals(deviceKind.invoke(request.device))) {
                        Executor executor = action -> {
                            try { post.invoke(worker,action); }
                            catch (ReflectiveOperationException error) { throw new IllegalStateException("native_cast_worker_unavailable",error); }
                        };
                        source.bind(chain.getThisObject(),(String) managerBus.get(owner),request.scope,request.format,
                                () -> !closed && request.consent.approved(request.device),executor,listener(request));
                        request.bound = true;
                        if(request.listener instanceof ConsentListener listener) {
                            try { listener.onNativeSource(request.device,chain.getThisObject(),(String)managerBus.get(owner)); }
                            catch(RuntimeException error) { fail(request,"native_screen_source_binding_failed"); return null; }
                        }
                        if(!request.consent.approved(request.device)) return null;
                    }
                }
                return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    private static Field field(Class<?> type,String name,Class<?> expected) throws ReflectiveOperationException {
        Field result=type.getDeclaredField(name);
        if(result.getType()!=expected) throw new NoSuchFieldException("native_request_field_type_changed");
        result.setAccessible(true); return result;
    }
    private synchronized Request pending(Object device) {
        Request request=pending.get(device);
        if(request==null) return null;
        if(closed || !request.consent.current()) {
            fail(request,"native_screen_request_expired"); return null;
        }
        return request;
    }
    private synchronized boolean attachCallback(Request request, Object callback) {
        Object identity = ((IInterface) callback).asBinder();
        for (Request other : pending.values())
            if (other != request && other.callbackBinder == identity && other.consent.current()) return false;
        if (request.callback != null) return false;
        request.callback = callback; request.callbackBinder = identity; return identity != null;
    }
    private synchronized Request callbackRequest(Object callback) {
        for (Request request : pending.values())
            if (request.callback == callback && request.consent.current()) return request;
        return null;
    }
    private void fail(Request request, String reason) {
        request.consent.close();
        boolean report;
        synchronized (this) {
            request.deferredStart = null; report = pending.remove(request.device, request);
            if (report) retire(request.device);
        }
        source.stop(request.scope);
        if (report) request.listener.onFailure(reason);
    }
    private OplusScreenSourceAdapter.Listener listener(Request request) {
        return new OplusScreenSourceAdapter.Listener() {
            public boolean offer(ScreenCastPacket packet) { return request.listener.offer(packet); }
            public void onFailure(String reason) { fail(request, reason); }
            public void onPrepared(int displayId) {
                if (request.listener instanceof OplusScreenSourceAdapter.Listener l) l.onPrepared(displayId);
            }
            public void onStopped() {
                request.consent.close();
                synchronized (OplusScreenRequestAdapter.this) {
                    if (pending.remove(request.device, request)) retire(request.device);
                }
                if (request.listener instanceof OplusScreenSourceAdapter.Listener l) l.onStopped();
            }
        };
    }
    /** Register BEFORE the original request reaches CastService$j.F0. The DeviceInfo object must
     * come from that real native request, mapped to the currently authenticated peer by the caller.
     * Pairing itself grants no screen permission: the later normal OEM allow event is mandatory. */
    public synchronized void expectRequest(Object nativeDeviceInfo,OplusScreenSourceAdapter.Scope scope,
            ScreenCastFormat format,long deadlineElapsedMs,BooleanSupplier currentNativeRequest,
            ScreenCastVideoEncoder.Listener listener) throws ReflectiveOperationException {
        Objects.requireNonNull(scope); Objects.requireNonNull(format); Objects.requireNonNull(currentNativeRequest);
        Objects.requireNonNull(listener);
        for (Request old : new ArrayList<>(pending.values())) if (!old.consent.current())
            fail(old, "native_screen_request_expired");
        pruneRetired();
        long remaining=deadlineElapsedMs-SystemClock.elapsedRealtime();
        if(closed || hooks.isEmpty() || !deviceType.isInstance(nativeDeviceInfo) || pending.size()>=4
                || retired(nativeDeviceInfo) || retired.size()+pending.size()>=256
                || pending.containsKey(nativeDeviceInfo) || remaining<=0 || remaining>120_000
                || !"SYNERGY_PAD".equals(deviceKind.invoke(nativeDeviceInfo)) || !currentNativeRequest.getAsBoolean())
            throw new IllegalStateException("native_screen_request_not_expected");
        for(Request request:pending.values()) if(request.scope.equals(scope))
            throw new IllegalStateException("screen_stream_already_pending");
        pending.put(nativeDeviceInfo,new Request(nativeDeviceInfo,scope,format,deadlineElapsedMs,currentNativeRequest,listener));
    }
    public void requestKeyFrame(OplusScreenSourceAdapter.Scope scope) { source.requestKeyFrame(scope); }
    /** Keep pending native permission and capture/release lifetimes bounded even when silent. */
    public void tick() {
        List<Request> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(pending.values()); }
        for (Request request : snapshot) if (!request.consent.current()) fail(request, "native_screen_request_expired");
        source.tick();
    }
    public void cancel(OplusScreenSourceAdapter.Scope scope) {
        synchronized(this) {
            pending.entrySet().removeIf(entry -> {
                if (!entry.getValue().scope.equals(scope)) return false;
                entry.getValue().consent.close(); retire(entry.getValue().device); return true;
            });
        }
        source.stop(scope);
    }
    public void sessionChanged() {
        synchronized(this) { for (Request request : pending.values()) { request.consent.close(); retire(request.device); } pending.clear(); }
        source.sessionChanged();
    }
    private void pruneRetired() { retired.removeIf(reference -> reference.get()==null); }
    private synchronized boolean retired(Object device) {
        pruneRetired();
        for (WeakReference<Object> reference : retired) if (reference.get()==device) return true;
        return false;
    }
    private void retire(Object device) {
        if (!retired(device)) retired.add(new WeakReference<>(device));
    }
    @Override public void close() {
        closed=true; sessionChanged();
        for(HookHandle hook:hooks) hook.unhook(); hooks.clear();
        source.close();
    }
}
