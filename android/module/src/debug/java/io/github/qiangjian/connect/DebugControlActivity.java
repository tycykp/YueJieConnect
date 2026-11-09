package io.github.qiangjian.connect;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Debug-source-set only. Manifest DUMP permission restricts this to adb/root/system. */
public final class DebugControlActivity extends Activity {
    private String requestId,command;
    private boolean bound;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){
            new Thread(()->{
                String response;
                try{response=IBridge.Stub.asInterface(b).transactJson(command);}
                catch(Exception e){response="{\"status\":\"error\",\"code\":\"debug_ipc_failed\"}";}
                Log.i("QiangJianTest",requestId+" "+Base64.encodeToString(response.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
                runOnUiThread(()->finish());
            },"qj-debug-control").start();
        }
        public void onServiceDisconnected(ComponentName n){finish();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            requestId=getIntent().getStringExtra("request_id");
            if(requestId==null||!requestId.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
            String input=getIntent().getStringExtra("command_b64");if(input==null||input.length()>12000)throw new IllegalArgumentException();
            command=new String(Base64.decode(input,Base64.DEFAULT),StandardCharsets.UTF_8);
            String op=new JSONObject(command).getString("op");
            if(!Set.of("bridge_status","bridge_runtime_status","bridge_listen","bridge_connect","bridge_confirm","bridge_query_peer","bridge_stop","notification_fixture","notification_packages").contains(op))throw new IllegalArgumentException();
            TextView message=new TextView(this);message.setText("越界互联：正在执行已授权的调试操作");setContentView(message);
            if(op.equals("bridge_listen")||op.equals("bridge_connect"))startService(new Intent(this,BridgeService.class));
            bound=bindService(new Intent(this,BridgeService.class),connection,Context.BIND_AUTO_CREATE);
        }catch(Exception e){finish();}
    }
    @Override public void onDestroy(){if(bound)try{unbindService(connection);}catch(IllegalArgumentException ignored){}super.onDestroy();}
}
