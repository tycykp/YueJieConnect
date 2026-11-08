package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Binder;
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
    private final Map<String, Long> catalogEpochs = new HashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "qj-oplus-relay-sdk"); t.setDaemon(true); return t;
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
            // The real type9 consumer emits a native hide icon on this same call stack. Forward
            // just that selected context; never hijack LinkManager.sendRelayInfo's broadcast.
            hooks.add(module.hook(manager.getMethod("writeJsonData", String.class, callback)).setId("qj.oplus.relay.confirm-hide").intercept(chain -> {
                AppRelayPeer peer = confirmation.get();
                if (peer == null) {
                    peer = selected.get();
                    if (!current(peer) || !"EVENT".equals(String.valueOf(taskField.get(chain.getThisObject())))) return chain.proceed();
                    // This boundary runs after the native consent/browser-init/icon checks.
                    // PC_EVENT, DATA and normal type8/type9 broadcasts retain their native path.
                    AppRelayCatalog.Update update;
                    try { update = OplusRelayCatalog.profiles(parse((String)chain.getArg(0))); }
                    catch (Exception unrelated) { return chain.proceed(); }
                    sourceCatalog(peer, update); bridge.publishCatalog(peer, update);
                } else if (current(peer)) {
                    bridge.publishCatalog(peer, OplusRelayCatalog.profiles(parse((String)chain.getArg(0))));
                }
                return null;
            }));
            hooks.add(module.hook(service.getMethod("sendRelayData", int.class, callback, String.class)).setId("qj.oplus.relay.sdk-data").intercept(chain -> {
                String json = (String) chain.getArg(2);
                String pkg;
                try { pkg = OplusRelayControl.sourcePackage(json); } catch (Exception unrelated) { return chain.proceed(); }
                Pending item;
                synchronized (requests) { item = requests.get(pkg); }
                if (item == null || ((Integer)chain.getArg(0)) != 1) return chain.proceed();
                // Capture kernel caller identity before returning from the real Binder ingress.
                String[] callerPackages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
                if (callerPackages == null || callerPackages.length != 1 || !pkg.equals(callerPackages[0])) return chain.proceed();
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
    private List<?> parse(String json) throws Exception {
        return (List<?>) Class.forName("com.oplus.synergy.utils.RelayAbilityUtil", false, loader)
                .getMethod("parseJsonStr", String.class).invoke(null, json);
    }
    private void sourceCatalog(AppRelayPeer peer, AppRelayCatalog.Update update) {
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
                Class<?> linkClass = Class.forName("com.oplus.synergy.linkmanager.LinkManager", false, loader);
                Object link = linkClass.getMethod("getInstance").invoke(null);
                if (!Boolean.TRUE.equals(linkClass.getMethod("getONetInitFlag").invoke(link))) throw new IllegalStateException("native_peer_unavailable");
                var field = linkClass.getDeclaredField("mIReceiveDataCallbacks"); field.setAccessible(true);
                if (!(field.get(link) instanceof ConcurrentHashMap<?, ?> callbacks)) throw new IllegalStateException("native_source_unavailable");
                Object nativeCallback = callbacks.get(appId);
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
        item.result.complete(new AppRelayTasks.Result(item.id, "cancelled_before_launch".equals(code)
                ? AppRelayTasks.State.CANCELLED : AppRelayTasks.State.FAILED, code, "", -1));
    }
    private boolean current(AppRelayPeer peer) { return peer != null && !closed && allowed.getAsBoolean() && peer.equals(resolver.selected(peer.nativeId())); }
    @Override public void close() {
        closed = true;
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        List<Pending> copy; synchronized (requests) { copy = List.copyOf(requests.values()); }
        for (Pending item : copy) { cancel(item.peer, item.id); fail(item, "session_revoked"); }
        worker.shutdown();
    }
}
