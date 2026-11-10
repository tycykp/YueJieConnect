package io.github.yuejie.connect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android system notification path; OEM source hooks have their own separately verified lifecycle. */
final class SystemNotificationBridge implements AutoCloseable {
    private final BridgeClient client;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences settings;
    private final NotificationSyncRuntime runtime;
    private final NotificationSyncEndpoint endpoint;
    private final List<BridgeClient.Subscription> registrations;
    private final BridgeClient.Subscription projectionListener;
    private volatile Set<String> packages;
    private volatile boolean nativeEnabled;
    private boolean postingGranted;
    private volatile boolean closed;
    private final NotificationSyncRuntime.Grants grants=new NotificationSyncRuntime.Grants(){
        @Override public boolean allows(String peer,String session,String operation){
            JSONObject projection=client.projection();
            return !closed&&nativeEnabled
                    &&NotificationSyncBridgeTransport.supports(projection,operation)
                    &&peer.equals(projection.optString("id"))&&session.equals(projection.optString("session_id"));
        }
        @Override public boolean allowsPackage(String pkg){return !closed&&packages.contains(pkg);}
    };
    private final Runnable monitor=new Runnable(){@Override public void run(){if(!closed){refresh();main.postDelayed(this,1000);}}};
    SystemNotificationBridge(Context context,BridgeClient client){
        this.client=client;
        settings=context.getSharedPreferences("notification_policy",Context.MODE_PRIVATE);
        packages=Set.copyOf(settings.getStringSet("packages",Set.of()));
        nativeEnabled=settings.getBoolean("native_enabled",false);
        runtime=new NotificationSyncRuntime(context,new NotificationSyncBridgeTransport(client,()->!closed),grants);
        endpoint=new NotificationSyncEndpoint(runtime,grants);

        // Capabilities are negotiated symmetrically. A device can send without posting permission
        // or receive without listener access. The runtime checks the permission for each effect.
        registrations=List.of(client.registerExecutor("notification_post",endpoint),
                client.registerExecutor("notification_remove",endpoint));
        projectionListener=client.addProjectionListener(value->{
            endpoint.onProjection(value==null?"":value.optString("id"),value==null?"":value.optString("session_id"));
        });
        postingGranted=runtime.postingGranted();
        main.post(monitor);
    }
    private void refresh(){
        boolean granted=runtime.postingGranted();
        if(postingGranted&&!granted)endpoint.authorizationChanged();
        postingGranted=granted;
    }
    JSONObject settings() throws org.json.JSONException {
        return new JSONObject().put("status","ok").put("packages",new JSONArray(packages)).put("native_enabled",nativeEnabled);
    }
    void addNativeGrants(JSONObject confirmation) throws org.json.JSONException {
        if(!nativeEnabled)return;
        confirmation.getJSONArray("operations").put("notification_post").put("notification_remove").put("notification_action");
    }
    synchronized JSONObject configure(JSONArray selected, Object nativeSetting) throws org.json.JSONException {
        if(selected==null||selected.length()>64)throw new IllegalArgumentException("invalid_notification_packages");
        if(nativeSetting!=null&&!(nativeSetting instanceof Boolean))throw new IllegalArgumentException("invalid_notification_setting");
        Set<String> next=new HashSet<>();
        for(int i=0;i<selected.length();i++){
            Object value=selected.get(i);
            if(!(value instanceof String pkg)||pkg.length()>200||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("invalid_notification_packages");
            next.add(pkg);
        }
        boolean enableNative=nativeSetting instanceof Boolean enabled?enabled:nativeEnabled;
        if(!settings.edit().putStringSet("packages",next).putBoolean("native_enabled",enableNative).commit())
            throw new IllegalStateException("notification_policy_write_failed");
        packages=Set.copyOf(next);
        nativeEnabled=enableNative;
        main.post(()->{if(!closed){endpoint.authorizationChanged();refresh();}});
        return new JSONObject().put("status","ok");
    }
    @Override public void close(){
        if(closed)return;closed=true;main.removeCallbacks(monitor);
        projectionListener.close();
        for(var subscription:registrations)subscription.close();
        endpoint.close();runtime.close();
    }
}
