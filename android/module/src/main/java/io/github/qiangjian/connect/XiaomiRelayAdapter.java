package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
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
    private final XposedModule module;
    private final Context context;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final AppRelaySender sender;
    private final XiaomiRelayCodec.Files files;
    private final AppRelayPeer.Resolver peers;
    private final AppRelayNativeDelegate catalogs;
    private XiaomiRelaySource sourceRequests;
    private static final class Frame {
        final Object owner;
        android.net.Uri officeUri;
        String officeUrl;
        Frame(Object owner) { this.owner = owner; }
    }
    void sourceRequests(XiaomiRelaySource source) { if (!hooks.isEmpty()) throw new IllegalStateException("relay_lifecycle"); sourceRequests = source; }
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
            r -> { Thread t = new Thread(r, "qj-xiaomi-relay-source"); t.setDaemon(true); return t; });
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
            hooks.add(module.hook(helper.getDeclaredMethod("J", Bundle.class)).setId("qj.xiaomi.relay.owner").intercept(chain -> {
                Frame previous = owner.get(); owner.set(new Frame(chain.getThisObject()));
                try { return chain.proceed(); } finally { if (previous == null) owner.remove(); else owner.set(previous); }
            }));
            Class<?> fileServer = Class.forName("V3.V", false, loader);
            hooks.add(module.hook(fileServer.getMethod("I", android.net.Uri.class)).setId("qj.xiaomi.relay.office.uri").intercept(chain -> {
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
                hooks.add(module.hook(target).setId("qj.xiaomi.relay.send." + method).intercept(chain -> {
                    Object source = chain.getArg(0);
                    Frame frame = owner.get(); Object nativeOwner = frame == null ? null : frame.owner;
                    String nativePeer = (String) chain.getArg(1);
                    JSONObject p = projection.get();
                    AppRelayPeer selected = peers.selected(nativePeer);
                    if (closed || !allowed.getAsBoolean()
                            || selected == null || !nativePeer.equals(selected.nativeId()) || !selected.matches(p))
                        return chain.proceed();
                    String sourceType = source == null ? "" : source.getClass().getName();
                    if (catalogs != null && ("com.xiaomi.mirror.message.RelayAppMessage".equals(sourceType)
                            || ("com.xiaomi.mirror.message.RecentAppsMessage".equals(sourceType) && source.getClass().getField("action").getInt(source) == 2))) {
                        if (sourceRequests != null) sourceRequests.observe(selected, source);
                        catalogs.xiaomiCatalog(nativePeer, source).whenComplete((receipt, error) -> {
                            if (error != null) android.util.Log.w("QiangJianRelay", "native_catalog_delivery_failed");
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
                            sender.send(peer, session, relay).whenComplete((result, error) -> complete(nativeOwner, selected, nativeSession, request, result));
                            if (binding.cancelled || (sourceRequests != null && sourceRequests.cancelled(nativeSession))) sender.cancel(request);
                        } catch (Exception invalid) { complete(nativeOwner, selected, nativeSession, request, null); }
                    }); } catch (java.util.concurrent.RejectedExecutionException busy) {
                        complete(nativeOwner, selected, nativeSession, request, null);
                    }
                    return null;
                }));
            }
            hooks.add(module.hook(helper.getDeclaredMethod("o", Bundle.class)).setId("qj.xiaomi.relay.cancel").intercept(chain -> {
                synchronized (running) {
                    Binding binding = running.get(chain.getThisObject());
                    if (binding != null) { binding.cancelled = true; sender.cancel(binding.request); }
                }
                return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
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
                    if (nativeOwner.getClass().getField("i").getLong(nativeOwner) != nativeSession
                            || !selected.nativeId().equals(nativeOwner.getClass().getField("g").get(nativeOwner))) {
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
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear(); worker.shutdownNow();
        synchronized (running) { for (Binding binding : running.values()) { binding.cancelled = true; sender.cancel(binding.request); } running.clear(); }
    }
}
