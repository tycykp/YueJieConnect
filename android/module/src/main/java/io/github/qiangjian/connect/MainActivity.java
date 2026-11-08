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
            LinearLayout consent=new LinearLayout(this);consent.setOrientation(LinearLayout.VERTICAL);consent.setPadding(pad,pad,pad,pad);
            TextView prompt=new TextView(this);prompt.setText("请在两台设备上核对：\n\n"+selectedCode+"\n\n两端必须完全一致。确认后共享设备资料，并允许勾选的本次会话功能。断开即撤销。");consent.addView(prompt);
            android.widget.CheckBox batteryConsent=new android.widget.CheckBox(this);batteryConsent.setText("电量与充电方式查询");batteryConsent.setChecked(true);consent.addView(batteryConsent);
            android.widget.CheckBox clipboardConsent=new android.widget.CheckBox(this);clipboardConsent.setText("纯文本剪贴板同步（最多4096字节）");clipboardConsent.setChecked(true);consent.addView(clipboardConsent);
            android.widget.CheckBox richClipboardConsent=new android.widget.CheckBox(this);richClipboardConsent.setText("富文本剪贴板同步（文本与HTML共4096字节）");consent.addView(richClipboardConsent);
            android.widget.CheckBox finderConsent=new android.widget.CheckBox(this);finderConsent.setText("响铃查找设备（最长30秒）");consent.addView(finderConsent);
            android.widget.CheckBox notificationConsent=new android.widget.CheckBox(this);notificationConsent.setText("同步所选应用的系统通知与撤回");consent.addView(notificationConsent);
            android.widget.CheckBox notificationActionConsent=new android.widget.CheckBox(this);notificationActionConsent.setText("允许操作所同步的通知（打开、回复、关闭）");consent.addView(notificationActionConsent);
            android.widget.CheckBox fileConsent=new android.widget.CheckBox(this);fileConsent.setText("文件传输（每次收件仍需原生操作确认）");consent.addView(fileConsent);
            android.widget.CheckBox uriConsent=new android.widget.CheckBox(this);uriConsent.setText("文件剪贴板（在对端粘贴时传输）");consent.addView(uriConsent);
            android.widget.CheckBox relayConsent=new android.widget.CheckBox(this);relayConsent.setText("应用接力");consent.addView(relayConsent);
            android.widget.CheckBox screenConsent=new android.widget.CheckBox(this);screenConsent.setText("投屏（每次仍需原厂录屏许可）");consent.addView(screenConsent);
            android.widget.CheckBox mediaConsent=new android.widget.CheckBox(this);mediaConsent.setText("投屏期间的媒体与音量控制");consent.addView(mediaConsent);
            android.widget.CheckBox cameraConsent=new android.widget.CheckBox(this);cameraConsent.setText("相机共享（每次单独确认）");consent.addView(cameraConsent);
            ScrollView consentScroll=new ScrollView(this);consentScroll.addView(consent);
            new AlertDialog.Builder(this).setTitle(R.string.compare_title)
                .setView(consentScroll)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.confirm_yes,(d,w)->{try{org.json.JSONArray operations=new org.json.JSONArray().put("device_profile");if(batteryConsent.isChecked())operations.put("battery_status");if(clipboardConsent.isChecked())operations.put("clipboard_write");if(richClipboardConsent.isChecked())operations.put("clipboard_rich_write");if(finderConsent.isChecked())operations.put("device_finder");if(notificationConsent.isChecked())operations.put("notification_post").put("notification_remove");if(notificationActionConsent.isChecked())operations.put("notification_action");if(fileConsent.isChecked()||uriConsent.isChecked())operations.put("file_transfer");if(uriConsent.isChecked())operations.put("clipboard_uri_write");if(relayConsent.isChecked())operations.put("app_relay");if(screenConsent.isChecked())operations.put("screen_video");if(mediaConsent.isChecked())operations.put("media_control");if(cameraConsent.isChecked())operations.put("camera_video");request(new JSONObject().put("op","bridge_confirm").put("session",selectedSession).put("operations",operations).toString(),false);}catch(org.json.JSONException ignored){}}).show();
        });confirm.setEnabled(false);
        query=button(layout,R.string.query_peer,()->request("{\"op\":\"bridge_query_peer\"}",false));query.setEnabled(false);
        button(layout,R.string.disconnect,()->request("{\"op\":\"bridge_stop\"}",false));
        button(layout,R.string.status,()->request("{\"op\":\"bridge_status\"}",true));
        Button notificationApps=new Button(this);notificationApps.setText("选择同步通知的应用");notificationApps.setOnClickListener(v->chooseNotificationApps());layout.addView(notificationApps);
        Button notificationAccess=new Button(this);notificationAccess.setText("授予通知访问权限");notificationAccess.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,new ComponentName(this,NotificationSyncListener.class).flattenToString())));layout.addView(notificationAccess);
        Button notificationPosting=new Button(this);notificationPosting.setText("允许显示同步通知");notificationPosting.setOnClickListener(v->requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},2));layout.addView(notificationPosting);
        output=new TextView(this);output.setTextIsSelectable(true);output.setText(R.string.connecting);layout.addView(output);
        operationResult=new TextView(this);operationResult.setTextIsSelectable(true);layout.addView(operationResult);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        getWindow().getInsetsController().setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        bound=bindService(new Intent(this,BridgeService.class),connection,Context.BIND_AUTO_CREATE);
    }
    private Button button(LinearLayout layout,int label,Runnable action){Button b=new Button(this);b.setText(label);b.setFilterTouchesWhenObscured(true);b.setOnClickListener(v->action.run());layout.addView(b);return b;}
    private void chooseNotificationApps(){
        worker.execute(()->{
            try{
                IBridge current=bridge;if(current==null)throw new IllegalStateException();
                JSONObject policy=new JSONObject(current.transactJson("{\"op\":\"notification_settings\"}"));
                if(!"ok".equals(policy.optString("status")))throw new IllegalStateException();
                java.util.Set<String> selected=new java.util.HashSet<>();org.json.JSONArray configured=policy.getJSONArray("packages");
                for(int i=0;i<configured.length();i++)selected.add(configured.getString(i));
                java.util.Map<String,String> apps=new java.util.TreeMap<>();
                for(var item:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){
                    String pkg=item.activityInfo.packageName;apps.put(pkg,item.loadLabel(getPackageManager()).toString());
                }
                // Retain explicitly chosen applications without a launcher, e.g. after an app update.
                for(String pkg:selected)if(!apps.containsKey(pkg))try{apps.put(pkg,getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString());}catch(PackageManager.NameNotFoundException removed){}
                var ids=new java.util.ArrayList<>(apps.keySet());ids.sort(java.util.Comparator.comparing(apps::get));
                String[] labels=new String[ids.size()];boolean[] choices=new boolean[ids.size()];
                for(int i=0;i<ids.size();i++){labels[i]=apps.get(ids.get(i))+"\n"+ids.get(i);choices[i]=selected.contains(ids.get(i));}
                main.post(()->{if(isDestroyed())return;new AlertDialog.Builder(this).setTitle("选择允许同步通知的应用")
                        .setMultiChoiceItems(labels,choices,(dialog,index,checked)->choices[index]=checked)
                        .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,which)->{
                            try{org.json.JSONArray packages=new org.json.JSONArray();for(int i=0;i<ids.size();i++)if(choices[i])packages.put(ids.get(i));
                                request(new JSONObject().put("op","notification_packages").put("packages",packages).toString(),false);
                            }catch(org.json.JSONException ignored){}
                        }).show();});
            }catch(Exception failure){main.post(()->{if(!isDestroyed())operationResult.setText("通知设置暂不可用");});}
        });
    }
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
