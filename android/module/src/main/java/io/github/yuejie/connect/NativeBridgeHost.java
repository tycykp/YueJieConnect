package io.github.yuejie.connect;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.function.Consumer;

/** Binder discovery carries no authority: every core request still verifies its kernel caller. */
final class NativeBridgeHost {
    private static final String ACTION = "io.github.yuejie.connect.NATIVE_CORE_V1";
    private static final String CALLBACK = ACTION + ".callback";
    private static NativeBridgeRuntime runtime;
    private static BroadcastReceiver receiver;
    private NativeBridgeHost() {}

    static int moduleUid(PackageManager manager) {
        try { return manager.getApplicationInfo("io.github.yuejie.connect", 0).uid; }
        catch (PackageManager.NameNotFoundException | SecurityException absent) { return -1; }
    }

    static boolean hostCaller(PackageManager manager, int uid) {
        CallerVerifier.Verified caller = CallerVerifier.resolve(manager, uid);
        return caller != null && (CallerPolicy.XIAOMI.equals(caller.packageName())
                || CallerPolicy.OPLUS.equals(caller.packageName()));
    }

    static synchronized void start(Context context, BridgeClient client) {
        String process = Application.getProcessName();
        if (runtime != null || !(CallerPolicy.XIAOMI.equals(process)
                || CallerPolicy.OPLUS.equals(process))
                || !hostCaller(context.getPackageManager(), android.os.Process.myUid())) return;
        if(CallerPolicy.XIAOMI.equals(process))new NativeHostLease(context);
        NativeBridgeRuntime created = new NativeBridgeRuntime(context,client);
        created.start();
        runtime = created;
        if(CallerPolicy.OPLUS.equals(context.getPackageName()))new NativeNfcCast(context,client);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent intent) {
                if (!ACTION.equals(intent.getAction())) return;
                Bundle extras = intent.getExtras();
                IBinder callback = extras == null ? null : extras.getBinder("callback");
                if (callback == null) return;
                // Never invoke a caller-controlled Binder on the OEM main thread.
                android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                    Parcel data = Parcel.obtain();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        data.writeInterfaceToken(CALLBACK); data.writeStrongBinder(created.binder());
                        callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
                    } catch (RemoteException | RuntimeException unavailable) {
                        android.util.Log.d("YueJieBridge", "native_client_gone");
                    } finally { Binder.restoreCallingIdentity(identity); data.recycle(); }
                });
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    static void connect(Context context, Consumer<IBinder> connected) {
        IBinder callback = new Binder() {
            private boolean delivered;
            @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(CALLBACK);
                if (!hostCaller(context.getPackageManager(), Binder.getCallingUid()))
                    throw new SecurityException("native_host_rejected");
                IBinder core = data.readStrongBinder();
                if (data.dataAvail() != 0 || core == null || !core.isBinderAlive())
                    throw new SecurityException("native_host_unavailable");
                if (!delivered) { delivered = true; connected.accept(core); }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
        Bundle extras = new Bundle(); extras.putBinder("callback", callback);
        for (String pkg : new String[]{CallerPolicy.XIAOMI, CallerPolicy.OPLUS})
            context.sendBroadcast(new Intent(ACTION).setPackage(pkg).putExtras(extras));
    }
}
