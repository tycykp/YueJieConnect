package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** ColorOS 17.7.30 SDK boundary. A profile response is never a native connection ack. */
final class OplusProtocolAdapter {
    private static final String BASE = "com.oplus.pantaconnect.sdk.connectionservice.connection.";
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> handles = new ArrayList<>();
    private final List<Discovery> discoveries = new ArrayList<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final OplusPeerRegistry registry = new OplusPeerRegistry();
    private volatile boolean closed;
    private volatile Published published;
    private Method found, lost, failure, idGetter, tempIdGetter, roleGetter, resultCode;
    private Method optionRoles, optionDuration;
    private Constructor<?> deviceConstructor;
    private Class<?> deviceType;
    private volatile JSONObject currentProjection;
    private OplusDeviceKitAdapter deviceKit;
    private OplusRuntimeProbe probe;

    private record Published(String id, String session, String fingerprint, Object device) {}
    private record Pending(OplusPeerRegistry.Ticket ticket, Object device, Object receiver, Runnable timeout) {}
    private static final class Discovery {
        final WeakReference<Object> owner, receiver;
        final Set<?> roles;
        final long deadline;
        Object emitted;
        Discovery(Object owner, Object receiver, Set<?> roles, long deadline) {
            this.owner = new WeakReference<>(owner);
            this.receiver = new WeakReference<>(receiver);
            this.roles = Set.copyOf(roles);
            this.deadline = deadline;
        }
    }

    OplusProtocolAdapter(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed) {
        this.module = module;
        this.client = client;
        this.loader = loader;
        this.allowed = allowed;
    }

    void install() throws ReflectiveOperationException {
        try {
            resolve();
            installDiscovery();
            installConnections();
            deviceKit = new OplusDeviceKitAdapter(module, client, loader, allowed, () -> currentProjection);
            deviceKit.install();
            probe = new OplusRuntimeProbe(module, loader, allowed, () -> currentProjection);
            probe.install();
            client.onProjection(value -> main.post(() -> update(value)));
            main.post(() -> update(client.projection()));
            log("oplus_adapter_installed");
        } catch (ReflectiveOperationException | RuntimeException error) {
            close();
            throw error;
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

    private void resolve() throws ReflectiveOperationException {
        deviceType = type(BASE + "DisplayDevice");
        idGetter = deviceType.getMethod("getProtocolDeviceId");
        tempIdGetter = deviceType.getMethod("getTempId");
        roleGetter = deviceType.getMethod("getDeviceType");
        Class<?> discovery = type(BASE + "GlobalDeviceDiscoveryCallback");
        found = discovery.getMethod("onDeviceFound", deviceType);
        lost = discovery.getMethod("onDeviceLost", deviceType);
        failure = type(BASE + "GlobalDeviceConnectionCallback").getMethod("onConnectionFailure", deviceType, Throwable.class);
        resultCode = type("com.oplus.pantaconnect.sdk.SealedResult").getMethod("getResultCodeValue");
        Class<?> options = type(BASE + "DeviceDiscoveryOptions");
        optionRoles = options.getMethod("getDeviceTypes");
        optionDuration = options.getMethod("getDurationMillis");
        deviceConstructor = deviceType.getConstructor(String.class, String.class,
                type("com.oplus.pantaconnect.sdk.DeviceType"), String.class, type(BASE + "DeviceState"),
                type(BASE + "AccountState"), boolean.class, int.class, List.class, int.class, String.class,
                type("com.oplus.pantaconnect.sdk.discovery.DiscoveryStrategy"), String.class,
                type("com.oplus.pantaconnect.sdk.connection.VLinkType"), String.class);
    }

    private void installDiscovery() throws ReflectiveOperationException {
        Class<?> clients = type(BASE + "ConnectionServiceClientsImpl");
        handles.add(module.hook(clients.getMethod("startGlobalDeviceDiscovery", type(BASE + "DeviceDiscoveryOptions"),
                type(BASE + "GlobalDeviceDiscoveryCallback"))).setId("qj.oplus.discovery.start").intercept(chain -> {
            Object result = chain.proceed();
            if (!closed && allowed.getAsBoolean() && Integer.valueOf(1).equals(resultCode.invoke(result))) {
                Object owner = chain.getThisObject(), receiver = chain.getArg(1), options = chain.getArg(0);
                Set<?> roles = Set.copyOf((Set<?>) optionRoles.invoke(options));
                long duration = (Long) optionDuration.invoke(options);
                main.post(() -> register(owner, receiver, roles, duration));
            }
            return result;
        }));
        handles.add(module.hook(clients.getMethod("stopGlobalDeviceDiscovery")).setId("qj.oplus.discovery.stop").intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            if (Integer.valueOf(1).equals(resultCode.invoke(result))) main.post(() -> removeDiscovery(owner));
            return result;
        }));
        for (String method : new String[]{"getAvailableDevices", "getDisplayDeviceList"}) {
            handles.add(module.hook(clients.getMethod(method)).setId("qj.oplus.discovery." + method).intercept(chain -> {
                Object original = chain.proceed();
                if (!(original instanceof CompletableFuture<?> future)) return original;
                return future.thenApply(value -> {
                    Published current = published;
                    if (closed || !allowed.getAsBoolean() || current == null || !(value instanceof List<?> list)) return value;
                    List<Object> copy = new ArrayList<>(list);
                    for (Object item : list) if (current.id().equals(id(item))) return value;
                    copy.add(current.device());
                    log("oplus_projection_list_added");
                    return copy;
                });
            }));
        }
    }

