package io.github.yuejie.connect;

import android.app.Service;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

/** JNI and all persistent state live only in this process, never in Hook callbacks. */
public final class BridgeService extends Service {
    private WifiRoute wifiRoute;
    private NetworkBluetoothSession networkBluetooth;
    private volatile JSONObject networkStatus;
    private volatile long networkObservedAt;
    private NativePairingService nativePairing;
    private VendorProcessProof mirrorProof;
    private SystemNotificationBridge notifications;
    private int hostSource;
    private final NativeRuntimeRegistry runtimes=new NativeRuntimeRegistry();
    private final BrowserRelayOffer.Inbox browserRelay = new BrowserRelayOffer.Inbox();
    private BrowserRelayNotice browserNotice;
    private final NativeScreenProofs screenProofs=new NativeScreenProofs();
    private record MediaGrant(String session,long stream,NativeScreenProofs.Caller caller){}
    private final java.util.Map<Long,MediaGrant> screenMedia=new java.util.concurrent.ConcurrentHashMap<>();
    private String initialization="{\"status\":\"error\",\"code\":\"profile_unavailable\"}";
    private final android.os.Handler monitor=new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastState="";
    private boolean connectionStarted;
    private final java.util.concurrent.locks.ReentrantLock lifecycle=new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.ScheduledExecutorService pairingWorker=
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"yj-pairing-recovery"));
    private final Runnable monitorTask=new Runnable(){public void run(){
        try{JSONObject result=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}"));
            JSONObject state=result.optJSONObject("bridge");refreshScreenScope(state);publishNetworkSurface(state);
            browserRelay.session(state != null && "active".equals(state.optString("phase")) ? state.optString("session") : "");
            browserNotice.session(state != null && "active".equals(state.optString("phase"))
                    && relayGranted(state.optJSONArray("granted_operations")) ? state.optString("session") : "");
            if(state!=null){String marker="phase="+state.optString("phase")+" error="+state.optString("last_error")+" errno="+state.optString("socket_errno")+" rpc="+state.optLong("requests_sent")+"/"+state.optLong("requests_received")+"/"+state.optLong("responses_received");
                if(!marker.equals(lastState)){lastState=marker;android.util.Log.i("YueJieBridge",marker);}}
        }catch(org.json.JSONException|RuntimeException ignored){}monitor.postDelayed(this,1000);
    }};
    private void publishNetworkSurface(JSONObject state)throws org.json.JSONException{
        if(hostSource!=1)return;
        JSONObject network=networkStatus,projection=state==null?null:state.optJSONObject("projection");
        boolean eligible=state!=null&&"active".equals(state.optString("phase"))&&"bluetooth".equals(state.optString("control_transport"))
            &&projection!=null&&network!=null&&state.optString("session").equals(network.optString("session"))
            &&SystemClock.elapsedRealtime()-networkObservedAt<6500;
        boolean granted=false;
        if(projection!=null){JSONArray ops=projection.optJSONArray("operations");if(ops!=null)for(int i=0;i<ops.length();i++)granted|="network_share".equals(ops.optString(i));}
        JSONObject data=new JSONObject().put("eligible",eligible&&granted);
        if(eligible&&granted){
            data.put("session",network.getString("session")).put("id",projection.getString("id"))
                .put("name",projection.getJSONObject("real").getString("name")).put("phase",network.getString("phase"))
                .put("upstream",network.getString("upstream")).put("available",network.optBoolean("available"))
                .put("observed_at",networkObservedAt);
        }
        sendBroadcast(new Intent(NetworkShareSurface.STATE).setPackage(NetworkShareSurface.SYSTEM_UI).putExtra("state",data.toString()));
    }
    @Override public void onCreate() {
        super.onCreate();
        browserNotice = new BrowserRelayNotice(this);
        wifiRoute=new WifiRoute(getSystemService(android.net.ConnectivityManager.class));
        nativePairing=new NativePairingService(this,new NativePairingService.Host(){
            public JSONObject dispatch(JSONObject command)throws org.json.JSONException{
                if("bridge_confirm".equals(command.optString("op"))&&notifications!=null)notifications.addNativeGrants(command);
                JSONObject result=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,hostSource,SystemClock.elapsedRealtime(),command.toString()));
                if("bridge_abort".equals(command.optString("op"))&&"ok".equals(result.optString("status"))){releaseConnection();}
                return result;
            }
            public JSONObject restore(JSONObject command)throws org.json.JSONException{
                // Reuse exactly the saved grants, without adding newly enabled notification grants.
                return new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,hostSource,SystemClock.elapsedRealtime(),command.toString()));
            }
            public void prepare()throws org.json.JSONException{
                if(!"ok".equals(new JSONObject(initialization).optString("status")))throw new IllegalStateException("profile_unavailable");
                if(!wifiRoute.acquire())throw new IllegalStateException("wifi_route_unavailable");
                try{foreground();}
                catch(ForegroundServiceStartNotAllowedException restricted){
                    // Verified OEM processes already hold a binding. Discovery can run while bound.
                    android.util.Log.i("YueJieBridge","native_pairing_bound_service");
                }
            }
        });
        mirrorProof=new VendorProcessProof(this);
        mirrorProof.start();
        try {
            int xiaomi=sourceFor(CallerPolicy.XIAOMI),oplus=sourceFor(CallerPolicy.OPLUS);
            int source=xiaomi!=0 && oplus==0?xiaomi:oplus!=0 && xiaomi==0?oplus:0;
            if(source==0){initialization="{\"status\":\"error\",\"code\":\"unsupported_host_package\"}";return;}
            hostSource=source;
            String name=null;
            try{name=Settings.Global.getString(getContentResolver(),"device_name");}
            catch(SecurityException restricted){android.util.Log.i("YueJieBridge","device_name_restricted_using_model");}
            if(name==null || name.isBlank()) name=Build.MODEL;
            JSONArray key=new JSONArray();for(int i=0;i<32;i++)key.put(0);
            JSONArray operations = new JSONArray();
            if (source == 2 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC)) operations.put("nfc_tap");
            JSONObject profile=new JSONObject().put("peer_key",key).put("platform",source==1?"hyper_os4":"color_os17")
                .put("kind",getResources().getConfiguration().smallestScreenWidthDp>=600?"tablet":"phone")
                .put("name",name).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
                .put("os_build",Build.VERSION.INCREMENTAL).put("operations",operations);
            NativeCore.setBatteryReader(()->LocalDeviceOperations.battery(getApplicationContext()));
            initialization=NativeCore.initialize(new java.io.File(getFilesDir(),"bridge-identity.key").getAbsolutePath(),profile.toString());
            if("ok".equals(new JSONObject(initialization).optString("status")))try{notifications=new SystemNotificationBridge(this);}
            catch(RuntimeException unavailable){android.util.Log.w("YueJieBridge","notification_endpoint_unavailable");}
        } catch(org.json.JSONException | RuntimeException error) {
            initialization="{\"status\":\"error\",\"code\":\"profile_setup_failed\",\"detail\":\""+error.getClass().getSimpleName()+"\"}";
        } finally {android.util.Log.i("YueJieBridge","initialization="+initialization);monitor.post(monitorTask);
            pairingWorker.scheduleWithFixedDelay(()->{
                if(!lifecycle.tryLock())return;
                try{if(networkBluetooth==null)nativePairing.tick();releaseEndedConnection();}
                catch(org.json.JSONException|RuntimeException failure){android.util.Log.w("YueJieBridge","pairing_recovery_unavailable");}
                finally{lifecycle.unlock();}
            },0,1,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    private int sourceFor(String packageName){
        try{return CallerVerifier.verify(getPackageManager(),getPackageManager().getApplicationInfo(packageName,0).uid);}
        catch(PackageManager.NameNotFoundException ignored){return 0;}
    }
    private synchronized void foreground() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("bridge","设备桥接",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,"bridge").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name)).setContentText("设备桥接正在运行，点此查看或断开")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(1,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        // Foreground priority alone does not give a bound service a started lifetime.
        // Retain the authenticated connection when the last UI/OEM binding goes away.
        if(!connectionStarted){
            startService(new Intent(this,BridgeService.class));
            connectionStarted=true;
        }
    }
    private synchronized void releaseConnection(){
        if(networkBluetooth!=null){networkBluetooth.close();networkBluetooth=null;}
        wifiRoute.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if(connectionStarted){connectionStarted=false;stopSelf();}
    }
    private void releaseEndedConnection() throws org.json.JSONException {
        // Do not race a new connection's route acquisition or block the main thread on it.
        if(!lifecycle.tryLock())return;
        try{
            JSONObject state=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,
                    SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}")).optJSONObject("bridge");
            if(state!=null && !(networkBluetooth!=null&&networkBluetooth.pending()) && !nativePairing.reconnecting() && ("stopped".equals(state.optString("phase")) || "disconnected".equals(state.optString("phase"))))releaseConnection();
        }finally{lifecycle.unlock();}
    }
    @Override public synchronized int onStartCommand(Intent intent,int flags,int startId){
        // A killed process has lost its Noise session; never replay pairing or consent.
        if(!connectionStarted)stopSelf(startId);
        return START_NOT_STICKY;
    }
    private void refreshScreenScope(JSONObject state){
        JSONObject projection=state==null?null:state.optJSONObject("projection"),remote=state==null?null:state.optJSONObject("remote");
        boolean permitted=false;JSONArray grants=state==null?null:state.optJSONArray("granted_operations");
        if(grants!=null)for(int i=0;i<grants.length();i++)if("screen_video".equals(grants.optString(i)))permitted=true;
        boolean active=state!=null&&"active".equals(state.optString("phase"))&&hostSource==2&&remote!=null&&"tablet".equals(remote.optString("kind"))&&projection!=null;
        screenProofs.scope(active?state.optString("session"):"",active?projection.optString("id"):"",active&&permitted);
        for(MediaGrant grant:screenMedia.values())if(!screenProofs.approved(grant.caller(),grant.session(),grant.stream())){
            try{NativeCore.dispatch(Process.myUid(),Process.myPid(),true,hostSource,SystemClock.elapsedRealtime(),
                new JSONObject().put("op","bridge_media_revoke").put("session_id",grant.session()).put("stream_id",grant.stream()).toString());}
            catch(org.json.JSONException ignored){}screenMedia.remove(grant.stream(),grant);
        }
    }
    private boolean mediaCaller(CallerVerifier.Verified caller,boolean owner,int uid,int pid,JSONObject command){
        String purpose=command.optString("purpose"),role=command.optString("role");
        if(owner)return false; // Camera capture registration comes from the private consent controller, not public Binder JSON.
        if(caller==null)return false;
        if("receiver".equals(role)&&CallerPolicy.MIRROR.equals(caller.packageName()))return "screen_video".equals(purpose)||"camera_video".equals(purpose);
        return "sender".equals(role)&&"screen_video".equals(purpose)&&CallerPolicy.OPLUS_CAST.equals(caller.packageName())
            &&screenProofs.approved(new NativeScreenProofs.Caller(uid,pid),command.optString("session_id"),command.optLong("stream_id"));
    }
    private String screenRequest(CallerVerifier.Verified verified,int uid,int pid,JSONObject command)throws org.json.JSONException{
        if(verified==null)return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
        JSONObject status=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}"));
        refreshScreenScope(status.optJSONObject("bridge"));
        NativeScreenProofs.Caller caller=new NativeScreenProofs.Caller(uid,pid);String op=command.getString("op");
        try{
            if("screen_request_issue".equals(op)&&command.length()==6&&CallerPolicy.OPLUS.equals(verified.packageName())){
                String proof=screenProofs.issue(caller,command.getString("session_id"),command.getString("projection_id"),command.getString("native_uid"),command.getString("device_type"),exactLong(command,"stream_id"));
                return new JSONObject().put("status","ok").put("proof",proof).toString();
            }
            if("screen_request_active".equals(op)&&command.length()==2&&CallerPolicy.OPLUS.equals(verified.packageName())){
                return new JSONObject().put("status","ok").put("stream_id",screenProofs.activeForIssuer(caller,command.getString("session_id"))).toString();
            }
            if("screen_request_claim".equals(op)&&command.length()==6&&CallerPolicy.OPLUS_CAST.equals(verified.packageName())){
                String proof=command.getString("proof");if(!proof.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
                var origin=new NativeScreenProofs.Caller(Math.toIntExact(exactLong(command,"original_caller_uid")),Math.toIntExact(exactLong(command,"original_caller_pid")));
                var claim=screenProofs.claim(caller,origin,proof,command.getString("native_uid"),command.getString("device_type"));
                return new JSONObject().put("status","ok").put("session_id",claim.session()).put("projection_id",claim.projection()).put("stream_id",claim.stream()).toString();
            }
            if("screen_request_allowed".equals(op)&&command.length()==3&&CallerPolicy.OPLUS_CAST.equals(verified.packageName())){
                screenProofs.nativeAllowed(caller,command.getString("session_id"),exactLong(command,"stream_id"));
                return "{\"status\":\"ok\"}";
            }
            if("screen_request_rendered".equals(op)&&command.length()==3&&CallerPolicy.OPLUS_CAST.equals(verified.packageName())){
                screenProofs.rendered(caller,command.getString("session_id"),exactLong(command,"stream_id"));
                return "{\"status\":\"ok\"}";
            }
            if("screen_request_media".equals(op)&&command.length()==3&&CallerPolicy.OPLUS.equals(verified.packageName())){
                boolean rendered=screenProofs.renderedForIssuer(caller,command.getString("session_id"),exactLong(command,"stream_id"));
                return new JSONObject().put("status","ok").put("rendered",rendered).toString();
            }
            if("screen_request_renew".equals(op)&&command.length()==3&&CallerPolicy.OPLUS_CAST.equals(verified.packageName())){
                screenProofs.renew(caller,command.getString("session_id"),exactLong(command,"stream_id"));return "{\"status\":\"ok\"}";
            }
            if("screen_request_revoke".equals(op)&&command.length()==3&&(CallerPolicy.OPLUS.equals(verified.packageName())||CallerPolicy.OPLUS_CAST.equals(verified.packageName()))){
                screenProofs.revoke(caller,command.getString("session_id"),exactLong(command,"stream_id"));return "{\"status\":\"ok\"}";
            }
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException rejected){return "{\"status\":\"error\",\"code\":\"screen_request_unproved\"}";}
        return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
    }
    private static long exactLong(JSONObject value,String key)throws org.json.JSONException{
        Object number=value.get(key);if(!(number instanceof Integer)&&!(number instanceof Long))throw new IllegalArgumentException();return ((Number)number).longValue();
    }
    private String browserRelayRequest(CallerVerifier.Verified caller, boolean owner, int uid, int pid, int source,
                                       JSONObject command) throws org.json.JSONException {
        String op = command.getString("op");
        boolean localCommand = op.equals("browser_relay_peer") || op.equals("browser_relay_offer")
                || op.equals("browser_relay_status") || op.equals("browser_relay_click");
        if (owner != localCommand || !owner && (caller == null || !CallerPolicy.executorAllowed(caller.packageName(), "app_relay")))
            return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
        JSONObject projection;
        if (owner) {
            JSONObject snapshot = new JSONObject(NativeCore.dispatch(uid, pid, true, source, SystemClock.elapsedRealtime(),
                    "{\"op\":\"bridge_status\"}")).optJSONObject("bridge");
            if (snapshot == null || !"active".equals(snapshot.optString("phase")) || !relayGranted(snapshot.optJSONArray("granted_operations")))
                return "{\"status\":\"error\",\"code\":\"peer_offline\"}";
            projection = snapshot.optJSONObject("projection");
        } else {
            JSONObject authorized = new JSONObject(NativeCore.dispatch(uid, pid, false, source, SystemClock.elapsedRealtime(),
                    new JSONObject().put("op", "bridge_projections").put("token", command.getString("token"))
                            .put("sequence", command.getLong("sequence")).toString()));
            if (!"ok".equals(authorized.optString("status"))) return authorized.toString();
            projection = authorized.optJSONObject("projection");
            if (projection == null || !relayGranted(projection.optJSONArray("local_grants")))
                return "{\"status\":\"error\",\"code\":\"peer_offline\"}";
        }
        if (projection == null || !relayGranted(projection.optJSONArray("operations")))
            return "{\"status\":\"error\",\"code\":\"peer_offline\"}";
        String session = projection.getString("session_id");
        if (!op.equals("browser_relay_peer") && !session.equals(command.getString("session")))
            return "{\"status\":\"error\",\"code\":\"stale_session\"}";
        JSONObject response = new JSONObject().put("status", "ok");
        try {
            switch (op) {
                case "browser_relay_peer" -> {
                    return response.put("session", session).put("name", projection.getJSONObject("real").getString("name")).toString();
                }
                case "browser_relay_offer" -> {
                    return response.put("id", browserRelay.submit(session, command.getString("url")).id()).toString();
                }
                case "browser_relay_status" -> {
                    return response.put("state", browserRelay.status(session, command.getString("id"))).toString();
                }
                case "browser_relay_click" -> {
                    if (!browserNotice.click(session, command.getString("package")))
                        return response.put("status", "error").put("code", "stale_offer").toString();
                    return response.toString();
                }
                case "browser_relay_claim" -> {
                    BrowserRelayOffer offer = browserRelay.claim(session, pid);
                    if (offer != null) response.put("offer", offer.encode());
                    String clicked = browserNotice.takeClick(session);
                    if (clicked != null) response.put("clicked_package", clicked);
                    return response.toString();
                }
                case "browser_relay_notice" -> {
                    String pkg = command.getString("package");
                    if (command.getBoolean("show"))
                        browserNotice.show(session, pkg, projection.getJSONObject("real").getString("name"), command.optString("app_label"));
                    else browserNotice.hide(session, pkg);
                    return response.toString();
                }
                case "browser_relay_finish" -> {
                    browserRelay.finish(session, pid, command.getString("id"), command.getBoolean("delivered"));
                    return response.toString();
                }
                default -> { return "{\"status\":\"error\",\"code\":\"invalid_request\"}"; }
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return response.put("status", "error").put("code", rejected.getMessage()).toString();
        }
    }
    private static boolean relayGranted(JSONArray operations) {
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("app_relay".equals(operations.optString(i))) return true;
        return false;
    }
    private final IBridge.Stub binder = new IBridge.Stub() {
        @Override public android.os.ParcelFileDescriptor openMediaPipe(String command){
            int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();long identity=Binder.clearCallingIdentity();
            android.os.ParcelFileDescriptor[] pair=null;
            try{
                if(command==null||command.length()>2048)return null;
                JSONObject parsed=new JSONObject(command);
                if(!"bridge_media_attach".equals(parsed.optString("op")))return null;
                CallerVerifier.Verified verified=CallerVerifier.resolve(getPackageManager(),uid);
                if(verified==null&&mirrorProof.verifies(uid,pid))verified=new CallerVerifier.Verified(1,CallerPolicy.MIRROR);
                if(verified==null||!runtimes.accepts(uid,pid,parsed.optString("token"),BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime())
                        ||!mediaCaller(verified,false,uid,pid,parsed))return null;
                pair=android.os.ParcelFileDescriptor.createSocketPair();
                boolean attached=NativeCore.attachMedia(uid,pid,verified.source(),SystemClock.elapsedRealtime(),command,pair[1].detachFd());
                if(!attached)return null;
                android.os.ParcelFileDescriptor result=pair[0];pair[0]=null;return result;
            }catch(org.json.JSONException|java.io.IOException|RuntimeException failure){return null;}
            finally{if(pair!=null)for(var fd:pair)if(fd!=null)try{fd.close();}catch(java.io.IOException ignored){}Binder.restoreCallingIdentity(identity);}
        }
        @Override public String transactJson(String command) {
            int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
            boolean owner = uid == Process.myUid();
            if (command == null || command.length() > 8192) return "{\"status\":\"error\",\"code\":\"invalid_request\"}";
            // Capture the kernel caller first; perform package lookups as this service, then
            // explicitly pass the ORIGINAL UID/PID into Rust's session check.
            long identity=Binder.clearCallingIdentity();
            boolean changingLifecycle=false;
            try {
            CallerVerifier.Verified verified=owner?null:CallerVerifier.resolve(getPackageManager(),uid);
            if(!owner&&verified==null&&mirrorProof.verifies(uid,pid))verified=new CallerVerifier.Verified(1,CallerPolicy.MIRROR);
            int source=owner?hostSource:verified==null?0:verified.source();
            if(!owner&&source==0&&uid==CallerVerifier.mirrorUid(getPackageManager()))mirrorProof.requestVerification();
            if (!owner && source == 0) return "{\"status\":\"error\",\"code\":\"caller_rejected\"}";
            try {
                JSONObject parsed=new JSONObject(command);String op=parsed.optString("op");
                changingLifecycle=java.util.Set.of("bridge_bluetooth_start","native_pairing","bridge_listen","bridge_connect","bridge_stop","bridge_disconnect","bridge_abort").contains(op);
                if(changingLifecycle)lifecycle.lock();
                if("bridge_bluetooth_start".equals(op)){
                    if(!owner)return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    if(!"ok".equals(new JSONObject(initialization).optString("status")))return initialization;
                    nativePairing.pause();
                    NativeCore.dispatch(uid,pid,true,source,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_stop\"}");
                    if(networkBluetooth!=null)networkBluetooth.close();
                    wifiRoute.release();foreground();
                    NativeCore.dispatch(uid,pid,true,source,SystemClock.elapsedRealtime(),"{\"op\":\"set_enabled\",\"enabled\":true}");
                    networkBluetooth=new NetworkBluetoothSession(BridgeService.this);
                    try{return networkBluetooth.start(parsed).toString();}
                    catch(Exception failure){networkBluetooth.close();networkBluetooth=null;
                        return new JSONObject().put("status","error").put("code",failure.getMessage()).toString();}
                }
                if("network_status_publish".equals(op)){
                    if(owner||!CallerPolicy.executorAllowed(verified.packageName(),"network_share"))return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    JSONObject authorized=new JSONObject(NativeCore.dispatch(uid,pid,false,source,SystemClock.elapsedRealtime(),
                        new JSONObject().put("op","bridge_projections").put("token",parsed.getString("token")).put("sequence",parsed.getLong("sequence")).toString()));
                    JSONObject projection=authorized.optJSONObject("projection");
                    if(projection==null||!projection.optString("session_id").equals(parsed.optString("session")))return "{\"status\":\"error\",\"code\":\"stale_session\"}";
                    String phase=parsed.getString("phase"),upstream=parsed.getString("upstream"),error=parsed.getString("error");
                    if(!java.util.Set.of("REQUESTED","STARTING","READY","ACTIVE","STOPPING","STOPPED","FAILED","RECOVERY_REQUIRED").contains(phase)
                        ||!java.util.Set.of("","WIFI","CELLULAR").contains(upstream)||!error.matches("[a-z_]{0,128}"))throw new org.json.JSONException("invalid_state");
                    networkStatus=new JSONObject().put("session",parsed.getString("session")).put("phase",phase).put("upstream",upstream).put("error",error).put("available",parsed.optBoolean("available"));
                    networkObservedAt=SystemClock.elapsedRealtime();
                    return "{\"status\":\"ok\"}";
                }
                if("network_control".equals(op)){
                    if(!owner)return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    JSONObject state=new JSONObject(NativeCore.dispatch(uid,pid,true,source,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}")).getJSONObject("bridge");
                    if(!"active".equals(state.optString("phase"))||!"bluetooth".equals(state.optString("control_transport")))
                        return "{\"status\":\"error\",\"code\":\"bluetooth_session_required\"}";
                    String action=parsed.getString("action");if(!java.util.Set.of("start","stop").contains(action))throw new IllegalArgumentException();
                    sendBroadcast(new Intent("io.github.yuejie.connect.NETWORK_CONTROL").setPackage(source==1?CallerPolicy.XIAOMI:CallerPolicy.OPLUS)
                        .putExtra("session",state.getString("session")).putExtra("action",action));
                    return "{\"status\":\"ok\",\"phase\":\"requested\"}";
                }
                if(owner&&"bridge_bluetooth_status".equals(op))return networkBluetooth==null?"{\"status\":\"ok\",\"phase\":\"stopped\"}":networkBluetooth.status().toString();
                if(owner&&"bridge_runtime_status".equals(op))return runtimes.snapshot(BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime()).put("native_pairing",nativePairing.inspect())
                    .put("host_initialization",new JSONObject(initialization)).put("xiaomi_package",CallerVerifier.inspect(getPackageManager(),CallerPolicy.XIAOMI)).toString();
                if(!owner){
                    // Bind revision agreement to Rust's actual kernel-caller IPC lease. Content
                    // requests keep their existing JSON budget and still undergo Rust replay checks.
                    boolean opening="open".equals(op),direct=op.startsWith("screen_request_")||"file_cleanup_state".equals(op)
                            ||("native_pairing".equals(op)&&java.util.Set.of("start","forget").contains(parsed.optString("action")));
                    boolean current=opening||direct
                        ?runtimes.observe(uid,pid,verified.packageName(),parsed.optString("runtime_revision",""),BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime())
                        :runtimes.accepts(uid,pid,parsed.optString("token",""),BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime());
                    if(!current)return "{\"status\":\"error\",\"code\":\"runtime_mismatch\"}";
                    parsed.remove("runtime_revision");command=parsed.toString();
                }
                if(op.startsWith("screen_request_"))return screenRequest(verified,uid,pid,parsed);
                if(op.startsWith("browser_relay_"))return browserRelayRequest(verified,owner,uid,pid,source,parsed);
                if("native_pairing".equals(op)){
                    boolean nfcTarget="nfc_target".equals(parsed.optString("action"));
                    if(!owner&&!NativePairingPolicy.caller(verified.packageName())&&!(nfcTarget&&CallerPolicy.MIRROR.equals(verified.packageName())))return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    if(nfcTarget&&(owner||!CallerPolicy.MIRROR.equals(verified.packageName())))
                        return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    // Consume the real Rust IPC sequence before invoking the narrowly scoped
                    // pairing controller. Package/revision checks alone are not a replay fence.
                    // Starting discovery is the consent entry point while the IPC engine is
                    // disabled. It is idempotent and cannot replace an in-flight/active peer.
                    if(!owner&&!java.util.Set.of("start","forget").contains(parsed.optString("action"))){
                    JSONObject authorized=new JSONObject(NativeCore.dispatch(uid,pid,false,source,SystemClock.elapsedRealtime(),
                            new JSONObject().put("op","bridge_projections").put("token",parsed.getString("token")).put("sequence",parsed.getLong("sequence")).toString()));
                    if(!"ok".equals(authorized.optString("status")))return authorized.toString();
                    }
                    try{return nativePairing.command(parsed).toString();}
                    catch(RuntimeException failure){String code=failure.getMessage();return new JSONObject().put("status","error").put("code",code!=null&&code.matches("[a-z_]{1,64}")?code:"pairing_unavailable").toString();}
                }
                if("bridge_disconnect".equals(op)&&(owner||!CallerPolicy.OPLUS.equals(verified.packageName())))
                    return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                if("bridge_media_register".equals(op)&&!mediaCaller(verified,owner,uid,pid,parsed))
                    return "{\"status\":\"error\",\"code\":\"media_native_consent_required\"}";
                if("file_cleanup_state".equals(op)){
                    if(parsed.length()!=2||(owner?source!=2:!CallerPolicy.executorAllowed(verified.packageName(),"file_offer")))return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    JSONObject state=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}")).optJSONObject("bridge");
                    if(state==null)return "{\"status\":\"error\",\"code\":\"service_unavailable\"}";
                    return "active".equals(state.optString("phase"))&&parsed.getString("session_id").equals(state.optString("session"))
                        ?"{\"status\":\"ok\",\"active\":true}":"{\"status\":\"error\",\"code\":\"stale_session\"}";
                }
                boolean fileAllocate="bridge_file_allocate".equals(op),fileRelease="bridge_file_release".equals(op);
                if((fileAllocate||fileRelease) && (owner
                        ? source!=2 || (fileRelease && !"outgoing".equals(parsed.optString("direction")))
                        : !CallerPolicy.executorAllowed(verified.packageName(),"file_offer")))
                    return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                if(owner&&"notification_fixture".equals(op)){
                    try{return ((JSONObject)Class.forName("io.github.yuejie.connect.NotificationSyncFixture").getMethod("execute",android.content.Context.class,JSONObject.class).invoke(null,BridgeService.this,parsed)).toString();}
                    catch(ReflectiveOperationException|RuntimeException unavailable){return "{\"status\":\"error\",\"code\":\"debug_fixture_unavailable\"}";}
                }
                if(op.equals("bridge_register_executor")){
                    JSONArray operations=parsed.optJSONArray("operations");
                    if(operations==null||operations.length()>32)return "{\"status\":\"error\",\"code\":\"invalid_request\"}";
                    for(int i=0;i<operations.length();i++)if(!(owner?CallerPolicy.moduleExecutorAllowed(operations.getString(i)):CallerPolicy.executorAllowed(verified.packageName(),operations.getString(i))))
                        return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                }
                if(owner&&op.equals("notification_settings"))return notifications==null?"{\"status\":\"error\",\"code\":\"notification_unavailable\"}":notifications.settings().toString();
                if(owner&&op.equals("notification_packages")){
                    if(notifications==null)return "{\"status\":\"error\",\"code\":\"notification_unavailable\"}";
                    try{return notifications.configure(parsed.optJSONArray("packages"),parsed.opt("native_enabled")).toString();}
                    catch(IllegalArgumentException invalid){return "{\"status\":\"error\",\"code\":\"invalid_notification_packages\"}";}
                }
                if(owner && (op.equals("bridge_listen") || op.equals("bridge_connect"))) {
                    if(!"ok".equals(new JSONObject(initialization).optString("status"))) return initialization;
                    // This process only carries the explicitly selected LAN bridge. Leave the
                    // vendor processes and device-wide default/VPN routing unchanged.
                    if(!wifiRoute.acquire())return "{\"status\":\"error\",\"code\":\"wifi_route_unavailable\"}";
                    foreground();
                }
                String result=NativeCore.dispatch(uid,pid,owner,source,SystemClock.elapsedRealtime(),command);
                if("ok".equals(new JSONObject(result).optString("status"))){
                    if(owner&&(op.equals("bridge_listen")||op.equals("bridge_connect")))nativePairing.resume();
                    if(op.equals("bridge_disconnect")||owner&&(op.equals("bridge_stop")||op.equals("bridge_abort")))nativePairing.pause();
                }
                if("bridge_disconnect".equals(op)&&"ok".equals(new JSONObject(result).optString("status"))){
                    releaseConnection();
                }
                if("bridge_media_register".equals(op)&&"sender".equals(parsed.optString("role"))&&"ok".equals(new JSONObject(result).optString("status"))){
                    long stream=parsed.getLong("stream_id");screenMedia.put(stream,new MediaGrant(parsed.getString("session_id"),stream,new NativeScreenProofs.Caller(uid,pid)));
                }
                if(!owner&&"open".equals(op)){
                    JSONObject opened=new JSONObject(result);
                    if("ok".equals(opened.optString("status")))runtimes.opened(uid,pid,opened.optString("token",""),SystemClock.elapsedRealtime());
                }
                if(owner && op.equals("bridge_stop")) {releaseConnection();}
                if(owner&&"bridge_status".equals(op)&&networkStatus!=null){
                    JSONObject response=new JSONObject(result),state=response.optJSONObject("bridge"),network=networkStatus;
                    if(state!=null&&state.optString("session").equals(network.optString("session")))response.put("network_share",network);
                    return response.toString();
                }
                return result;
            } catch(org.json.JSONException e) {return "{\"status\":\"error\",\"code\":\"invalid_request\"}";}
            } finally {if(changingLifecycle)lifecycle.unlock();Binder.restoreCallingIdentity(identity);}
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        pairingWorker.shutdownNow();
        lifecycle.lock();
        try{
        if(nativePairing!=null)nativePairing.close();
        monitor.removeCallbacks(monitorTask);
        if(notifications!=null)notifications.close();
        browserNotice.session("");
        screenProofs.scope("","",false);
        mirrorProof.close();
        wifiRoute.release();
        NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_stop\"}");
        }finally{lifecycle.unlock();}
        super.onDestroy();
    }
}
