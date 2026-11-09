package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** DUMP-protected background diagnostics; never brings a module task to the foreground. */
public final class DebugControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id=intent.getStringExtra("request_id"), encoded=intent.getStringExtra("command_b64");
        final String command;
        try{
            if(id==null||!id.matches("[a-f0-9]{32}")||encoded==null||encoded.length()>12000)return;
            command=new String(Base64.decode(encoded,Base64.DEFAULT),StandardCharsets.UTF_8);
            if(!Set.of("bridge_status","bridge_runtime_status","bridge_stop").contains(new JSONObject(command).getString("op")))return;
        }catch(Exception invalid){return;}
        PendingResult pending=goAsync();Context app=context.getApplicationContext();
        Handler main=new Handler(Looper.getMainLooper());AtomicBoolean done=new AtomicBoolean();
        ServiceConnection connection=new ServiceConnection(){
            private void complete(String response){main.post(()->{
                if(!done.compareAndSet(false,true))return;
                String value=Base64.encodeToString(response.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
                int count=(value.length()+2999)/3000;
                for(int i=0;i<count;i++)Log.i("YueJieTest",id+" "+(i+1)+"/"+count+" "+value.substring(i*3000,Math.min(value.length(),(i+1)*3000)));
                try{app.unbindService(this);}catch(IllegalArgumentException ignored){}pending.finish();
            });}
            @Override public void onServiceConnected(ComponentName name,IBinder binder){new Thread(()->{
                try{complete(IBridge.Stub.asInterface(binder).transactJson(command));}
                catch(Exception failure){complete("{\"status\":\"error\",\"code\":\"debug_ipc_failed\"}");}
            },"yj-background-test").start();}
            @Override public void onServiceDisconnected(ComponentName name){complete("{\"status\":\"error\"}");}
        };
        try{if(!app.bindService(new Intent(app,BridgeService.class),connection,Context.BIND_AUTO_CREATE)){done.set(true);pending.finish();return;}}
        catch(RuntimeException unavailable){done.set(true);pending.finish();return;}
        main.postDelayed(()->{if(done.compareAndSet(false,true)){try{app.unbindService(connection);}catch(IllegalArgumentException ignored){}pending.finish();}},8000);
    }
}
