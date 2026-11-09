package io.github.yuejie.connect;

import android.content.Context;
import android.os.Binder;
import android.os.IInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Selected DATA peer -> native type9 consumer -> actual registered SDK type8 callback -> data. */
final class OplusRelaySource implements AutoCloseable {
    private static final class Pending {
        final AppRelayPeer peer;
        final String id, pkg;
        final CompletableFuture<AppRelayTasks.Result> result = new CompletableFuture<>();
        volatile boolean cancelled;
        int appId;
        boolean claimed;
        boolean dispatching;
        volatile boolean requested;
        OplusRelayAdapter.Submission sent;
        long catalogEpoch;
        Pending(AppRelayPeer peer, String id, String pkg) { this.peer = peer; this.id = id; this.pkg = pkg; }
    }
    private final Context context;
    private final ClassLoader loader;
    private final XposedModule module;
    private final AppRelayPeer.Resolver resolver;
    private final Supplier<AppRelayPeer> selected;
    private final BooleanSupplier allowed;
    private final Supplier<String> nativeDeviceName;
    private final AppRelayBridge bridge;
    private final OplusRelayAdapter sender;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<String, Pending> requests = new HashMap<>();
    private final Map<String, IInterface> registeredCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Long> catalogEpochs = new HashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "yj-oplus-relay-sdk"); t.setDaemon(true); return t;
            });
    private final ThreadLocal<AppRelayPeer> confirmation = new ThreadLocal<>();
    private volatile boolean closed;
    OplusRelaySource(XposedModule module, Context context, ClassLoader loader, AppRelayPeer.Resolver resolver,
                     Supplier<AppRelayPeer> selected, BooleanSupplier allowed, Supplier<String> nativeDeviceName,
                     AppRelayBridge bridge, OplusRelayAdapter sender) {
        this.module = module; this.context = context; this.loader = loader; this.resolver = resolver;
        this.selected = selected; this.allowed = allowed; this.nativeDeviceName = nativeDeviceName; this.bridge = bridge; this.sender = sender;
    }
    void install() throws Exception {
        Class<?> manager = Class.forName("com.oplus.synergy.relay.manager.RelayPayloadManager", false, loader);
        Class<?> callback = Class.forName("com.oplus.synergy.engine.ISendDataCallback", false, loader);
        Class<?> service = Class.forName("com.oplus.synergy.engine.RelayService$1", false, loader);
        var taskField = manager.getDeclaredField("mTaskType"); taskField.setAccessible(true);
        try {
            installApplicationTriggers(service);
            // The real type9 consumer emits a native hide icon on this same call stack. Forward
            // just that selected context; never hijack LinkManager.sendRelayInfo's broadcast.
            hooks.add(module.hook(manager.getMethod("writeJsonData", String.class, callback)).setId("yj.oplus.relay.confirm-hide").intercept(chain -> {
                AppRelayPeer peer = confirmation.get();
                if (peer == null) {
                    peer = selected.get();
                    if (!current(peer) || !"EVENT".equals(String.valueOf(taskField.get(chain.getThisObject())))) return chain.proceed();
                    // This boundary runs after the native consent/browser-init/icon checks.
                    // PC_EVENT, DATA and normal type8/type9 broadcasts retain their native path.
                    AppRelayCatalog.Update update;
                    try { update = OplusRelayCatalog.profiles(parse((String)chain.getArg(0))); }
                    catch (Exception unrelated) { return chain.proceed(); }
                    try {
                        sourceCatalog(update);
                        bridge.publishCatalog(peer, update).whenComplete((receipt, error) -> {
                            if (error != null) ScreenCastLog.w("YueJieRelay", "native_catalog_delivery_failed");
                        });
                    } catch (RuntimeException unavailable) { ScreenCastLog.w("YueJieRelay", "native_catalog_unavailable"); }
                    return chain.proceed();
                } else if (current(peer)) {
                    bridge.publishCatalog(peer, OplusRelayCatalog.profiles(parse((String)chain.getArg(0))));
                }
                return null;
            }));
            hooks.add(module.hook(service.getMethod("sendRelayData", int.class, callback, String.class)).setId("yj.oplus.relay.sdk-data").intercept(chain -> {
                String json = (String) chain.getArg(2);
                String pkg;
                try { pkg = OplusRelayControl.sourcePackage(json); } catch (Exception unrelated) { return chain.proceed(); }
                Pending item;
                synchronized (requests) { item = requests.get(pkg); }
                if (item == null || ((Integer)chain.getArg(0)) != 1) return chain.proceed();
                // Capture kernel caller identity before returning from the real Binder ingress.
                if (!pkg.equals(callingPackage())) return chain.proceed();
                Object nativeCallback = chain.getArg(1);
                boolean reject;
                synchronized (item) {
                    if (!item.requested && !item.cancelled && !item.result.isDone()) return chain.proceed();
                    reject = item.claimed || item.cancelled || item.result.isDone() || !current(item.peer);
                    if (!reject) item.claimed = true;
                }
                if (reject) { failNative(nativeCallback); return null; }
                AppRelayEvents.emit(AppRelayEvents.Event.SOURCE_PAYLOAD, item.id, AppRelayEvents.Outcome.PENDING);
                try { worker.execute(() -> send(item, json, nativeCallback)); }
                catch (java.util.concurrent.RejectedExecutionException full) { failNative(nativeCallback); fail(item, "relay_busy"); }
                return null;
            }));
        } catch (Exception failure) { close(); throw failure; }
    }
    private void installApplicationTriggers(Class<?> service) throws Exception {
        Class<?> receiver = Class.forName("com.oplus.synergy.engine.IReceivePayloadCallback", false, loader);
        Class<?> status = Class.forName("com.oplus.synergy.bean.RelayInfoStatus", false, loader);
        Class<?> info = Class.forName("com.oplus.synergy.bean.DataRelayInfo", false, loader);
        Object nativeList = status.getField("INSTANCE").get(null);
        Method apps = status.getMethod("getDataRelayList");
        Method name = info.getMethod("getPkgName"), type = info.getMethod("getRelayType");

        hooks.add(module.hook(service.getMethod("registerReceiveDataCallback", receiver))
                .setId("yj.oplus.relay.app-callback").intercept(chain -> {
                    String pkg = callingPackage();
                    Object callback = chain.getArg(0);
                    Object result = chain.proceed();
                    if (!closed && callback instanceof IInterface binder && nativeTriggerApp(pkg, nativeList, apps, name, type)) {
                        registeredCallbacks.put(pkg, binder);
                        ScreenCastLog.i("YueJieRelay", "native_app_callback_registered " + pkg);
                    }
                    return result;
                }));
        hooks.add(module.hook(service.getMethod("checkChannelStatus")).setId("yj.oplus.relay.app-channel").intercept(chain -> {
            Object stock = chain.proceed();
            AppRelayPeer peer = selected.get();
            String pkg = callingPackage();
            if (!Integer.valueOf(0).equals(stock) || !current(peer) || registeredCallback(pkg) == null) return stock;
            try {
                OplusRelayPermission.checkConsent(context, loader);
                return current(peer) ? 1 : stock;
            } catch (Exception unavailable) { return stock; }
        }));
        hooks.add(module.hook(service.getMethod("triggerRelay", String.class, boolean.class))
                .setId("yj.oplus.relay.app-trigger").intercept(chain -> {
                    String pkg = (String) chain.getArg(0);
                    boolean show = (Boolean) chain.getArg(1);
                    AppRelayPeer peer = selected.get();
                    boolean ownsTrigger = pkg != null && pkg.equals(callingPackage()) && current(peer)
                            && registeredCallback(pkg) != null;
                    Object result = chain.proceed();
                    if (ownsTrigger) {
                        try { worker.execute(() -> publishApplication(peer, pkg, show)); }
                        catch (java.util.concurrent.RejectedExecutionException busy) {
                            ScreenCastLog.w("YueJieRelay", "native_app_catalog_busy");
                        }
                    }
                    return result;
                }));
    }
    private String callingPackage() {
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        return packages != null && packages.length == 1 ? packages[0] : "";
    }
    private static boolean nativeTriggerApp(String pkg, Object list, Method apps, Method name, Method type) {
        if (pkg.isEmpty()) return false;
        try {
            for (Object app : (List<?>) apps.invoke(list))
                if (pkg.equals(name.invoke(app)) && "trigger_app_name".equals(type.invoke(app))) return true;
        } catch (Exception unavailable) { return false; }
        return false;
    }
    private IInterface registeredCallback(String pkg) {
        IInterface callback = registeredCallbacks.get(pkg);
        if (callback == null || callback.asBinder().isBinderAlive()) return callback;
        registeredCallbacks.remove(pkg, callback);
        return null;
    }
    private void publishApplication(AppRelayPeer peer, String pkg, boolean show) {
        try {
            if (!current(peer)) return;
            String label = "";
            if (show) {
                OplusRelayPermission.check(context, loader, pkg);
                var pm = context.getPackageManager();
                label = AppRelayCatalog.text(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), 256);
                if (!current(peer) || registeredCallback(pkg) == null) return;
            }
            AppRelayCatalog.Update update = new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(
                    pkg, label, AppRelayCatalog.Kind.APP, show, show, 1, "")));
            sourceCatalog(update);
            bridge.publishCatalog(peer, update).whenComplete((receipt, failure) -> {
                ScreenCastLog.i("YueJieRelay", failure == null ? "native_app_catalog_delivered " + pkg : "native_app_catalog_failed");
            });
        } catch (Exception unavailable) { ScreenCastLog.w("YueJieRelay", "native_app_catalog_rejected " + AppRelayTasks.code(unavailable)); }
    }
    private List<?> parse(String json) throws Exception {
        return (List<?>) Class.forName("com.oplus.synergy.utils.RelayAbilityUtil", false, loader)
                .getMethod("parseJsonStr", String.class).invoke(null, json);
    }
    private void sourceCatalog(AppRelayCatalog.Update update) {
        synchronized (requests) {
            for (var candidate : update.candidates()) if (candidate.eligible(true, true)) {
                if (!catalogEpochs.containsKey(candidate.packageName()) && catalogEpochs.size() >= 256) throw new IllegalStateException("relay_busy");
                catalogEpochs.merge(candidate.packageName(), 1L, Long::sum);
            }
        }
    }
    AppRelaySync.Native forPeer(AppRelayPeer peer) {
        return new AppRelaySync.Native() {
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { return start(peer, id, pkg); }
            public void cancel(String id) { OplusRelaySource.this.cancel(peer, id); }
        };
    }
    private CompletableFuture<AppRelayTasks.Result> start(AppRelayPeer peer, String id, String pkg) {
        Pending item = new Pending(peer, id, pkg);
        synchronized (requests) {
            Pending previous = requests.get(pkg);
            AppRelayTasks.Result completed = previous == null ? null : previous.result.getNow(null);
            item.catalogEpoch = catalogEpochs.getOrDefault(pkg, 0L);
            if (closed || requests.size() >= 128 || (previous != null && (completed == null || completed.state() != AppRelayTasks.State.COMPLETED
                    || item.catalogEpoch <= previous.catalogEpoch)))
                return CompletableFuture.failedFuture(new IllegalStateException("native_source_already_requested"));
            requests.put(pkg, item);
        }
        try { worker.execute(() -> {
            try {
                if (!current(peer) || item.cancelled) { fail(item, "cancelled_before_launch"); return; }
                OplusRelayPermission.check(context, loader, pkg);
                Class<?> ability = Class.forName("com.oplus.pantaconnect.sdk.connectionservice.ability.Ability", false, loader);
                Object query = ability.getMethod("getAppIdByPackageName", String.class).invoke(ability.getMethod("create").invoke(null), pkg);
                if (!(query instanceof CompletableFuture<?> future)) throw new IllegalStateException("ability_query_failed");
                Object value;
                try { value = future.get(2000, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException timeout) { future.cancel(true); throw new IllegalStateException("ability_query_timeout"); }
                if (!(value instanceof Integer appId) || appId <= 0 || appId > 65535) throw new IllegalStateException("invalid_native_app_id");
                item.appId = appId;
                Object nativeCallback = registeredCallback(pkg);
                if (nativeCallback == null) {
                    Class<?> linkClass = Class.forName("com.oplus.synergy.linkmanager.LinkManager", false, loader);
                    Object link = linkClass.getMethod("getInstance").invoke(null);
                    var field = linkClass.getDeclaredField("mIReceiveDataCallbacks"); field.setAccessible(true);
                    if (field.get(link) instanceof ConcurrentHashMap<?, ?> callbacks) nativeCallback = callbacks.get(appId);
                }
                if (nativeCallback == null) throw new IllegalStateException("native_source_unavailable");
                if (!current(peer) || item.cancelled) { fail(item, "cancelled_before_launch"); return; }
                Class<?> task = Class.forName("com.oplus.synergy.utils.TaskType", false, loader);
                Object event = task.getField("EVENT").get(null);
                Object confirm = Class.forName("com.oplus.synergy.relay.manager.runnable.RelayEventReadRunnable", false, loader)
                        .getConstructor(byte[].class, ConcurrentHashMap.class, task)
                        .newInstance(OplusRelayControl.confirmation(nativeDeviceName.get()), null, event);
                confirmation.set(peer);
                try { ((Runnable)confirm).run(); } finally { confirmation.remove(); }
                if (!current(peer) || item.cancelled) { fail(item, "cancelled_before_launch"); return; }
                item.requested = true;
                Class.forName("com.oplus.synergy.engine.IReceivePayloadCallback", false, loader)
                        .getMethod("onRelayPayloadReceived", String.class, int.class)
                        .invoke(nativeCallback, OplusRelayControl.request(pkg, appId, nativeDeviceName.get()), 8);
            } catch (Exception failure) { fail(item, AppRelayTasks.code(failure)); }
        }); } catch (java.util.concurrent.RejectedExecutionException full) { fail(item, "relay_busy"); }
        return item.result;
    }
    private void send(Pending item, String json, Object callback) {
        try {
            if (!current(item.peer) || item.cancelled) { failNative(callback); fail(item, "cancelled_before_launch"); return; }
            byte[] payload = OplusRelayControl.sdkData(json, item.pkg, item.appId);
            boolean cancelled;
            synchronized (item) {
                cancelled = item.cancelled;
                if (!cancelled) item.dispatching = true;
            }
            if (cancelled) { failNative(callback); fail(item, "cancelled_before_launch"); return; }
            OplusRelayAdapter.Submission sent = sender.send(item.peer, payload, callback, () -> current(item.peer), () -> item.cancelled);
            AppRelayEvents.linked(item.id, sent.requestId());
            synchronized (item) { item.sent = sent; item.dispatching = false; }
            sent.result().whenComplete((result, error) -> {
                if (error != null || result == null) fail(item, "native_source_failed");
                else {
                    synchronized (requests) { item.catalogEpoch = catalogEpochs.getOrDefault(item.pkg, 0L); }
                    item.result.complete(new AppRelayTasks.Result(item.id, result.state(), result.code(), result.targetPackage(), result.taskId()));
                }
            });
            if (item.cancelled) sender.cancel(sent.requestId());
        } catch (Exception error) { failNative(callback); fail(item, AppRelayTasks.code(error)); }
    }
    private void cancel(AppRelayPeer peer, String id) {
        List<Pending> copy;
        synchronized (requests) { copy = List.copyOf(requests.values()); }
        for (Pending item : copy) if (item.peer.equals(peer) && item.id.equals(id)) {
            OplusRelayAdapter.Submission sent; boolean beforeDispatch;
            synchronized (item) { item.cancelled = true; sent = item.sent; beforeDispatch = !item.dispatching && sent == null; }
            if (sent != null) sender.cancel(sent.requestId()); else if (beforeDispatch) fail(item, "cancelled_before_launch");
        }
    }
    private void failNative(Object callback) {
        if (callback != null) try { Class.forName("com.oplus.synergy.engine.ISendDataCallback", false, loader)
                .getMethod("onSendRelayDataFailed", int.class).invoke(callback, -1); }
        catch (Exception ignored) { /* Dead native callback cannot produce success. */ }
    }
    private static void fail(Pending item, String code) {
        ScreenCastLog.w("YueJieRelay", "native_app_payload_failed " + code);
        item.result.complete(new AppRelayTasks.Result(item.id, "cancelled_before_launch".equals(code)
                ? AppRelayTasks.State.CANCELLED : AppRelayTasks.State.FAILED, code, "", -1));
    }
    private boolean current(AppRelayPeer peer) { return peer != null && !closed && allowed.getAsBoolean() && peer.equals(resolver.selected(peer.nativeId())); }
    @Override public void close() {
        closed = true;
        registeredCallbacks.clear();
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        List<Pending> copy; synchronized (requests) { copy = List.copyOf(requests.values()); }
        for (Pending item : copy) { cancel(item.peer, item.id); fail(item, "session_revoked"); }
        worker.shutdown();
    }
}