    private void installConnections() throws ReflectiveOperationException {
        Class<?> clients = type(BASE + "ConnectionServiceClientsImpl"), service = type(BASE + "ConnectionServiceImpl");
        Class<?> options = type(BASE + "GlobalDeviceConnectOptions"), callback = type(BASE + "GlobalDeviceConnectionCallback");
        handles.add(module.hook(clients.getMethod("requestConnection", deviceType, options, callback))
                .setId("qj.oplus.connection.request").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if (!registry.owns(target)) return chain.proceed();
                    Object receiver = chain.getArg(2);
                    main.post(() -> {
                        if (!matchesSession(device)) fail(receiver, device, "stale_session");
                        else request(target, device, receiver);
                    });
                    return null;
                }));
        // Hook the async SDK wrapper used by BOTH DisplayDevice and ConnectionImpl. ClientsImpl
        // returns SealedResult here, so returning a CompletableFuture from that method would crash.
        handles.add(module.hook(service.getMethod("cancelConnection", deviceType))
                .setId("qj.oplus.connection.cancel").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(device)) return CompletableFuture.failedFuture(new IllegalStateException("QJ:stale_session"));
                    CompletableFuture<Boolean> result = new CompletableFuture<>();
                    main.post(() -> {
                        boolean existed = pending.values().stream().anyMatch(item -> item.ticket().id().equals(target));
                        cancel(target, "cancelled"); result.complete(existed);
                    });
                    // No native connection is opened by this profile-only version. This acknowledges
                    // cancellation of this adapter's pending local request, not remote business.
                    return result;
                }));
        handles.add(module.hook(service.getMethod("closeConnection", deviceType, options))
                .setId("qj.oplus.connection.close").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(device)) return CompletableFuture.failedFuture(new IllegalStateException("QJ:stale_session"));
                    main.post(() -> cancel(target, "connection_closed"));
                    return CompletableFuture.completedFuture(false); // No native connection exists.
                }));
        handles.add(module.hook(clients.getMethod("confirmConnection", deviceType, type(BASE + "PairAction"), type(BASE + "ConfirmType")))
                .setId("qj.oplus.connection.confirm").intercept(chain -> {
                    String target = id(chain.getArg(0));
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(chain.getArg(0))) { log("oplus_stale_confirmation_ignored"); return null; }
                    String action = ((Enum<?>) chain.getArg(1)).name();
                    main.post(() -> cancel(target, "REJECT".equals(action) ? "permission_denied" : "native_auth_unavailable"));
                    return null;
                }));
    }

    private void request(String target, Object device, Object receiver) {
        if (closed || !allowed.getAsBoolean() || !registry.owns(target)) {
            fail(receiver, device, "peer_offline"); return;
        }
        final OplusPeerRegistry.Ticket ticket;
        try { ticket = registry.begin(target); }
        catch (IllegalStateException error) { fail(receiver, device, error.getMessage()); return; }
        Runnable timeout = () -> complete(ticket, "timeout");
        pending.put(ticket.number(), new Pending(ticket, device, receiver, timeout));
        main.postDelayed(timeout, 10_000);
        client.queryRealPeer(target, response -> {
            String error = response == null ? "peer_offline" : response.optString("code", "remote_error");
            if (response != null && "ok".equals(response.optString("status"))) {
                log("oplus_remote_profile_verified_native_business_unavailable");
                error = "native_business_unavailable";
            }
            complete(ticket, error);
        });
    }

    private void complete(OplusPeerRegistry.Ticket ticket, String error) {
        Pending item = pending.remove(ticket.number());
        if (item == null) return;
        main.removeCallbacks(item.timeout());
        if (!registry.finish(ticket)) return; // Cancelled/withdrawn request cannot be resurrected.
        fail(item.receiver(), item.device(), error);
    }

    private void cancel(String target, String reason) {
        for (Pending item : new ArrayList<>(pending.values())) {
            if (item.ticket().id().equals(target)) complete(item.ticket(), reason);
        }
        registry.cancel(target);
    }

    private void register(Object owner, Object receiver, Set<?> roles, long duration) {
        if (closed || !allowed.getAsBoolean()) return;
        discoveries.removeIf(item -> item.owner.get() == null || item.receiver.get() == null);
        // A ConnectionServiceClientsImpl has one global discovery registration.
        removeDiscovery(owner);
        if (discoveries.size() >= 32) { log("oplus_discovery_limit"); return; }
        long deadline = duration > 0 ? SystemClock.elapsedRealtime() + Math.min(duration, 86_400_000L) : Long.MAX_VALUE;
        Discovery discovery = new Discovery(owner, receiver, roles, deadline);
        discoveries.add(discovery);
        publish(discovery);
        if (duration > 0) main.postDelayed(() -> {
            withdraw(discovery);
            discoveries.remove(discovery);
        }, Math.min(duration, 86_400_000L));
    }

    private void removeDiscovery(Object owner) {
        discoveries.removeIf(item -> {
            if (item.owner.get() != owner && item.owner.get() != null && item.receiver.get() != null) return false;
            withdraw(item); return true;
        });
    }

    private void update(JSONObject projection) {
        if (closed || !allowed.getAsBoolean()) projection = null;
        Published next = null;
        if (projection != null) try {
            if (!"color_os17".equals(projection.getString("target_sdk"))) throw new JSONException("wrong target");
            JSONObject real = projection.getJSONObject("real");
            if (!"hyper_os4".equals(real.getString("platform"))) throw new JSONException("wrong peer");
            String target = projection.getString("id");
            if (!target.matches("qj:[0-9a-f]{64}")) throw new JSONException("invalid alias");
            String session = projection.getString("session_id");
            if (!session.matches("[0-9a-f]{64}")) throw new JSONException("invalid session");
            next = new Published(target, session, projection.toString(), build(projection));
        } catch (ReflectiveOperationException | JSONException | RuntimeException error) {
            log("oplus_projection_rejected " + error.getClass().getSimpleName());
        }
        Published old = published;
        if (old == null && next == null) return;
        if (old != null && next != null && old.fingerprint().equals(next.fingerprint())) return;
        if (old != null && (next == null || !old.id().equals(next.id()) || !old.session().equals(next.session())))
            cancel(old.id(), "peer_offline");
        for (Discovery discovery : discoveries) withdraw(discovery);
        registry.update(next == null ? null : next.id(), next == null ? null : next.session());
        published = next;
        currentProjection = next == null ? null : projection;
        if (deviceKit != null) deviceKit.sessionChanged();
        for (Discovery discovery : discoveries) publish(discovery);
    }

    private void publish(Discovery discovery) {
        Published current = published;
        Object receiver = discovery.receiver.get();
        if (current == null || receiver == null || SystemClock.elapsedRealtime() >= discovery.deadline) return;
        try {
            Object role = roleGetter.invoke(current.device());
            if (!discovery.roles.isEmpty() && !discovery.roles.contains(role)) return;
            if (notify(receiver, found, current.device())) discovery.emitted = current.device();
        } catch (ReflectiveOperationException error) { log("oplus_role_mapping_failed"); }
    }

    private void withdraw(Discovery discovery) {
        Object old = discovery.emitted;
        discovery.emitted = null;
        if (old != null && discovery.receiver.get() != null) notify(discovery.receiver.get(), lost, old);
    }

    private Object build(JSONObject projection) throws ReflectiveOperationException, JSONException {
        JSONObject real = projection.getJSONObject("real");
        String kind = real.getString("kind");
        if (!kind.equals("tablet") && !kind.equals("phone")) throw new JSONException("invalid kind");
        if (projection.getInt("sdk_device_type") != (kind.equals("tablet") ? 10 : 8)) throw new JSONException("invalid role");
        String target = projection.getString("id");
        return deviceConstructor.newInstance(target, "qj-session:" + projection.getString("session_id"), enumeration("com.oplus.pantaconnect.sdk.DeviceType", kind.equals("tablet") ? "PAD" : "PHONE"),
                real.getString("name"), enumeration(BASE + "DeviceState", "DISCOVERED"), enumeration(BASE + "AccountState", "NO_ACCOUNT"),
                false, 0, Collections.emptyList(), 0, real.getString("model"), enumeration("com.oplus.pantaconnect.sdk.discovery.DiscoveryStrategy", "LAN"),
                "", enumeration("com.oplus.pantaconnect.sdk.connection.VLinkType", "WLAN"), "");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumeration(String type, String name) throws ClassNotFoundException { return Enum.valueOf((Class) type(type), name); }
    private String id(Object device) {
        try { return (String) idGetter.invoke(device); }
        catch (ReflectiveOperationException | RuntimeException error) { return null; }
    }
    private boolean matchesSession(Object device) {
        Published current = published;
        JSONObject live = client.projection();
        if (current == null || live == null || !current.id().equals(live.optString("id"))
                || !current.session().equals(live.optString("session_id"))) return false;
        try { return ("qj-session:" + current.session()).equals(tempIdGetter.invoke(device)); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    private boolean notify(Object receiver, Method method, Object... args) {
        try { method.invoke(receiver, args); return true; }
        catch (ReflectiveOperationException | RuntimeException error) { log("oplus_callback_failed " + method.getName()); return false; }
    }
    private void fail(Object receiver, Object device, String error) {
        notify(receiver, failure, device, new IllegalStateException("QJ:" + error));
        log("oplus_request_failed " + error);
    }
    private void log(String event) { module.log(Log.INFO, "QiangJian", event + " source=2"); }

    void close() {
        if (closed) return;
        closed = true;
        if (deviceKit != null) deviceKit.close();
        if (probe != null) probe.close();
        client.onProjection(null);
        for (HookHandle handle : handles) handle.unhook();
        handles.clear();
        main.post(() -> {
            Published current = published;
            if (current != null) cancel(current.id(), "adapter_closed");
            for (Discovery discovery : discoveries) withdraw(discovery);
            discoveries.clear();
            published = null;
            currentProjection = null;
            registry.update(null, null);
            main.removeCallbacksAndMessages(null);
        });
    }
}
