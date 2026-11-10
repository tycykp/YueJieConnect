package io.github.yuejie.connect;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

/** Optional module diagnostics proxy. The OEM core survives this service and the whole module app. */
public final class BridgeService extends Service {
    private volatile IBridge remote;
    private final java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable discover = new Runnable() { public void run() {
        if (remote == null) NativeBridgeHost.connect(BridgeService.this, binder -> {
            try { binder.linkToDeath(() -> remote = null, 0); remote = IBridge.Stub.asInterface(binder); ready.countDown(); }
            catch (RemoteException dead) { remote = null; }
        });
        main.postDelayed(this, 2000);
    }};
    private final IBridge.Stub proxy = new IBridge.Stub() {
        @Override public String transactJson(String command) throws RemoteException {
            if (Binder.getCallingUid() != android.os.Process.myUid())
                throw new SecurityException("diagnostics_caller_rejected");
            if(remote==null)try{ready.await(2,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            IBridge current = remote;
            if (current == null) return "{\"status\":\"error\",\"code\":\"native_host_unavailable\"}";
            long identity = Binder.clearCallingIdentity();
            try { return current.transactJson(command); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
    };
    @Override public void onCreate() { super.onCreate(); main.post(discover); }
    @Override public IBinder onBind(Intent intent) { return proxy; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { stopSelf(startId); return START_NOT_STICKY; }
    @Override public void onDestroy() { main.removeCallbacks(discover); remote = null; super.onDestroy(); }
}
