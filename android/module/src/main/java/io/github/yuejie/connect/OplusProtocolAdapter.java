package io.github.yuejie.connect;

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
    private final NativeLogicalClient logical;
    private volatile NativeLogicalClient.Lease connected;
    private volatile Object connectionInfo;
    private boolean checkingChannel;
    private long nextAutoConnect;
    private String userClosedSession="";
    private final Map<Object,WeakReference<Object>> stateListeners=new java.util.WeakHashMap<>();
    private volatile boolean closed;
    private volatile Published published;
    private volatile Published pairingPublished;
    private BridgeClient.Subscription pairingSubscription;
    private Object pairingReceiver,pairingDevice;
    private String pairingTarget="";
    private Method found, lost, failure, success, stateChanged, optionConnection, optionChannel, idGetter, tempIdGetter, roleGetter, resultCode;
    private Constructor<?> connectionConstructor;
    private Method optionRoles, optionDuration;
    private Constructor<?> deviceConstructor;
    private Class<?> deviceType;
    private volatile JSONObject currentProjection;
    private OplusDeviceKitAdapter deviceKit;
    private OplusRuntimeProbe probe;

    private record Published(String id, String session, String fingerprint, Object device) {}
    private record Pending(OplusPeerRegistry.Ticket ticket, Object device, Object receiver, Runnable timeout,NativeLogicalClient.Lease lease) {}
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
        this.logical=new NativeLogicalClient(client);
    }

    void install() throws ReflectiveOperationException {
        try {
            resolve();
            installDiscovery();
            installConnections();
            installLogicalRouteSelection();
            deviceKit = new OplusDeviceKitAdapter(module, client, loader, allowed, () -> currentProjection,this::currentChannel);
            deviceKit.install();
            probe = new OplusRuntimeProbe(module, loader, allowed, () -> currentProjection);
            probe.install();
            client.onProjection(value -> main.post(() -> update(value)));
            pairingSubscription=client.nativePairing().listen(this::updatePairing);
            client.nativePairing().watch();
            main.post(() -> update(client.projection()));
            main.post(channelMonitor);
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
        success=type(BASE+"GlobalDeviceConnectionCallback").getMethod("onConnectionSuccess",deviceType,type(BASE+"GlobalDeviceConnectionInfo"));
        stateChanged=type(BASE+"DisplayDeviceChangedCallback").getMethod("onChanged",deviceType,deviceType);
        optionConnection=type(BASE+"GlobalDeviceConnectOptions").getMethod("getConnectionType");
        optionChannel=type(BASE+"GlobalDeviceConnectOptions").getMethod("getChannelType");
        connectionConstructor=type(BASE+"GlobalDeviceConnectionInfo").getConstructor(type("com.oplus.pantaconnect.sdk.connection.ConnectionType"),type(BASE+"ChannelType"),String.class,String.class,String.class,type("com.oplus.pantaconnect.sdk.connection.VLinkType"));
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
                type(BASE + "GlobalDeviceDiscoveryCallback"))).setId("yj.oplus.discovery.start").intercept(chain -> {
            Object result = chain.proceed();
            log("oplus_discovery_result code="+resultCode.invoke(result));
            if (!closed && allowed.getAsBoolean() && Integer.valueOf(1).equals(resultCode.invoke(result))) {
                Object owner = chain.getThisObject(), receiver = chain.getArg(1), options = chain.getArg(0);
                Set<?> roles = Set.copyOf((Set<?>) optionRoles.invoke(options));
                long duration = (Long) optionDuration.invoke(options);
                main.post(() -> register(owner, receiver, roles, duration));
            }
            return result;
        }));
        handles.add(module.hook(clients.getMethod("stopGlobalDeviceDiscovery")).setId("yj.oplus.discovery.stop").intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            if (Integer.valueOf(1).equals(resultCode.invoke(result))) main.post(() -> removeDiscovery(owner));
            return result;
        }));
        for (String method : new String[]{"getAvailableDevices", "getDisplayDeviceList"}) {
            handles.add(module.hook(clients.getMethod(method)).setId("yj.oplus.discovery." + method).intercept(chain -> {
                Object original = chain.proceed();
                if (!(original instanceof CompletableFuture<?> future)) return original;
                return future.thenApply(value -> {
                    Published current = published==null?pairingPublished:published;
                    if (closed || !allowed.getAsBoolean() || current == null || !(value instanceof List<?> list)) return value;
                    List<Object> copy = OplusPeerRegistry.overlay(list,current.device(),this::id);
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
                .setId("yj.oplus.connection.request").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if(published==null&&client.nativePairing().owns(target)){
                        Object receiver=chain.getArg(2);main.post(()->{
                            clearPairing("cancelled");pairingTarget=target;pairingReceiver=receiver;pairingDevice=device;
                            try{type(BASE+"GlobalDeviceConnectionCallback").getMethod("onConnectionInitiated",deviceType).invoke(receiver,device);}catch(ReflectiveOperationException ignored){}
                            client.nativePairing().select(target);
                            main.postDelayed(()->{if(pairingReceiver==receiver)clearPairing("timeout");},30_000);
                        });return null;
                    }
                    if (!registry.owns(target)) return chain.proceed();
                    Object receiver = chain.getArg(2);
                    String connection=((Enum<?>)optionConnection.invoke(chain.getArg(1))).name(),channel=((Enum<?>)optionChannel.invoke(chain.getArg(1))).name();
                    main.post(() -> {
                        if (!matchesSession(device)) fail(receiver, device, "stale_session");
                        else if(!"WLAN".equals(connection)||!"CHANNEL_TYPE_LOGICAL".equals(channel))fail(receiver,device,"unsupported_transport_type");
                        else request(target, device, receiver);
                    });
                    return null;
                }));
        // Hook the async SDK wrapper used by BOTH DisplayDevice and ConnectionImpl. ClientsImpl
        // returns SealedResult here, so returning a CompletableFuture from that method would crash.
        handles.add(module.hook(service.getMethod("cancelConnection", deviceType))
                .setId("yj.oplus.connection.cancel").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if(published==null&&client.nativePairing().owns(target))return cancelPairing(target);
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(device)) return CompletableFuture.failedFuture(new IllegalStateException("YJ:stale_session"));
                    CompletableFuture<Boolean> result = new CompletableFuture<>();
                    main.post(() -> closeRemote(target,"cancelled",result));
                    return result;
                }));
        handles.add(module.hook(service.getMethod("closeConnection", deviceType, options))
                .setId("yj.oplus.connection.close").intercept(chain -> {
                    Object device = chain.getArg(0);
                    String target = id(device);
                    if(published==null&&client.nativePairing().owns(target))return cancelPairing(target);
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(device)) return CompletableFuture.failedFuture(new IllegalStateException("YJ:stale_session"));
                    CompletableFuture<Boolean> result=new CompletableFuture<>();
                    main.post(()->closeRemote(target,"connection_closed",result));return result;
                }));
        handles.add(module.hook(clients.getMethod("confirmConnection", deviceType, type(BASE + "PairAction"), type(BASE + "ConfirmType")))
                .setId("yj.oplus.connection.confirm").intercept(chain -> {
                    String target = id(chain.getArg(0));
                    if (!registry.owns(target)) return chain.proceed();
                    if (!matchesSession(chain.getArg(0))) { log("oplus_stale_confirmation_ignored"); return null; }
                    String action = ((Enum<?>) chain.getArg(1)).name();
                    if("REJECT".equals(action))main.post(()->closeRemote(target,"permission_denied",new CompletableFuture<>()));
                    return null;
                }));
        handles.add(module.hook(service.getMethod("getConnectionList",String.class,type("com.oplus.pantaconnect.sdk.connection.ConnectionType")))
            .setId("yj.oplus.connection.list").intercept(chain->{
                if(!registry.owns((String)chain.getArg(0)))return chain.proceed();
                Object filter=chain.getArg(1);Object info=connectionInfo;
                boolean wlan=filter==null||"WLAN".equals(((Enum<?>)filter).name());
                return CompletableFuture.completedFuture(wlan&&currentChannel()&&info!=null?List.of(info):Collections.emptyList());
            }));
        handles.add(module.hook(service.getMethod("registerDisplayDeviceStateListener",type(BASE+"DisplayDeviceChangedCallback")))
            .setId("yj.oplus.connection.listen").intercept(chain->{Object result=chain.proceed();Object owner=chain.getThisObject(),listener=chain.getArg(0);main.post(()->stateListeners.put(owner,new WeakReference<>(listener)));return result;}));
        handles.add(module.hook(service.getMethod("unregisterDisplayDeviceStateListener"))
            .setId("yj.oplus.connection.unlisten").intercept(chain->{Object result=chain.proceed();Object owner=chain.getThisObject();main.post(()->stateListeners.remove(owner));return result;}));
    }

    private void request(String target, Object device, Object receiver) {
        if(receiver!=null)userClosedSession="";
        if (closed || !allowed.getAsBoolean() || !registry.owns(target)) {
            fail(receiver, device, "peer_offline"); return;
        }
        if(currentChannel()){
            NativeLogicalClient.Lease live=connected;
            logical.state(live).whenComplete((open,error)->{
                // A late state result belongs to its old channel, never the replacement lease.
                if(connected!=live){fail(receiver,device,"native_channel_unavailable");return;}
                if(error==null&&Boolean.TRUE.equals(open)&&matchesSession(device))notify(receiver,success,device,connectionInfo);
                else{clearConnection();fail(receiver,device,"native_channel_unavailable");}
            });
            return;
        }
        if(!pending.isEmpty()){fail(receiver,device,"busy");return;}
        final OplusPeerRegistry.Ticket ticket;
        try { ticket = registry.begin(target); }
        catch (IllegalStateException error) { fail(receiver, device, error.getMessage()); return; }
        final NativeLogicalClient.Lease lease;
        try{lease=logical.allocate(target,currentProjection.getString("session_id"));}catch(JSONException|RuntimeException unavailable){registry.finish(ticket);fail(receiver,device,"stale_session");return;}
        Runnable timeout = () -> {logical.close(lease);complete(ticket, "timeout");};
        pending.put(ticket.number(), new Pending(ticket, device, receiver, timeout,lease));
        main.postDelayed(timeout, 10_000);
        logical.open(lease).whenComplete((open,error) -> {
            if(error!=null||!Boolean.TRUE.equals(open)){logical.close(lease);complete(ticket,error==null?"native_channel_unavailable":safeError(error));return;}
            Pending item=pending.remove(ticket.number());
            if(item==null||!registry.finish(ticket)||!matchesSession(device)){logical.close(lease);return;}
            main.removeCallbacks(item.timeout());
            try{
                String address=lease.transportAddress();int split=address.lastIndexOf(':');String ip=split>0?address.substring(0,split):"";
                if(!ip.matches("[0-9.]+"))throw new IllegalStateException("transport_address_unavailable");
                Object info=connectionConstructor.newInstance(enumeration("com.oplus.pantaconnect.sdk.connection.ConnectionType","WLAN"),enumeration(BASE+"ChannelType","CHANNEL_TYPE_LOGICAL"),"",ip,"",enumeration("com.oplus.pantaconnect.sdk.connection.VLinkType","WLAN"));
                connected=lease;connectionInfo=info;
                if(!currentChannel())throw new IllegalStateException("native_channel_unavailable");
                refreshConnectionState();
                if(receiver!=null&&!notify(receiver,success,published.device(),info)){clearConnection();logical.close(lease);}
                else log("oplus_logical_channel_open_remote_ack");
            }catch(ReflectiveOperationException|RuntimeException failure){logical.close(lease);clearConnection();fail(receiver,device,"native_channel_setup_failed");}
        });
    }

    /** The sampled UI hardcodes PAD to Bluetooth. For our exact LAN-bound peer, select the
     * implemented WLAN logical path while retaining PAD/PHONE, account state and OEM flags. */
    private void installLogicalRouteSelection() throws ReflectiveOperationException {
        Class<?> wrapper=type("com.oplus.linker.plugin.devicediscovery.thirdapi.ptc.PTCWrapper");
        Class<?> resultCallback=type("com.oplus.linker.connect.arch.ResultCallBack");
        Method resultMethod=resultCallback.getMethod("onResult",Object.class);
        Class<?> discoveryPage=type("com.oplus.linker.plugin.devicediscovery.ui.DeviceDiscoveryPreferenceFragment");
        Class<?> bean=type("com.oplus.linker.plugin.devicediscovery.bean.DeviceBean");
        Method beanId=bean.getMethod("getDeviceId");
        handles.add(module.hook(discoveryPage.getDeclaredMethod("isDuplicateDevice",bean,type("com.oplus.synergy.devicekit.bean.SynergyDeviceInfo")))
            .setId("yj.oplus.pairing.rediscovery").intercept(chain->{
                // OEM history is not a reusable YJ trust grant. Keep a currently discovered,
                // unpaired bridge peer selectable so the user can authorize a fresh session.
                if(published==null&&client.nativePairing().owns((String)beanId.invoke(chain.getArg(0))))return false;
                return chain.proceed();
            }));
        handles.add(module.hook(wrapper.getDeclaredMethod("getConnectInfo",deviceType,resultCallback))
            .setId("yj.oplus.logical.discovery.metadata").intercept(chain->{
                Object device=chain.getArg(0),info=connectionInfo;
                // The OEM wrapper only asks for WLAN metadata for PCs. This exact PAD proxy
                // has a real acknowledged WLAN lease, so supply that lease's existing result.
                if(registry.owns(id(device))&&matchesSession(device)&&currentChannel()&&info!=null){resultMethod.invoke(chain.getArg(1),info);return null;}
                return chain.proceed();
            }));
        Class<?> options=type(BASE+"GlobalDeviceConnectOptions");
        Method copy=options.getMethod("copy",type("com.oplus.pantaconnect.sdk.connection.ConnectionType"),type(BASE+"ChannelType"),type(BASE+"OptionExtensionArgs"),type(BASE+"DirectDecision"));
        Method extra=options.getMethod("getExtra"),direct=options.getMethod("getDirectDecision");
        Class<?> utility=type("com.oplus.linker.synergy.wiseconnect.senseless.SenselessConnectionUtil");
        handles.add(module.hook(utility.getDeclaredMethod("deviceConnectOptions",Boolean.class,deviceType)).setId("yj.oplus.logical.route").intercept(chain->{
            Object original=chain.proceed(),device=chain.getArg(1);
            if(!client.nativePairing().owns(id(device))&&(!registry.owns(id(device))||!matchesSession(device)||!supports(client.projection(),"logical_open")))return original;
            return copy.invoke(original,enumeration("com.oplus.pantaconnect.sdk.connection.ConnectionType","WLAN"),enumeration(BASE+"ChannelType","CHANNEL_TYPE_LOGICAL"),extra.invoke(original),direct.invoke(original));
        }));
        Class<?> page=type("com.oplus.linker.synergy.ui.fragment.DeviceConnectPreferenceFragment");
        java.lang.reflect.Field selected=page.getDeclaredField("mONetDevice");selected.setAccessible(true);
        handles.add(module.hook(page.getDeclaredMethod("isLanDevice",deviceType)).setId("yj.oplus.logical.ui.route").intercept(chain->{
            Object device=chain.getArg(0);if(client.nativePairing().owns(id(device))||registry.owns(id(device))&&matchesSession(device)&&supports(client.projection(),"logical_open"))return true;
            return chain.proceed();
        }));
        handles.add(module.hook(page.getDeclaredMethod("isWlanType",type("com.oplus.pantaconnect.sdk.DeviceType"))).setId("yj.oplus.logical.ui.type").intercept(chain->{
            Object device=selected.get(chain.getThisObject());if(client.nativePairing().owns(id(device))||registry.owns(id(device))&&matchesSession(device)&&supports(client.projection(),"logical_open"))return true;
            return chain.proceed();
        }));
        Class<?> remote=type("com.oplus.linker.mac.client.data.source.remote.MacRemoteDataSource");
        handles.add(module.hook(remote.getDeclaredMethod("isWlanType",deviceType)).setId("yj.oplus.logical.result.route").intercept(chain->{
            Object device=chain.getArg(0);
            // The same PAD-over-WLAN decision must reach the result consumer; otherwise
            // its real onConnectionSuccess callback drops the panel completion as non-PC.
            if(client.nativePairing().owns(id(device))||registry.owns(id(device))&&matchesSession(device)&&supports(client.projection(),"logical_open"))return true;
            return chain.proceed();
        }));
    }

    private static String safeError(Throwable failure){String message=failure.getMessage();return message!=null&&message.matches("[a-z_]{1,64}")?message:"remote_error";}
    private static boolean supports(JSONObject projection,String operation){if(projection==null)return false;var ops=projection.optJSONArray("operations");if(ops!=null)for(int i=0;i<ops.length();i++)if(operation.equals(ops.optString(i)))return true;return false;}
    private boolean currentChannel(){return !closed&&allowed.getAsBoolean()&&logical.current(connected);}
    private void refreshConnectionState(){
        if(deviceKit!=null)deviceKit.sessionChanged();
        Published old=published;if(old==null||currentProjection==null)return;
        try{Published next=new Published(old.id(),old.session(),old.fingerprint(),build(currentProjection));published=next;for(WeakReference<Object> listener:stateListeners.values()){Object callback=listener.get();if(callback!=null)notify(callback,stateChanged,old.device(),next.device());}}
        catch(ReflectiveOperationException|JSONException failure){log("oplus_connection_state_failed");}
    }
    private void clearConnection(){boolean existed=connected!=null;connected=null;connectionInfo=null;if(existed)refreshConnectionState();}
    private void closeRemote(String target,String reason,CompletableFuture<Boolean> result){
        JSONObject selected=currentProjection;if(selected!=null&&target.equals(selected.optString("id")))userClosedSession=selected.optString("session_id");
        ArrayList<NativeLogicalClient.Lease> leases=new ArrayList<>();
        for(Pending item:new ArrayList<>(pending.values()))if(item.ticket().id().equals(target)){leases.add(item.lease());complete(item.ticket(),reason);}
        if(connected!=null&&target.equals(connected.peer()))leases.add(connected);
        clearConnection();registry.cancel(target);
        if(leases.isEmpty()){result.complete(true);return;} // This adapter has created no remote channel.
        CompletableFuture<?>[] closing=leases.stream().map(logical::close).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(closing).whenComplete((ignored,error)->{if(error==null)result.complete(true);else result.completeExceptionally(error);});
    }
    private final Runnable channelMonitor=new Runnable(){public void run(){
        if(closed)return;
        if(currentChannel()&&!checkingChannel){checkingChannel=true;NativeLogicalClient.Lease lease=connected;logical.state(lease).whenComplete((open,error)->{checkingChannel=false;if(connected==lease&&(error!=null||!Boolean.TRUE.equals(open)))clearConnection();});}
        else if(connected!=null&&!currentChannel())clearConnection();
        JSONObject live=client.projection();Published device=published;
        if(connected==null&&pending.isEmpty()&&allowed.getAsBoolean()&&live!=null&&device!=null
                &&device.id().equals(live.optString("id"))&&device.session().equals(live.optString("session_id"))
                &&!userClosedSession.equals(device.session())&&supports(live,"logical_open")
                &&android.os.SystemClock.elapsedRealtime()>=nextAutoConnect){
            nextAutoConnect=android.os.SystemClock.elapsedRealtime()+5000;
            request(device.id(),device.device(),null);
        }
        main.postDelayed(this,2000);
    }};

    private void complete(OplusPeerRegistry.Ticket ticket, String error) {
        Pending item = pending.remove(ticket.number());
        if (item == null) return;
        main.removeCallbacks(item.timeout());
        if (!registry.finish(ticket)) return; // Cancelled/withdrawn request cannot be resurrected.
        fail(item.receiver(), item.device(), error);
    }

    private void cancel(String target, String reason) {
        for (Pending item : new ArrayList<>(pending.values())) {
            if (item.ticket().id().equals(target)){logical.close(item.lease());complete(item.ticket(), reason);}
        }
        registry.cancel(target);
        if(connected!=null&&target.equals(connected.peer())){logical.close(connected);clearConnection();}
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
        log("oplus_discovery_registered roles="+roles+" projection="+(published!=null));
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
            if (!target.matches("yj:[0-9a-f]{64}")) throw new JSONException("invalid alias");
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
        if(next!=null&&next.id().equals(pairingTarget)&&pairingReceiver!=null){Object receiver=pairingReceiver;pairingReceiver=null;pairingDevice=null;pairingTarget="";request(next.id(),next.device(),receiver);}
    }

    private void clearPairing(String error){Object receiver=pairingReceiver,device=pairingDevice;pairingReceiver=null;pairingDevice=null;pairingTarget="";if(receiver!=null)fail(receiver,device,error);}
    private CompletableFuture<Boolean> cancelPairing(String target){
        CompletableFuture<Boolean> result=new CompletableFuture<>();main.post(()->{
            clearPairing("cancelled");client.nativePairing().cancel(target,value->result.complete("ok".equals(value.optString("status"))));
        });return result;
    }
    private void updatePairing(JSONObject snapshot){
        JSONObject state=snapshot.optJSONObject("pairing");String phase=state==null?"":state.optString("phase");
        if("disconnected".equals(phase)||"stopped".equals(phase))clearPairing("peer_offline");
        if(published!=null)return;
        JSONObject candidate=client.nativePairing().candidate();Published next=null;
        try{if(candidate!=null)next=new Published(candidate.getString("id"),candidate.optString("session_id"),candidate.toString(),build(candidate));}
        catch(ReflectiveOperationException|JSONException failure){log("oplus_pairing_candidate_rejected");}
        if(java.util.Objects.equals(pairingPublished==null?null:pairingPublished.fingerprint(),next==null?null:next.fingerprint()))return;
        for(Discovery discovery:discoveries)withdraw(discovery);pairingPublished=next;
        for(Discovery discovery:discoveries)publish(discovery);
    }

    private void publish(Discovery discovery) {
        Published current = published==null?pairingPublished:published;
        Object receiver = discovery.receiver.get();
        if (current == null || receiver == null || SystemClock.elapsedRealtime() >= discovery.deadline) return;
        try {
            Object role = roleGetter.invoke(current.device());
            if (!discovery.roles.isEmpty() && !discovery.roles.contains(role)){log("oplus_discovery_role_filtered");return;}
            if (notify(receiver, found, current.device())){discovery.emitted = current.device();log("oplus_discovery_published");}
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
        return deviceConstructor.newInstance(target, "yj-session:" + projection.getString("session_id"), enumeration("com.oplus.pantaconnect.sdk.DeviceType", kind.equals("tablet") ? "PAD" : "PHONE"),
                real.getString("name"), enumeration(BASE + "DeviceState", currentChannel()?"CONNECTED":"DISCOVERED"), enumeration(BASE + "AccountState", "NO_ACCOUNT"),
                false, 0, currentChannel()?List.of(enumeration("com.oplus.pantaconnect.sdk.connection.ConnectionType","WLAN")):Collections.emptyList(), 0, real.getString("model"), enumeration("com.oplus.pantaconnect.sdk.discovery.DiscoveryStrategy", "LAN"),
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
        try { return ("yj-session:" + current.session()).equals(tempIdGetter.invoke(device)); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    private boolean notify(Object receiver, Method method, Object... args) {
        try { method.invoke(receiver, args); return true; }
        catch (ReflectiveOperationException | RuntimeException error) { log("oplus_callback_failed " + method.getName()); return false; }
    }
    private void fail(Object receiver, Object device, String error) {
        if(receiver!=null)notify(receiver, failure, device, new IllegalStateException("YJ:" + error));
        log("oplus_request_failed " + error);
    }
    private void log(String event) { module.log(Log.INFO, "YueJie", event + " source=2");android.util.Log.i("YueJieOplus",event); }

    void close() {
        if(pairingSubscription!=null)pairingSubscription.close();
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
