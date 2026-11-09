package io.github.yuejie.connect;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Hooks only sample-backed data emission with an explicit gateway peer and native relay owner. */
final class XiaomiRelayAdapter implements AutoCloseable {
    private static final String LEGACY_BILI = XiaomiRelayCatalog.BILI_PACKAGE;
    private record GatewayCallback(Object helper, String cookie, Object value, boolean legacy) {}
    private final XposedModule module;
    private final Context context;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final AppRelaySender sender;
    private final XiaomiRelayCodec.Files files;
    private final AppRelayPeer.Resolver peers;
    private final AppRelayNativeDelegate catalogs;
    private Supplier<AppRelayPeer> selectedPeer = () -> null;
    private final Map<IBinder, GatewayCallback> gatewayCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> legacyCall = new ThreadLocal<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Method callbackConnected, callbackDisconnected, stockManager, stockConnected, unregister;
    private XiaomiRelaySource sourceRequests;
    private static final class Frame {
        final Object owner;
        android.net.Uri officeUri;
        String officeUrl;
        Frame(Object owner) { this.owner = owner; }
    }
    void sourceRequests(XiaomiRelaySource source, Supplier<AppRelayPeer> selected) {
        if (!hooks.isEmpty()) throw new IllegalStateException("relay_lifecycle");
        sourceRequests = source; selectedPeer = selected;
    }
    private final ThreadLocal<Frame> owner = new ThreadLocal<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private static final class Binding {
        final String request;
        volatile boolean cancelled;
        Binding(String request) { this.request = request; }
    }
    private final Map<Object, Binding> running = new WeakHashMap<>();
    private final ExecutorService worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8),
            r -> { Thread t = new Thread(r, "yj-xiaomi-relay-source"); t.setDaemon(true); return t; });
    private Method responseFactory, deliver;
    private volatile boolean closed;

    XiaomiRelayAdapter(XposedModule module, Context context, ClassLoader loader, BooleanSupplier allowed,
                       Supplier<JSONObject> projection, AppRelaySender sender, XiaomiRelayCodec.Files files,
                       AppRelayPeer.Resolver peers) {
        this(module, context, loader, allowed, projection, sender, files, peers, null);
    }
    XiaomiRelayAdapter(XposedModule module, Context context, ClassLoader loader, BooleanSupplier allowed,
                       Supplier<JSONObject> projection, AppRelaySender sender, XiaomiRelayCodec.Files files,
                       AppRelayPeer.Resolver peers, AppRelayNativeDelegate catalogs) {
        this.module = module; this.context = context; this.loader = loader; this.allowed = allowed;
        this.projection = projection; this.sender = sender; this.files = files; this.peers = java.util.Objects.requireNonNull(peers);
        this.catalogs = catalogs;
    }
    void install() throws ReflectiveOperationException {
        Class<?> helper = Class.forName("com.xiaomi.mirror.relay.e", false, loader);
        Class<?> event = Class.forName("com.xiaomi.mirror.message.SimpleEventMessage", false, loader);
        Class<?> message = Class.forName("com.xiaomi.mirror.message.Message", false, loader);
        Class<?> send = Class.forName("q4.A", false, loader);
        responseFactory = event.getMethod("syncAppDataToServerResponseMessage", long.class, int.class);
        deliver = helper.getMethod("D", event, boolean.class);
        try {
            if (catalogs != null && sourceRequests != null) {
                installCatalogHooks();
                installApplicationStatus(helper);
            }
            hooks.add(module.hook(helper.getDeclaredMethod("J", Bundle.class)).setId("yj.xiaomi.relay.owner").intercept(chain -> {
                Frame previous = owner.get(); owner.set(new Frame(chain.getThisObject()));
                try { return chain.proceed(); } finally { if (previous == null) owner.remove(); else owner.set(previous); }
            }));
            Class<?> fileServer = Class.forName("V3.V", false, loader);
            hooks.add(module.hook(fileServer.getMethod("I", android.net.Uri.class)).setId("yj.xiaomi.relay.office.uri").intercept(chain -> {
                Object result = chain.proceed();
                Frame frame = owner.get();
                if (frame != null && result != null) {
                    android.net.Uri uri = (android.net.Uri) chain.getArg(0);
                    if (uri != null && "content".equals(uri.getScheme())) {
                        frame.officeUri = uri;
                        frame.officeUrl = (String) result.getClass().getField("url").get(result);
                    }
                }
                return result;
            }));
            for (String method : List.of("O", "R")) {
                Method target = "O".equals(method) ? send.getMethod(method, message, String.class)
                        : send.getMethod(method, message, String.class, boolean.class);
                hooks.add(module.hook(target).setId("yj.xiaomi.relay.send." + method).intercept(chain -> {
                    Object source = chain.getArg(0);
                    Frame frame = owner.get(); Object nativeOwner = frame == null ? null : frame.owner;
                    String nativePeer = (String) chain.getArg(1);
                    JSONObject p = projection.get();
                    AppRelayPeer selected = peers.selected(nativePeer);
                    if (closed || !allowed.getAsBoolean()
                            || selected == null || !nativePeer.equals(selected.nativeId()) || !selected.matches(p))
                        return chain.proceed();
                    String sourceType = source == null ? "" : source.getClass().getName();
                    if (catalogs != null && "com.xiaomi.mirror.message.RecentAppsMessage".equals(sourceType)
                            && source.getClass().getField("action").getInt(source) == 2) {
                        catalogs.xiaomiCatalog(nativePeer, source).whenComplete((receipt, error) -> {
                            if (error != null) android.util.Log.w("YueJieRelay", "native_catalog_delivery_failed");
                        });
                        return null;
                    }
                    if (nativeOwner == null || !XiaomiRelayCodec.accepts(source)) return chain.proceed();
                    String peer = selected.projectionId(), session = selected.sessionId();
                    if (source.getClass().getSimpleName().equals("RelayOfficeMessage")
                            && !nativePeer.equals(source.getClass().getField("deviceId").get(source))) return chain.proceed();
                    android.net.Uri officeUri = source.getClass().getSimpleName().equals("RelayOfficeMessage")
                            && frame.officeUrl != null && frame.officeUrl.equals(source.getClass().getField("downloadURL").get(source))
                            ? frame.officeUri : null;
                    long nativeSession = source.getClass().getField("sessionId").getLong(source);
                    String pull = sourceRequests == null ? null : sourceRequests.claim(selected, nativeOwner, nativeSession,
                            (String) source.getClass().getField("packageName").get(source));
                    if ("".equals(pull)) return null; // Retired/duplicate native SDK reply cannot enter a new request.
                    String request = pull == null ? "xiaomi-relay-" + java.util.UUID.randomUUID() : pull;
                    Binding binding = new Binding(request);
                    synchronized (running) {
                        if (running.containsKey(nativeOwner)) return null; // Existing native session already owns its one result.
                        running.put(nativeOwner, binding);
                    }
                    try { worker.execute(() -> {
                        try {
                            AppRelayRequest relay = XiaomiRelayCodec.decode(context, source, request, files, officeUri, peer, session,
                                    () -> !closed && !binding.cancelled && (sourceRequests == null || !sourceRequests.cancelled(nativeSession))
                                            && allowed.getAsBoolean() && selected.equals(peers.selected(nativePeer)) && sameSession(peer, session),
                                    sourceRequests == null ? "" : sourceRequests.documentOwnerToken(selected, nativeSession));
                            if (closed || binding.cancelled || (sourceRequests != null && sourceRequests.cancelled(nativeSession))) {
                                complete(nativeOwner, selected, nativeSession, request,
                                        new AppRelayTasks.Result(request, AppRelayTasks.State.CANCELLED, "cancelled_before_launch", "", -1)); return;
                            }
                            sender.send(peer, session, relay).whenComplete((result, error) -> {
                                if (result != null && result.state() == AppRelayTasks.State.FAILED)
                                    ScreenCastLog.w("YueJieRelay", "native_payload_delivery_failed " + result.code());
                                complete(nativeOwner, selected, nativeSession, request, result);
                            });
                            if (binding.cancelled || (sourceRequests != null && sourceRequests.cancelled(nativeSession))) sender.cancel(request);
                        } catch (Exception invalid) {
                            Exception cause = invalid instanceof java.lang.reflect.InvocationTargetException invocation
                                    && invocation.getCause() instanceof Exception nested ? nested : invalid;
                            ScreenCastLog.w("YueJieRelay", "native_payload_rejected " + AppRelayTasks.code(cause)
                                    + " " + cause.getClass().getSimpleName());
                            complete(nativeOwner, selected, nativeSession, request, null);
                        }
                    }); } catch (java.util.concurrent.RejectedExecutionException busy) {
                        complete(nativeOwner, selected, nativeSession, request, null);
                    }
                    return null;
                }));
            }
            hooks.add(module.hook(helper.getDeclaredMethod("o", Bundle.class)).setId("yj.xiaomi.relay.cancel").intercept(chain -> {
                synchronized (running) {
                    Binding binding = running.get(chain.getThisObject());
                    if (binding != null) { binding.cancelled = true; sender.cancel(binding.request); }
                }
                return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    private void installCatalogHooks() throws ReflectiveOperationException {
        Class<?> local = Class.forName("com.xiaomi.mirror.relay.s0", false, loader);
        Class<?> message = Class.forName("com.xiaomi.mirror.message.RelayAppMessage", false, loader);
        Method enabled = Class.forName("y3.p", false, loader).getMethod("e");
        var processName = message.getField("processName");
        // Both paths build the actual OEM candidate before looking for native peers.
        // Leave its original publications alone and send a separate update for our selected peer.
        for (Method emit : List.of(local.getDeclaredMethod("R", message, String.class, int.class),
                local.getDeclaredMethod("S", message, String.class))) {
            hooks.add(module.hook(emit).setId("yj.xiaomi.relay.catalog." + emit.getName()).intercept(chain -> {
                AppRelayPeer peer = selectedPeer.get();
                Object result = chain.proceed();
                if (closed || peer == null || !peer.equals(catalogs.selected(peer.nativeId()))) return result;
                try {
                    Object source = chain.getArg(0);
                    String process = (String)processName.get(source), pkg = XiaomiRelayCatalog.packageOfProcess(process);
                    AppRelayCatalog.Candidate original = XiaomiRelayCatalog.relay(source).candidates().get(0);
                    boolean ready = original.visible() && original.kind() != AppRelayCatalog.Kind.RECENT
                            && Boolean.TRUE.equals(enabled.invoke(null)) && sourceRequests.registered(pkg);
                    var update = XiaomiRelayCatalog.relay(source, ready);
                    if (!ready && original.advertisesDataRelay()) {
                        update = new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(pkg,
                                original.label(), original.kind(), false, false, original.nativeDestinationType(), original.activity())));
                    }
                    sourceRequests.observe(peer, update, process);
                    boolean dataRelay = update.candidates().get(0).advertisesDataRelay();
                    catalogs.xiaomiCatalog(peer, update).whenComplete((receipt, error) -> {
                        if (error != null) ScreenCastLog.w("YueJieRelay", "native_catalog_delivery_failed");
                        else if (dataRelay)
                            ScreenCastLog.i("YueJieRelay", "native_app_catalog_delivered " + pkg);
                    });
                } catch (Exception invalid) { ScreenCastLog.w("YueJieRelay", "native_catalog_unavailable"); }
                return result;
            }));
        }
    }
    private void installApplicationStatus(Class<?> helper) throws ReflectiveOperationException {
        Class<?> callback = Class.forName("com.xiaomi.mirror.u", false, loader);
        Class<?> sdk = Class.forName("com.xiaomi.mirror.j0", false, loader);
        Class<?> provider = Class.forName("com.xiaomi.mirror.provider.CallProvider", false, loader);
        Method enabled = Class.forName("y3.p", false, loader).getMethod("e");
        stockManager = sdk.getMethod("m");
        stockConnected = sdk.getMethod("z");
        callbackConnected = callback.getMethod("j0");
        callbackDisconnected = callback.getMethod("M1");
        unregister = helper.getMethod("K", String.class, callback, boolean.class);
        hooks.add(module.hook(helper.getMethod("t", String.class)).setId("yj.xiaomi.relay.legacy-app").intercept(chain ->
                Boolean.TRUE.equals(legacyCall.get()) && LEGACY_BILI.equals(chain.getArg(0)) ? false : chain.proceed()));
        Class<?> local = Class.forName("com.xiaomi.mirror.relay.s0", false, loader);
        hooks.add(module.hook(local.getMethod("b")).setId("yj.xiaomi.relay.legacy-top-app").intercept(chain ->
                Boolean.TRUE.equals(legacyCall.get()) ? foregroundPackage(context) : chain.proceed()));
        hooks.add(module.hook(helper.getMethod("C", String.class, callback)).setId("yj.xiaomi.relay.app-status").intercept(chain -> {
            String cookie = (String) chain.getArg(0);
            Object value = chain.getArg(1);
            AppRelayPeer peer = selectedPeer.get();
            String pkg;
            try { pkg = XiaomiRelayCatalog.packageOfProcess(cookie); }
            catch (IllegalArgumentException invalid) { return chain.proceed(); }
            if (peer == null || !(value instanceof IInterface registered)) return chain.proceed();
            IBinder binder = registered.asBinder();
            boolean legacy = false;
            try {
                String[] callers = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
                legacy = LEGACY_BILI.equals(pkg) && callers != null && callers.length == 1 && pkg.equals(callers[0])
                        && gatewayReady(peer, enabled) && gatewayCallbacks.size() < 16
                        && !Boolean.TRUE.equals(stockConnected.invoke(stockManager.invoke(null)));
            } catch (Exception unavailable) { /* Retain the OEM registration policy. */ }
            Object result;
            if (legacy) legacyCall.set(true);
            try { result = chain.proceed(); }
            finally { if (legacy) legacyCall.remove(); }
            try {
                GatewayCallback route = new GatewayCallback(chain.getThisObject(), cookie, value, legacy);
                if (currentApplication(peer, pkg, enabled)) {
                    gatewayCallbacks.entrySet().removeIf(entry -> !entry.getValue().legacy() && !entry.getKey().isBinderAlive());
                    if (gatewayCallbacks.size() >= 16 || gatewayCallbacks.putIfAbsent(binder, route) != null) return result;
                    main.post(() -> {
                        try {
                            if (gatewayCallbacks.get(binder) != route || !currentApplication(peer, pkg, enabled) || !binder.isBinderAlive()) return;
                            if (Boolean.TRUE.equals(stockConnected.invoke(stockManager.invoke(null)))) return;
                            callbackConnected.invoke(value);
                            ScreenCastLog.i("YueJieRelay", "native_app_callback_registered " + pkg);
                        } catch (Exception unavailable) {
                            if (gatewayCallbacks.get(binder) == route) unregisterGatewayCallback(route);
                            gatewayCallbacks.remove(binder, route);
                        }
                    });
                } else unregisterGatewayCallback(route);
            } catch (Exception invalid) { /* Preserve the native registration result. */ }
            return result;
        }));
        for (String method : List.of("G", "I", "o")) {
            Method target = helper.getDeclaredMethod(method, Bundle.class);
            hooks.add(module.hook(target).setId("yj.xiaomi.relay.legacy-data." + method)
                    .intercept(chain -> {
                        Bundle data = (Bundle) chain.getArg(0);
                        AppRelayPeer peer = selectedPeer.get();
                        if (data == null || !LEGACY_BILI.equals(data.getString("relay_package_name")) || peer == null
                                || !gatewayCallbacks.values().stream().anyMatch(GatewayCallback::legacy)) return chain.proceed();
                        try { if (!currentApplication(peer, LEGACY_BILI, enabled)) return chain.proceed(); }
                        catch (ReflectiveOperationException unavailable) { return chain.proceed(); }
                        if ("G".equals(method) && !LEGACY_BILI.equals(foregroundPackage(context))) {
                            // On a cold start the registration can beat the first visible activity.
                            Object owner = chain.getThisObject();
                            Bundle offer = new Bundle(data);
                            main.postDelayed(() -> {
                                try {
                                    if (currentApplication(peer, LEGACY_BILI, enabled) && LEGACY_BILI.equals(foregroundPackage(context)))
                                        target.invoke(owner, offer);
                                } catch (ReflectiveOperationException unavailable) { }
                            }, 500);
                            return null;
                        }
                        legacyCall.set(true);
                        try { return chain.proceed(); } finally { legacyCall.remove(); }
                    }));
        }
        hooks.add(module.hook(unregister).setId("yj.xiaomi.relay.app-unregister").intercept(chain -> {
            Object value = chain.getArg(1);
            GatewayCallback route = value instanceof IInterface binder ? gatewayCallbacks.remove(binder.asBinder()) : null;
            if (route == null || !route.legacy()) return chain.proceed();
            legacyCall.set(true);
            try { return chain.proceed(); } finally { legacyCall.remove(); }
        }));
        hooks.add(module.hook(provider.getMethod("call", String.class, String.class, Bundle.class))
                .setId("yj.xiaomi.relay.app-channel").intercept(chain -> {
                    Object stock = chain.proceed();
                    if (!(stock instanceof Bundle reply) || !"isRelayEnable".equals(chain.getArg(0)) || reply.getBoolean("enable"))
                        return stock;
                    String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
                    AppRelayPeer peer = selectedPeer.get();
                    if (peer == null || packages == null || packages.length != 1) return stock;
                    try {
                        if (currentApplication(peer, packages[0], enabled)) {
                            Bundle routed = new Bundle(reply);
                            routed.putBoolean("enable", true);
                            return routed;
                        }
                    } catch (Exception unavailable) { return stock; }
                    return stock;
                }));
    }
    @SuppressWarnings("deprecation") static String foregroundPackage(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return "";
            var tasks = manager.getRunningTasks(1);
            return tasks.isEmpty() || tasks.get(0).topActivity == null ? "" : tasks.get(0).topActivity.getPackageName();
        } catch (RuntimeException unavailable) { return ""; }
    }
    private boolean gatewayReady(AppRelayPeer peer, Method enabled) throws ReflectiveOperationException {
        return !closed && peer.equals(catalogs.selected(peer.nativeId())) && Boolean.TRUE.equals(enabled.invoke(null));
    }
    private boolean currentApplication(AppRelayPeer peer, String pkg, Method enabled) throws ReflectiveOperationException {
        return gatewayReady(peer, enabled) && sourceRequests.registered(pkg);
    }
    private void unregisterGatewayCallback(GatewayCallback route) {
        if (!route.legacy()) return;
        legacyCall.set(true);
        try { unregister.invoke(route.helper(), route.cookie(), route.value(), true); }
        catch (ReflectiveOperationException unavailable) { }
        finally { legacyCall.remove(); }
    }
    private boolean sameSession(String peer, String session) {
        JSONObject p = projection.get();
        return p != null && peer.equals(p.optString("id")) && session.equals(p.optString("session_id"));
    }
    private void complete(Object nativeOwner, AppRelayPeer selected, long nativeSession, String request, AppRelayTasks.Result result) {
        try {
            Handler handler = (Handler) nativeOwner.getClass().getField("c").get(nativeOwner);
            handler.post(() -> {
                remove(nativeOwner, request);
                JSONObject p = projection.get();
                AppRelayPeer currentPeer = peers.selected(selected.nativeId());
                if (closed || !allowed.getAsBoolean() || !selected.equals(currentPeer) || !selected.matches(p)) {
                    if (sourceRequests != null) sourceRequests.complete(nativeSession, null);
                    return;
                }
                try {
                    // The OEM clears e.i as soon as its asynchronous SDK response is
                    // serialized. The claimed transaction still owns the later gateway receipt.
                    boolean claimed = sourceRequests != null && sourceRequests.ownsResponse(selected, nativeOwner, nativeSession, request);
                    if (!claimed && (nativeOwner.getClass().getField("i").getLong(nativeOwner) != nativeSession
                            || !selected.nativeId().equals(nativeOwner.getClass().getField("g").get(nativeOwner)))) {
                        if (sourceRequests != null) sourceRequests.complete(nativeSession, null);
                        return;
                    }
                    int code = result != null && result.state() == AppRelayTasks.State.COMPLETED ? 1
                            : result != null && "target_app_missing".equals(result.code()) ? -1 : 0;
                    deliver.invoke(nativeOwner, responseFactory.invoke(null, nativeSession, code), false);
                    AppRelayEvents.emit(AppRelayEvents.Event.SOURCE_NATIVE_RESULT, request, AppRelayEvents.outcome(result));
                    if (sourceRequests != null) sourceRequests.complete(nativeSession, result);
                } catch (ReflectiveOperationException ignored) {
                    if (sourceRequests != null) sourceRequests.complete(nativeSession, null);
                }
            });
        } catch (ReflectiveOperationException ignored) {
            remove(nativeOwner, request);
            if (sourceRequests != null) sourceRequests.complete(nativeSession, null);
        }
    }
    private void remove(Object nativeOwner, String request) {
        synchronized (running) { Binding binding = running.get(nativeOwner); if (binding != null && request.equals(binding.request)) running.remove(nativeOwner); }
    }
    @Override public void close() {
        closed = true;
        if (!gatewayCallbacks.isEmpty()) {
            var callbacks = Map.copyOf(gatewayCallbacks);
            for (GatewayCallback route : callbacks.values()) unregisterGatewayCallback(route);
            gatewayCallbacks.clear();
            main.post(() -> {
                try {
                    if (Boolean.TRUE.equals(stockConnected.invoke(stockManager.invoke(null)))) return;
                    for (GatewayCallback callback : callbacks.values()) {
                        try { callbackDisconnected.invoke(callback.value()); } catch (ReflectiveOperationException unavailable) { }
                    }
                } catch (ReflectiveOperationException unavailable) { }
            });
        }
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear(); worker.shutdownNow();
        synchronized (running) { for (Binding binding : running.values()) { binding.cancelled = true; sender.cancel(binding.request); } running.clear(); }
    }
}
