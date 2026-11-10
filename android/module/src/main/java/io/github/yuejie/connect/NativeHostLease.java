package io.github.yuejie.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** SystemUI retains the OEM's exported service through an ordinary, permission-checked OS binding. */
final class NativeHostLease {
    private final Context context;
    private final ComponentName target;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            android.util.Log.i("YueJieBridge", "native_host_retained " + name.getPackageName());
        }
        @Override public void onServiceDisconnected(ComponentName name) { /* Android reconnects the existing binding. */ }
        @Override public void onBindingDied(ComponentName name) { retry(); }
        @Override public void onNullBinding(ComponentName name) { retry(); }
    };
    NativeHostLease(Context context) {
        this.context = context;
        boolean xiaomi = CallerVerifier.phoneSource(context.getPackageManager()) == 1;
        target = new ComponentName(xiaomi ? CallerPolicy.XIAOMI : CallerPolicy.OPLUS, xiaomi
                ? (CallerPolicy.XIAOMI.equals(context.getPackageName())
                    ? "com.miui.circulate.channel.SharedChannelService" : "com.milink.client.ClientV2PublicService")
                : "com.oplus.synergy.engine.RelayService");
        main.post(this::bind);
    }
    private void bind() {
        if (bound) return;
        try {
            int uid = context.getPackageManager().getApplicationInfo(target.getPackageName(), 0).uid;
            if (!NativeBridgeHost.hostCaller(context.getPackageManager(), uid)) return;
            bound = context.bindService(new Intent().setComponent(target), connection, Context.BIND_AUTO_CREATE);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            android.util.Log.w("YueJieBridge", "native_host_retention_unavailable",unavailable);
        }
        if (!bound) main.postDelayed(this::bind, 5000);
    }
    private void retry() {
        if (bound) { bound = false; try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {} }
        main.postDelayed(this::bind, 2000);
    }
}
