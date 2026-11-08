package io.github.qiangjian.connect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android system notification path; OEM source hooks have their own separately verified lifecycle. */
final class SystemNotificationBridge implements AutoCloseable {
    private final BridgeClient client=new BridgeClient();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences settings;
    private final NotificationSyncRuntime runtime;
    private final NotificationSyncEndpoint endpoint;
    private final Map<String,BridgeClient.Subscription> registrations=new LinkedHashMap<>();
    private final BridgeClient.Subscription projectionListener;
    private volatile Set<String> packages;
    private volatile boolean closed;
    private final NotificationSyncRuntime.Grants grants=new NotificationSyncRuntime.Grants(){
        @Override public boolean allows(String peer,String session,String operation){
            JSONObject projection=client.projection();
            return !closed&&NotificationSyncBridgeTransport.supports(projection,operation)
                    &&peer.equals(projection.optString("id"))&&session.equals(projection.optString("session_id"));
        }
        @Override public boolean allowsPackage(String pkg){return !closed&&packages.contains(pkg);}
    };
    private final Runnable monitor=new Runnable(){@Override public void run(){if(!closed){refresh();main.postDelayed(this,1000);}}};
    SystemNotificationBridge(Context context){
        settings=context.getSharedPreferences("notification_policy",Context.MODE_PRIVATE);
        packages=Set.copyOf(settings.getStringSet("packages",Set.of()));
        runtime=new NotificationSyncRuntime(context,new NotificationSyncBridgeTransport(client,()->!closed),grants);
        endpoint=new NotificationSyncEndpoint(runtime,grants);
        client.attach(context);
        projectionListener=client.addProjectionListener(value->{
            endpoint.onProjection(value==null?"":value.optString("id"),value==null?"":value.optString("session_id"));
        });
        main.post(monitor);
    }
    private void refresh(){
        Set<String> ready=new HashSet<>();
        if(runtime.postingGranted()){ready.add("notification_post");ready.add("notification_remove");}
        if(runtime.accessGranted()&&!packages.isEmpty())ready.add("notification_action");
        boolean changed=!ready.equals(registrations.keySet());
        for(String op:new ArrayList<>(registrations.keySet()))if(!ready.contains(op))registrations.remove(op).close();
        for(String op:ready)if(!registrations.containsKey(op))registrations.put(op,client.registerExecutor(op,endpoint));
        if(changed)endpoint.authorizationChanged();
    }
    JSONObject settings() throws org.json.JSONException {
        return new JSONObject().put("status","ok").put("packages",new JSONArray(packages));
    }
    JSONObject configure(JSONArray selected) throws org.json.JSONException {
        if(selected==null||selected.length()>64)throw new IllegalArgumentException("invalid_notification_packages");
        Set<String> next=new HashSet<>();
        for(int i=0;i<selected.length();i++){
            Object value=selected.get(i);
            if(!(value instanceof String pkg)||pkg.length()>200||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("invalid_notification_packages");
            next.add(pkg);
        }
        if(!settings.edit().putStringSet("packages",next).commit())throw new IllegalStateException("notification_policy_write_failed");
        packages=Set.copyOf(next);
        main.post(()->{if(!closed){endpoint.authorizationChanged();refresh();}});
        return new JSONObject().put("status","ok");
    }
    @Override public void close(){
        if(closed)return;closed=true;main.removeCallbacks(monitor);
        projectionListener.close();
        for(var subscription:registrations.values())subscription.close();registrations.clear();
        endpoint.close();runtime.close();client.close();
    }
}
