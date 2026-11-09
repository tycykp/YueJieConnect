package io.github.yuejie.connect;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONArray;

/** Owns the bounded tap operation without an Activity window covering native cast consent. */
public final class NfcTapService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private BridgeClient client;
    private boolean pending,closed;

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String target=intent==null?null:intent.getStringExtra("target");
        if(!NativePairingPolicy.session(target)||getSystemService(KeyguardManager.class).isDeviceLocked()){
            if(!pending)stopSelf();return START_NOT_STICKY;
        }
        if(pending)return START_NOT_STICKY;
        NotificationManager notices=getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel("nfc_connect","NFC 连接",NotificationManager.IMPORTANCE_LOW));
        Notification notification=new Notification.Builder(this,"nfc_connect")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("正在连接平板")
                .setOngoing(true).setOnlyAlertOnce(true).build();
        startForeground(2,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        pending=true;client=new BridgeClient();client.attach(this);
        NfcTapActivity.waitForPeer(client,main,target,"screen_video",()->!closed,projection->{
            if(closed)return;
            if(projection==null){finish(R.string.nfc_tap_no_peer);return;}
            NfcTapActivity.request(client,projection,target,new JSONArray(),reply->{
                if(!closed)finish(NfcTapActivity.accepted(reply)?0:R.string.nfc_tap_rejected);
            });
        });
        return START_NOT_STICKY;
    }
    private void finish(int message){
        if(message!=0)Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){closed=true;main.removeCallbacksAndMessages(null);if(client!=null)client.close();super.onDestroy();}
}
