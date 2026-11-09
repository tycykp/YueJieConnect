package io.github.yuejie.connect;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Wakes the bridge when the OEM blocks a cold cross-package service bind. */
public final class BridgeBootstrapActivity extends Activity {
    static void launch(Activity entry) {
        Intent intent = new Intent().setClassName("io.github.yuejie.connect", BridgeBootstrapActivity.class.getName())
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        entry.startActivity(intent, ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            CallerVerifier.Verified caller = CallerVerifier.resolve(getPackageManager(), getLaunchedFromUid());
            if (caller == null || !caller.packageName().equals(getLaunchedFromPackage())
                    || !(CallerPolicy.XIAOMI.equals(caller.packageName()) || CallerPolicy.OPLUS.equals(caller.packageName()))) return;

            Context app = getApplicationContext();
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {}
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            if (app.bindService(new Intent(app, BridgeService.class), connection, Context.BIND_AUTO_CREATE)) {
                // Keep it alive through one normal OEM reconnect interval.
                new Handler(Looper.getMainLooper()).postDelayed(() -> app.unbindService(connection), 12_000);
            }
        } finally {
            finish();
        }
    }
}
