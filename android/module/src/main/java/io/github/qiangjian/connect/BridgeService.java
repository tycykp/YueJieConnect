package io.github.qiangjian.connect;

import android.app.Service;
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
    private String initialization="{\"status\":\"error\",\"code\":\"profile_unavailable\"}";
    private final android.os.Handler monitor=new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastState="";
    private final Runnable monitorTask=new Runnable(){public void run(){
        try{JSONObject result=new JSONObject(NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_status\"}"));
            JSONObject state=result.optJSONObject("bridge");if(state!=null){String marker="phase="+state.optString("phase")+" error="+state.optString("last_error")+" errno="+state.optString("socket_errno")+" rpc="+state.optLong("requests_sent")+"/"+state.optLong("requests_received")+"/"+state.optLong("responses_received");
                if(!marker.equals(lastState)){lastState=marker;android.util.Log.i("QiangJianBridge",marker);}}
        }catch(org.json.JSONException|RuntimeException ignored){}monitor.postDelayed(this,1000);
    }};
    @Override public void onCreate() {
        super.onCreate();
        wifiRoute=new WifiRoute(getSystemService(android.net.ConnectivityManager.class));
        try {
            int xiaomi=sourceFor(CallerPolicy.XIAOMI),oplus=sourceFor(CallerPolicy.OPLUS);
            int source=xiaomi!=0 && oplus==0?xiaomi:oplus!=0 && xiaomi==0?oplus:0;
            if(source==0){initialization="{\"status\":\"error\",\"code\":\"unsupported_host_package\"}";return;}
            String name=null;
            try{name=Settings.Global.getString(getContentResolver(),"device_name");}
            catch(SecurityException restricted){android.util.Log.i("QiangJianBridge","device_name_restricted_using_model");}
            if(name==null || name.isBlank()) name=Build.MODEL;
            JSONArray key=new JSONArray();for(int i=0;i<32;i++)key.put(0);
            JSONObject profile=new JSONObject().put("peer_key",key).put("platform",source==1?"hyper_os4":"color_os17")
                .put("kind",getResources().getConfiguration().smallestScreenWidthDp>=600?"tablet":"phone")
                .put("name",name).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
                .put("os_build",Build.VERSION.INCREMENTAL).put("operations",new JSONArray());
            NativeCore.setBatteryReader(()->LocalDeviceOperations.battery(getApplicationContext()));
            initialization=NativeCore.initialize(new java.io.File(getFilesDir(),"bridge-identity.key").getAbsolutePath(),profile.toString());
        } catch(org.json.JSONException | RuntimeException error) {
            initialization="{\"status\":\"error\",\"code\":\"profile_setup_failed\",\"detail\":\""+error.getClass().getSimpleName()+"\"}";
        } finally {android.util.Log.i("QiangJianBridge","initialization="+initialization);monitor.post(monitorTask);}
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
    private final IBridge.Stub binder = new IBridge.Stub() {
        @Override public String transactJson(String command) {
            int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
            boolean owner = uid == Process.myUid();
            if (command == null || command.length() > 8192) return "{\"status\":\"error\",\"code\":\"invalid_request\"}";
            // Capture the kernel caller first; perform package lookups as this service, then
            // explicitly pass the ORIGINAL UID/PID into Rust's session check.
            long identity=Binder.clearCallingIdentity();
            try {
            int source = owner ? 0 : CallerVerifier.verify(getPackageManager(), uid);
            if (!owner && source == 0) return "{\"status\":\"error\",\"code\":\"caller_rejected\"}";
            try {
                String op=new JSONObject(command).optString("op");
                if(owner && (op.equals("bridge_listen") || op.equals("bridge_connect"))) {
                    if(!"ok".equals(new JSONObject(initialization).optString("status"))) return initialization;
                    if(checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED)
                        return "{\"status\":\"error\",\"code\":\"local_network_permission_required\"}";
                    // This process only carries the explicitly selected LAN bridge. Leave the
                    // vendor processes and device-wide default/VPN routing unchanged.
                    if(!wifiRoute.acquire())return "{\"status\":\"error\",\"code\":\"wifi_route_unavailable\"}";
                    foreground();
                }
                String result=NativeCore.dispatch(uid,pid,owner,source,SystemClock.elapsedRealtime(),command);
                if(owner && op.equals("bridge_stop")) {wifiRoute.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
                return result;
            } catch(org.json.JSONException e) {return "{\"status\":\"error\",\"code\":\"invalid_request\"}";}
            } finally {Binder.restoreCallingIdentity(identity);}
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        monitor.removeCallbacks(monitorTask);
        wifiRoute.release();
        NativeCore.dispatch(Process.myUid(),Process.myPid(),true,0,SystemClock.elapsedRealtime(),"{\"op\":\"bridge_stop\"}");
        super.onDestroy();
    }
}
