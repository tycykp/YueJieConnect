package io.github.yuejie.connect;

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
    private final BooleanSupplier connected;
    private final Supplier<JSONObject> selected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ConcurrentHashMap<String, Query> queries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private Class<?> callbackType, infoType;
    private Constructor<?> property;
    private Method onResult, onError, deviceId;
    private Method deviceAdded,deviceChanged,deviceRemoved;
    private final java.util.Map<Object,List<java.lang.ref.WeakReference<Object>>> listeners=new java.util.WeakHashMap<>();
    private Object lastDevice;
    private String lastSession="",batterySession="",batteryPeer="";
    private record Battery(int level,Boolean charging) {}
    private Battery batteryState;
    private final Runnable batteryPoll=new Runnable(){public void run(){
        if(closed)return;
        listeners.values().forEach(values->values.removeIf(reference->reference.get()==null));
        JSONObject p=active();
        if(p!=null&&supports(p,"battery_status")&&listeners.values().stream().anyMatch(values->!values.isEmpty())&&queries.isEmpty())
            read(p,null,List.of(),false);
        main.postDelayed(this,2000);
    }};

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
                         BooleanSupplier allowed, Supplier<JSONObject> selected,BooleanSupplier connected) {
        this.module = module; this.client = client; this.loader = loader;
        this.allowed = allowed; this.selected = selected;
        this.connected=connected;
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
        Class<?> operateCallback=type(KIT+"callback.ISynergyDeviceOperateCallback");
        Method operateResult=operateCallback.getMethod("onResult",int.class,String.class);
        Method removeDevice=owner.getMethod("removeDevice",List.class);
        hooks.add(module.hook(owner.getMethod("deviceDelete",String.class,int.class,operateCallback))
            .setId("yj.oplus.devicekit.forget").intercept(chain->{
                String target=(String)chain.getArg(0);
                if(target==null||!target.matches("yj:[0-9a-f]{64}"))return chain.proceed();
                Object callback=chain.getArg(2),service=chain.getThisObject();
                client.pairing(new JSONObject().put("action","forget").put("peer",target),response->main.postDelayed(()->{
                    // Other OEM processes observe projection withdrawal on their 1-second poll.
                    // Let those final offline notifications settle before removing shared history.
                    // Never remove a new connection created while this deletion was pending.
                    boolean ok="ok".equals(response.optString("status"));
                    JSONObject live=client.projection();
                    if(live!=null&&target.equals(live.optString("id")))ok=false;
                    if(ok)try{removeDevice.invoke(service,List.of(target));}
                    catch(ReflectiveOperationException failed){ok=false;}
                    if(callback!=null)call(operateResult,callback,ok?0:1,ok?"success":response.optString("code","delete_failed"));
                    android.util.Log.i("YueJieOplus","oplus_devicekit_forget_"+(ok?"completed":"failed"));
                },2000));return null;
            }));
        hooks.add(module.hook(owner.getMethod("deviceDisconnect",String.class,int.class,boolean.class,operateCallback))
            .setId("yj.oplus.devicekit.disconnect").intercept(chain->{
                JSONObject p=active();String target=(String)chain.getArg(0);
                if(p==null||!p.optString("id").equals(target))return chain.proceed();
                Object callback=chain.getArg(3);
                if((Integer)chain.getArg(1)!=p.optInt("sdk_device_type")){
                    if(callback!=null)main.post(()->call(operateResult,callback,1,"invalid_device_type"));
                    return null;
                }
                client.disconnect(target,p.optString("session_id"),response->{
                    boolean ok="ok".equals(response.optString("status"));
                    if(callback!=null)call(operateResult,callback,ok?0:1,ok?"success":response.optString("code","disconnect_failed"));
                    android.util.Log.i("YueJieOplus","oplus_devicekit_disconnect_"+(ok?"completed":"failed"));
                });
                return null;
            }));
        Class<?> changeListener=type(KIT+"callback.ISynergyDeviceChangeListener");
        deviceAdded=changeListener.getMethod("onDeviceAdded",List.class);
        deviceChanged=changeListener.getMethod("onDeviceInfoChanged",List.class);
        deviceRemoved=changeListener.getMethod("onDeviceRemoved",List.class);
        hooks.add(module.hook(owner.getMethod("registerDeviceChangeListener",changeListener)).setId("yj.oplus.devicekit.listen").intercept(chain->{
            Object result=chain.proceed(),service=chain.getThisObject(),listener=chain.getArg(0);
            main.post(()->{if(closed)return;var values=listeners.computeIfAbsent(service,k->new ArrayList<>());
                values.removeIf(w->w.get()==null);if(values.stream().noneMatch(w->w.get()==listener))values.add(new java.lang.ref.WeakReference<>(listener));
                JSONObject p=active();if(p!=null)try{call(deviceAdded,listener,List.of(build(p,cachedBattery(p))));}catch(ReflectiveOperationException|JSONException ignored){}
            });return result;
        }));
        for(Class<?>[] signature:new Class<?>[][]{{},{changeListener}}){
            hooks.add(module.hook(owner.getMethod("unRegisterDeviceChangeListener",signature)).setId("yj.oplus.devicekit.unlisten."+signature.length).intercept(chain->{
                Object result=chain.proceed(),service=chain.getThisObject(),listener=signature.length==0?null:chain.getArg(0);
                main.post(()->{if(listener==null)listeners.remove(service);else{var values=listeners.get(service);if(values!=null)values.removeIf(w->w.get()==null||w.get()==listener);}});return result;
            }));
        }
        hooks.add(module.hook(owner.getMethod("queryOriginalDeviceListWithIds", List.class, callbackType))
                .setId("yj.oplus.devicekit.ids").intercept(chain -> {
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
                .setId("yj.oplus.devicekit.type").intercept(chain -> {
                    JSONObject p = active();
                    int requested = (Integer) chain.getArg(0);
                    if (p == null || requested != p.optInt("sdk_device_type")) return chain.proceed();
                    return chain.proceed(new Object[]{requested, wrap(p, chain.getArg(1))});
                }));
        hooks.add(module.hook(owner.getMethod("queryDeviceListByTypes", List.class, callbackType))
                .setId("yj.oplus.devicekit.types").intercept(chain -> {
                    JSONObject p = active();
                    List<?> types = (List<?>) chain.getArg(0);
                    if (p == null || !types.contains(Integer.toString(p.optInt("sdk_device_type")))) return chain.proceed();
                    return chain.proceed(new Object[]{types, wrap(p, chain.getArg(1))});
                }));
        main.post(batteryPoll);
    }

    private JSONObject active() {
        JSONObject p = selected.get();
        if (closed || !allowed.getAsBoolean() || p == null) return null;
        JSONObject live = client.projection();
        if (live == null || !p.optString("id").equals(live.optString("id"))
                || !p.optString("session_id").equals(live.optString("session_id"))) return null;
        JSONArray operations = p.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("device_profile".equals(operations.optString(i))) return p;
        return null;
    }

    private Object wrap(JSONObject p, Object callback) {
        AtomicBoolean answered = new AtomicBoolean();
        return Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "YjOplusDeviceQuery";
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
            if (onlyProxy) call(onError, callback, 1, queries.size() >= 32 ? "YJ:busy" : "YJ:stale_session");
            else call(onResult, callback, original);
            return;
        }
        queries.put(query.request, query);
        query.timeout = () -> finish(query, original, onlyProxy, null, "request_timeout");
        main.postDelayed(query.timeout, 10_000);
        if(!supports(p,"battery_status")){
            try{finish(query,original,onlyProxy,build(p,null),"");}
            catch(ReflectiveOperationException|JSONException failure){finish(query,original,onlyProxy,null,"invalid_remote_result");}return;
        }
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
                    Boolean charging=data.has("charging")&&!data.isNull("charging")?data.getBoolean("charging"):null;
                    Battery next=new Battery(level,charging);
                    boolean changed=!next.equals(cachedBattery(query.projection));
                    batteryPeer=query.id;batterySession=query.session;batteryState=next;
                    device = build(query.projection, next);
                    if(changed)publishState();
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
        if(query.callback==null)return; // Listener refresh has no SDK query callback.
        if (device == null && onlyProxy) { call(onError, query.callback, "request_timeout".equals(error) ? 2 : 1, "YJ:" + error); return; }
        ArrayList<Object> result = new ArrayList<>();
        for (Object item : original) {
            try { if (query.id.equals(deviceId.invoke(item))) continue; }
            catch (ReflectiveOperationException ignored) { /* Preserve other native entries. */ }
            result.add(item);
        }
        if (device != null) result.add(device);
        call(onResult, query.callback, result);
        module.log(Log.INFO, "YueJie", device == null ? "oplus_battery_request_failed" : "oplus_native_battery_result_delivered");
    }

    private Object build(JSONObject p, Battery battery) throws ReflectiveOperationException, JSONException {
        JSONObject real = p.getJSONObject("real");
        Object device = infoType.getConstructor().newInstance();
        infoType.getMethod("setDeviceId", String.class).invoke(device, p.getString("id"));
        infoType.getMethod("setName", String.class).invoke(device, real.getString("name"));
        infoType.getMethod("setModelId", String.class).invoke(device, real.getString("model"));
        infoType.getMethod("setDeviceType", int.class).invoke(device, p.getInt("sdk_device_type"));
        // PantaUtils DEVICE_STATE_CONNECTED=2, DISCOVERED=1 in the pinned APK.
        // This uses the same remote-acknowledged lease as DisplayDevice and getConnectionList.
        infoType.getMethod("setDeviceState", int.class).invoke(device, connected.getAsBoolean()?2:1);
        infoType.getMethod("setDeviceProtocol", int.class).invoke(device, 1); // ONET SDK projection role.
        ArrayList<Object> properties = new ArrayList<>();
        if(battery!=null){
            properties.add(property.newInstance("batteryLevel_1280_1",Integer.toString(battery.level())));
            if(battery.charging()!=null)properties.add(property.newInstance("batteryCharging_1280_2",Boolean.toString(battery.charging())));
        }
        infoType.getMethod("setPropertyList", ArrayList.class).invoke(device, properties);
        return device;
    }

    private void call(Method method, Object target, Object... args) {
        try { method.invoke(target, args); }
        catch (ReflectiveOperationException | RuntimeException error) { module.log(Log.WARN, "YueJie", "oplus_devicekit_callback_failed"); }
    }

    void sessionChanged() {
        main.post(() -> {
            for (Query query : queries.values()) if (!current(query)) finish(query, query.original, query.onlyProxy, null, "stale_session");
            publishState();
        });
    }
    private static boolean supports(JSONObject p,String operation){JSONArray ops=p.optJSONArray("operations");if(ops!=null)for(int i=0;i<ops.length();i++)if(operation.equals(ops.optString(i)))return true;return false;}
    private Battery cachedBattery(JSONObject p){return batteryPeer.equals(p.optString("id"))&&batterySession.equals(p.optString("session_id"))&&supports(p,"battery_status")?batteryState:null;}
    private void publishState(){
        JSONObject p=active();Object old=lastDevice,next=null;String session=p==null?"":p.optString("session_id");
        try{if(p!=null)next=build(p,cachedBattery(p));}catch(ReflectiveOperationException|JSONException failure){next=null;}
        boolean replaced=!lastSession.equals(session);lastDevice=next;lastSession=session;
        for(var values:listeners.values()){
            values.removeIf(w->w.get()==null);
            for(var reference:values){Object listener=reference.get();if(listener==null)continue;
                if(old!=null&&(next==null||replaced))call(deviceRemoved,listener,List.of(old));
                if(next!=null)call(old==null||replaced?deviceAdded:deviceChanged,listener,List.of(next));
            }
        }
    }

    void close() {
        closed = true;
        main.removeCallbacks(batteryPoll);
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        sessionChanged();
        main.post(listeners::clear);
    }
}
