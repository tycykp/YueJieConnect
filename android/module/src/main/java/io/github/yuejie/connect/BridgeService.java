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
    private NativePairingService nativePairing;
    private VendorProcessProof mirrorProof;
    private SystemNotificationBridge notifications;
    private int hostSource;
    private final NativeRuntimeRegistry runtimes=new NativeRuntimeRegistry();
    private final NativeScreenProofs screenProofs=new NativeScreenProofs();
    private record MediaGrant(String session,long stream,NativeScreenProofs.Caller caller){}
    private final java.util.Map<Long,MediaGrant> screenMedia=new java.util.concurrent.ConcurrentHashMap<>();
    private String initialization="{\"status\":\"error\",\"code\":\"profile_unavailable\"}";
    private final android.os.Handler monitor=new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastState="";
    private final Runnable monitorTask=new Runnable(){public void run(){
        if(nativePairing!=null)nativePairing.tick();
        try{JSONObject result=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}"));
            JSONObject state=result.optJSONObject("bridge");refreshScreenScope(state);if(state!=null){String marker="phase="+state.optString("phase")+" error="+state.optString("last_error")+" errno="+state.optString("socket_errno")+" rpc="+state.optLong("requests_sent")+"/"+state.optLong("requests_received")+"/"+state.optLong("responses_received");
                if(!marker.equals(lastState)){lastState=marker;android.util.Log.i("YueJieBridge",marker);}}
        }catch(org.json.JSONException|RuntimeException ignored){}monitor.postDelayed(this,1000);
    }};
    @Override public void onCreate() {
        super.onCreate();
        wifiRoute=new WifiRoute(getSystemService(android.net.ConnectivityManager.class));
        nativePairing=new NativePairingService(this,new NativePairingService.Host(){
            public JSONObject dispatch(JSONObject command)throws org.json.JSONException{
                JSONObject result=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,hostSource,SystemClock.elapsedRealtime(),command.toString()));
                if("bridge_abort".equals(command.optString("op"))&&"ok".equals(result.optString("status"))){wifiRoute.release();stopForeground(STOP_FOREGROUND_REMOVE);}
                return result;
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
            JSONObject profile=new JSONObject().put("peer_key",key).put("platform",source==1?"hyper_os4":"color_os17")
                .put("kind",getResources().getConfiguration().smallestScreenWidthDp>=600?"tablet":"phone")
                .put("name",name).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
                .put("os_build",Build.VERSION.INCREMENTAL).put("operations",new JSONArray());
            NativeCore.setBatteryReader(()->LocalDeviceOperations.battery(getApplicationContext()));
            initialization=NativeCore.initialize(new java.io.File(getFilesDir(),"bridge-identity.key").getAbsolutePath(),profile.toString());
            if("ok".equals(new JSONObject(initialization).optString("status")))try{notifications=new SystemNotificationBridge(this);}
            catch(RuntimeException unavailable){android.util.Log.w("YueJieBridge","notification_endpoint_unavailable");}
        } catch(org.json.JSONException | RuntimeException error) {
            initialization="{\"status\":\"error\",\"code\":\"profile_setup_failed\",\"detail\":\""+error.getClass().getSimpleName()+"\"}";
        } finally {android.util.Log.i("YueJieBridge","initialization="+initialization);monitor.post(monitorTask);}
    }
    private int sourceFor(String packageName){
        try{return CallerVerifier.verify(getPackageManager(),getPackageManager().getApplicationInfo(packageName,0).uid);}
        catch(PackageManager.NameNotFoundException ignored){return 0;}
    }
    private void foreground() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("bridge","设备桥接",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,"bridge").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name)).setContentText("设备桥接正在运行，点此查看或断开")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(1,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
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
            try {
            CallerVerifier.Verified verified=owner?null:CallerVerifier.resolve(getPackageManager(),uid);
            if(!owner&&verified==null&&mirrorProof.verifies(uid,pid))verified=new CallerVerifier.Verified(1,CallerPolicy.MIRROR);
            int source=owner?hostSource:verified==null?0:verified.source();
            if(!owner&&source==0&&uid==CallerVerifier.mirrorUid(getPackageManager()))mirrorProof.requestVerification();
            if (!owner && source == 0) return "{\"status\":\"error\",\"code\":\"caller_rejected\"}";
            try {
                JSONObject parsed=new JSONObject(command);String op=parsed.optString("op");
                if(owner&&"bridge_runtime_status".equals(op))return runtimes.snapshot(BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime()).put("native_pairing",nativePairing.inspect())
                    .put("host_initialization",new JSONObject(initialization)).put("xiaomi_package",CallerVerifier.inspect(getPackageManager(),CallerPolicy.XIAOMI)).toString();
                if(!owner){
                    // Bind revision agreement to Rust's actual kernel-caller IPC lease. Content
                    // requests keep their existing JSON budget and still undergo Rust replay checks.
                    boolean opening="open".equals(op),direct=op.startsWith("screen_request_")||"file_cleanup_state".equals(op)
                            ||("native_pairing".equals(op)&&"start".equals(parsed.optString("action")));
                    boolean current=opening||direct
                        ?runtimes.observe(uid,pid,verified.packageName(),parsed.optString("runtime_revision",""),BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime())
                        :runtimes.accepts(uid,pid,parsed.optString("token",""),BridgeClient.runtimeRevision(),SystemClock.elapsedRealtime());
                    if(!current)return "{\"status\":\"error\",\"code\":\"runtime_mismatch\"}";
                    parsed.remove("runtime_revision");command=parsed.toString();
                }
                if(op.startsWith("screen_request_"))return screenRequest(verified,uid,pid,parsed);
                if("native_pairing".equals(op)){
                    if(owner||!NativePairingPolicy.caller(verified.packageName()))return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    // Consume the real Rust IPC sequence before invoking the narrowly scoped
                    // pairing controller. Package/revision checks alone are not a replay fence.
                    // Starting discovery is the consent entry point while the IPC engine is
                    // disabled. It is idempotent and cannot replace an in-flight/active peer.
                    if(!"start".equals(parsed.optString("action"))){
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
                    if(owner||parsed.length()!=2||!CallerPolicy.executorAllowed(verified.packageName(),"file_offer"))return "{\"status\":\"error\",\"code\":\"permission_denied\"}";
                    JSONObject state=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}")).optJSONObject("bridge");
                    if(state==null)return "{\"status\":\"error\",\"code\":\"service_unavailable\"}";
                    return "active".equals(state.optString("phase"))&&parsed.getString("session_id").equals(state.optString("session"))
                        ?"{\"status\":\"ok\",\"active\":true}":"{\"status\":\"error\",\"code\":\"stale_session\"}";
                }
                if(("bridge_file_allocate".equals(op)||"bridge_file_release".equals(op))
                        &&(owner||!CallerPolicy.executorAllowed(verified.packageName(),"file_offer")))
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
                    try{return notifications.configure(parsed.optJSONArray("packages")).toString();}
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
                if("bridge_disconnect".equals(op)&&"ok".equals(new JSONObject(result).optString("status"))){
                    wifiRoute.release();stopForeground(STOP_FOREGROUND_REMOVE);
                }
                if("bridge_media_register".equals(op)&&"sender".equals(parsed.optString("role"))&&"ok".equals(new JSONObject(result).optString("status"))){
                    long stream=parsed.getLong("stream_id");screenMedia.put(stream,new MediaGrant(parsed.getString("session_id"),stream,new NativeScreenProofs.Caller(uid,pid)));
                }
                if(!owner&&"open".equals(op)){
                    JSONObject opened=new JSONObject(result);
                    if("ok".equals(opened.optString("status")))runtimes.opened(uid,pid,opened.optString("token",""),SystemClock.elapsedRealtime());
                }
                if(owner && op.equals("bridge_stop")) {wifiRoute.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
                return result;
            } catch(org.json.JSONException e) {return "{\"status\":\"error\",\"code\":\"invalid_request\"}";}
            } finally {Binder.restoreCallingIdentity(identity);}
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        if(nativePairing!=null)nativePairing.close();
        monitor.removeCallbacks(monitorTask);
        if(notifications!=null)notifications.close();
        screenProofs.scope("","",false);
        mirrorProof.close();
        wifiRoute.release();
        NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_stop\"}");
        super.onDestroy();
    }
}
