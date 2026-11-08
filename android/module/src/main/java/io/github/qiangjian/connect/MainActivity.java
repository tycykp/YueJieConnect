package io.github.qiangjian.connect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private volatile IBridge bridge;
    private TextView output,operationResult;
    private EditText address;
    private Button confirm,query;
    private String session="",code="";
    private boolean bound,visible;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger pending=new AtomicInteger();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){public void run(){if(visible){request("{\"op\":\"bridge_status\"}",true);main.postDelayed(this,1000);}}};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){bridge=IBridge.Stub.asInterface(binder);request("{\"op\":\"bridge_status\"}",true);}
        public void onServiceDisconnected(ComponentName name){bridge=null;output.setText(R.string.unavailable);confirm.setEnabled(false);query.setEnabled(false);}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFocusableInTouchMode(true);
        int pad=(int)(16*getResources().getDisplayMetrics().density);
        layout.setOnApplyWindowInsetsListener((view,insets)->{var bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        TextView intro=new TextView(this);intro.setText(R.string.gateway_intro);layout.addView(intro);
        address=new EditText(this);address.setSingleLine(true);address.setHint(R.string.address_hint);
        address.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.:"));
        address.setRawInputType(android.text.InputType.TYPE_CLASS_PHONE);layout.addView(address);
        button(layout,R.string.listen,()->networkCommand("{\"op\":\"bridge_listen\",\"port\":38271}"));
        button(layout,R.string.connect_peer,()->{try{String remote=address.getText().toString().trim();if(!remote.contains(":"))remote+=":38271";
            networkCommand(new JSONObject().put("op","bridge_connect").put("address",remote).toString());}catch(org.json.JSONException ignored){}});
        confirm=button(layout,R.string.confirm_pair,()->{
            final String selectedSession=session,selectedCode=code;
            new AlertDialog.Builder(this).setTitle(R.string.compare_title)
                .setMessage("请在两台设备上核对：\n\n"+selectedCode+"\n\n两端必须完全一致。分别确认后允许共享设备资料，以及本会话内的电量和充电方式查询。")
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.confirm_yes,(d,w)->{try{request(new JSONObject().put("op","bridge_confirm").put("session",selectedSession).toString(),false);}catch(org.json.JSONException ignored){}}).show();
        });confirm.setEnabled(false);
        query=button(layout,R.string.query_peer,()->request("{\"op\":\"bridge_query_peer\"}",false));query.setEnabled(false);
        button(layout,R.string.disconnect,()->request("{\"op\":\"bridge_stop\"}",false));
        button(layout,R.string.status,()->request("{\"op\":\"bridge_status\"}",true));
        output=new TextView(this);output.setTextIsSelectable(true);output.setText(R.string.connecting);layout.addView(output);
        operationResult=new TextView(this);operationResult.setTextIsSelectable(true);layout.addView(operationResult);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        getWindow().getInsetsController().setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        bound=bindService(new Intent(this,BridgeService.class),connection,Context.BIND_AUTO_CREATE);
    }
    private Button button(LinearLayout layout,int label,Runnable action){Button b=new Button(this);b.setText(label);b.setFilterTouchesWhenObscured(true);b.setOnClickListener(v->action.run());layout.addView(b);return b;}
    private void networkCommand(String command){
        if(checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK","android.permission.POST_NOTIFICATIONS"},1);
            operationResult.setText(R.string.permission_needed);return;
        }
        startService(new Intent(this,BridgeService.class));request(command,false);
    }
    private void request(String command,boolean status){
        if(isDestroyed())return;
        if(status){if(!pending.compareAndSet(0,1))return;}else pending.incrementAndGet();
        worker.execute(()->{
            JSONObject response;String addresses="";
            try{IBridge current=bridge;if(current==null)throw new IllegalStateException();response=new JSONObject(current.transactJson(command));
                if(status)addresses=localAddresses();
            }catch(Exception e){response=new JSONObject();try{response.put("status","error").put("code","service_unavailable");}catch(org.json.JSONException ignored){}}
            final JSONObject value=response;final String local=addresses;
            main.post(()->{pending.decrementAndGet();if(isDestroyed())return;
                if(status)render(value,local);else try{operationResult.setText(value.has("real_device")?"已从真实对端取得：\n"+summary(value.getJSONObject("real_device")):value.toString(2));}catch(org.json.JSONException ignored){}
            });
        });
    }
    private void render(JSONObject response,String addresses){
        JSONObject state=response.optJSONObject("bridge");
        if(state==null){output.setText(response.toString());return;}
        String phase=state.optString("phase");session=state.optString("session");code=state.optString("comparison_code");
        confirm.setEnabled(phase.equals("awaiting_confirmation")&&!state.optBoolean("local_confirmed"));query.setEnabled(phase.equals("active"));
        String label=switch(phase){case "listening"->"等待连接（两分钟有效）";case "connecting"->"正在连接";case "handshaking"->"正在建立加密通道";case "awaiting_confirmation"->"等待两端确认";case "active"->"桥接已配对 · 业务以双方已实现能力为准";case "disconnected"->"连接已结束";default->"已停止";};
        String text="\n"+label+"\n本机地址："+addresses+"\n";
        if(!code.isEmpty())text+="\n校验码："+code+"\n本机确认："+state.optBoolean("local_confirmed")+"  对端确认："+state.optBoolean("remote_confirmed")+"\n";
        JSONObject peer=state.optJSONObject("remote");if(peer!=null)text+="\n真实对端：\n"+summary(peer);
        text+="\n资料请求 发出/收到/响应："+state.optLong("requests_sent")+" / "+state.optLong("requests_received")+" / "+state.optLong("responses_received");
        if(!state.isNull("last_error"))text+="\n原因："+state.optString("last_error");
        if(!state.isNull("socket_errno"))text+=" (socket errno="+state.optInt("socket_errno")+")";output.setText(text);
    }
    private String summary(JSONObject p){return p.optString("name")+"\n"+p.optString("manufacturer")+" / "+p.optString("model")+"\n"+p.optString("os_build")+"\n";}
    private String localAddresses()throws java.net.SocketException{
        StringBuilder s=new StringBuilder();for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces()))
            for(var ip:Collections.list(network.getInetAddresses()))if(ip instanceof Inet4Address && ip.isSiteLocalAddress())s.append(ip.getHostAddress()).append(":38271 ");
        return s.toString();
    }
    @Override public void onResume(){super.onResume();visible=true;main.post(ticker);}
    @Override public void onPause(){visible=false;main.removeCallbacks(ticker);super.onPause();}
    @Override public void onDestroy(){if(bound)unbindService(connection);worker.shutdown();super.onDestroy();}
}
