package io.github.yuejie.connect;

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
                logResponse(response);
                runOnUiThread(()->finish());
            },"yj-debug-control").start();
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
            JSONObject inputCommand=new JSONObject(command);
            String op=inputCommand.getString("op");
            if("media_fixture".equals(op)){
                logResponse(MediaControlFixture.command(getApplicationContext(),inputCommand.getString("action")));
                finish();return;
            }
            if(!Set.of("bridge_bluetooth_start","bridge_bluetooth_status","bridge_status","bridge_runtime_status","bridge_listen","bridge_connect","bridge_confirm","bridge_query_peer","bridge_stop","notification_fixture","notification_packages").contains(op))throw new IllegalArgumentException();
            TextView message=new TextView(this);message.setText("越界互联：正在执行已授权的调试操作");setContentView(message);
            bound=bindService(new Intent(this,BridgeService.class),connection,Context.BIND_AUTO_CREATE);
        }catch(Exception e){finish();}
    }
    private void logResponse(String response){
        String encoded=Base64.encodeToString(response.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        int count=(encoded.length()+2999)/3000;
        if(count<=1||!getIntent().getBooleanExtra("chunked_response",false)){
            Log.i("YueJieTest",requestId+" "+encoded);return;
        }
        for(int i=0;i<count;i++)
            Log.i("YueJieTest",requestId+" "+(i+1)+"/"+count+" "+encoded.substring(i*3000,Math.min(encoded.length(),(i+1)*3000)));
    }
    @Override public void onDestroy(){if(bound)try{unbindService(connection);}catch(IllegalArgumentException ignored){}super.onDestroy();}
}
