package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native DeviceKit query -> authenticated remote battery read -> native property callback. */
final class OplusDeviceKitAdapter {
    private static final String KIT = "com.oplus.synergy.devicekit.";
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> selected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ConcurrentHashMap<String, Query> queries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private Class<?> callbackType, infoType;
    private Constructor<?> property;
    private Method onResult, onError, deviceId;

    private static final class Query {
        final String id, session, request;
        final JSONObject projection;
        final Object callback;
        final List<?> original;
        final boolean onlyProxy;
        final AtomicBoolean done = new AtomicBoolean();
        Runnable timeout;
        Query(JSONObject projection, Object callback, String request, List<?> original, boolean onlyProxy) {
            this.projection = projection;
            this.id = projection.optString("id"); this.session = projection.optString("session_id");
            this.callback = callback; this.request = request;
            this.original = original; this.onlyProxy = onlyProxy;
        }
    }

    OplusDeviceKitAdapter(XposedModule module, BridgeClient client, ClassLoader loader,
                         BooleanSupplier allowed, Supplier<JSONObject> selected) {
        this.module = module; this.client = client; this.loader = loader;
        this.allowed = allowed; this.selected = selected;
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

    void install() throws ReflectiveOperationException {
        callbackType = type(KIT + "callback.ISynergyDeviceInfoCallback");
        infoType = type(KIT + "bean.SynergyDeviceInfo");
        property = type(KIT + "bean.DeviceProperty").getConstructor(String.class, String.class);
        onResult = callbackType.getMethod("onResult", List.class);
        onError = callbackType.getMethod("onError", int.class, String.class);
        deviceId = infoType.getMethod("getDeviceId");
        infoType.getConstructor();
        for (String name : new String[]{"setDeviceId", "setName", "setModelId"}) infoType.getMethod(name, String.class);
        for (String name : new String[]{"setDeviceType", "setDeviceState", "setDeviceProtocol"}) infoType.getMethod(name, int.class);
        infoType.getMethod("setPropertyList", ArrayList.class);
        Class<?> owner = type(KIT + "OpDeviceServiceClient");
        hooks.add(module.hook(owner.getMethod("queryOriginalDeviceListWithIds", List.class, callbackType))
                .setId("qj.oplus.devicekit.ids").intercept(chain -> {
                    JSONObject p = active();
                    List<?> ids = (List<?>) chain.getArg(0);
                    if (p == null || !ids.contains(p.optString("id"))) return chain.proceed();
                    Object callback = chain.getArg(1);
                    if (ids.stream().allMatch(p.optString("id")::equals)) {
                        main.post(() -> read(p, callback, List.of(), true));
                        return null;
                    }
                    return chain.proceed(new Object[]{ids, wrap(p, callback)});
                }));
        hooks.add(module.hook(owner.getMethod("queryOriginalDeviceList", int.class, callbackType))
                .setId("qj.oplus.devicekit.type").intercept(chain -> {
                    JSONObject p = active();
                    int requested = (Integer) chain.getArg(0);
                    if (p == null || requested != p.optInt("sdk_device_type")) return chain.proceed();
                    return chain.proceed(new Object[]{requested, wrap(p, chain.getArg(1))});
                }));
        hooks.add(module.hook(owner.getMethod("queryDeviceListByTypes", List.class, callbackType))
                .setId("qj.oplus.devicekit.types").intercept(chain -> {
                    JSONObject p = active();
                    List<?> types = (List<?>) chain.getArg(0);
                    if (p == null || !types.contains(Integer.toString(p.optInt("sdk_device_type")))) return chain.proceed();
                    return chain.proceed(new Object[]{types, wrap(p, chain.getArg(1))});
                }));
    }

    private JSONObject active() {
        JSONObject p = selected.get();
        if (closed || !allowed.getAsBoolean() || p == null) return null;
        JSONObject live = client.projection();
        if (live == null || !p.optString("id").equals(live.optString("id"))
                || !p.optString("session_id").equals(live.optString("session_id"))) return null;
        JSONArray operations = p.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("battery_status".equals(operations.optString(i))) return p;
        return null;
    }

    private Object wrap(JSONObject p, Object callback) {
        AtomicBoolean answered = new AtomicBoolean();
        return Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "QjOplusDeviceQuery";
            };
            if (!answered.compareAndSet(false, true)) return null;
            if (method.getName().equals("onResult")) {
                List<?> copy = new ArrayList<>((List<?>) args[0]);
                main.post(() -> read(p, callback, copy, false));
            } else {
                main.post(() -> call(onError, callback, args));
            }
            return null;
        });
    }

    private void read(JSONObject p, Object callback, List<?> original, boolean onlyProxy) {
        Query query = new Query(p, callback, "oplus-battery-" + sequence.incrementAndGet(), original, onlyProxy);
        if (queries.size() >= 32 || !current(query)) {
            if (onlyProxy) call(onError, callback, 1, queries.size() >= 32 ? "QJ:busy" : "QJ:stale_session");
            else call(onResult, callback, original);
            return;
        }
        queries.put(query.request, query);
        query.timeout = () -> finish(query, original, onlyProxy, null, "request_timeout");
        main.postDelayed(query.timeout, 10_000);
        client.request(query.id, query.session, "battery_status", query.request, new JSONObject(), response -> {
            Object device = null;
            String error = "remote_error";
            try {
                if (response != null && "ok".equals(response.optString("status")) && current(query)
                        && query.request.equals(response.optString("request_id")) && query.session.equals(response.optString("session_id"))) {
                    JSONObject result = response.getJSONObject("result");
                    if (!"battery_status".equals(result.getString("operation"))) throw new JSONException("wrong operation");
                    JSONObject data = result.getJSONObject("data");
                    int level = data.getInt("level");
                    if (level < 0 || level > 100) throw new JSONException("invalid level");
                    device = build(query.projection, level);
                } else if (response != null) error = response.optString("code", "stale_session");
            } catch (ReflectiveOperationException | JSONException | RuntimeException failure) { error = "invalid_remote_result"; }
            finish(query, original, onlyProxy, device, error);
        });
    }

    private boolean current(Query query) {
        JSONObject p = active();
        return p != null && query.id.equals(p.optString("id")) && query.session.equals(p.optString("session_id"));
    }

    private void finish(Query query, List<?> original, boolean onlyProxy, Object device, String error) {
        if (!query.done.compareAndSet(false, true)) return;
        queries.remove(query.request);
        main.removeCallbacks(query.timeout);
        if (!current(query)) { device = null; error = "stale_session"; }
        if (device == null && onlyProxy) { call(onError, query.callback, "request_timeout".equals(error) ? 2 : 1, "QJ:" + error); return; }
        ArrayList<Object> result = new ArrayList<>();
        for (Object item : original) {
            try { if (query.id.equals(deviceId.invoke(item))) continue; }
            catch (ReflectiveOperationException ignored) { /* Preserve other native entries. */ }
            result.add(item);
        }
        if (device != null) result.add(device);
        call(onResult, query.callback, result);
        module.log(Log.INFO, "QiangJian", device == null ? "oplus_battery_request_failed" : "oplus_native_battery_result_delivered");
    }

    private Object build(JSONObject p, int level) throws ReflectiveOperationException, JSONException {
        JSONObject real = p.getJSONObject("real");
        Object device = infoType.getConstructor().newInstance();
        infoType.getMethod("setDeviceId", String.class).invoke(device, p.getString("id"));
        infoType.getMethod("setName", String.class).invoke(device, real.getString("name"));
        infoType.getMethod("setModelId", String.class).invoke(device, real.getString("model"));
        infoType.getMethod("setDeviceType", int.class).invoke(device, p.getInt("sdk_device_type"));
        infoType.getMethod("setDeviceState", int.class).invoke(device, 1); // DISCOVERED; no native connected state invented.
        infoType.getMethod("setDeviceProtocol", int.class).invoke(device, 1); // ONET SDK projection role.
        ArrayList<Object> properties = new ArrayList<>();
        properties.add(property.newInstance("batteryLevel_1280_1", Integer.toString(level)));
        // plugged is not charging. v2 doesn't carry charging status, so don't invent that property.
        infoType.getMethod("setPropertyList", ArrayList.class).invoke(device, properties);
        return device;
    }

    private void call(Method method, Object target, Object... args) {
        try { method.invoke(target, args); }
        catch (ReflectiveOperationException | RuntimeException error) { module.log(Log.WARN, "QiangJian", "oplus_devicekit_callback_failed"); }
    }

    void sessionChanged() {
        main.post(() -> {
            for (Query query : queries.values()) if (!current(query)) finish(query, query.original, query.onlyProxy, null, "stale_session");
        });
    }

    void close() {
        closed = true;
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        sessionChanged();
    }
}
