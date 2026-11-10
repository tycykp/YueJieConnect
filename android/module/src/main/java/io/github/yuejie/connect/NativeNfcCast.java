package io.github.yuejie.connect;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;

/** The NFC system dispatcher hands the tap to Linker; no companion Activity is opened. */
final class NativeNfcCast extends BroadcastReceiver {
    static final String ACTION = "io.github.yuejie.connect.NATIVE_NFC_CAST";
    private final BridgeClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pending;
    NativeNfcCast(Context context,BridgeClient client) {
        this.client=client;
        context.registerReceiver(this, new IntentFilter(ACTION), "android.permission.DISPATCH_NFC_MESSAGE",
                main, Context.RECEIVER_EXPORTED);
    }
    @Override public void onReceive(Context context, Intent intent) {
        String target = intent.getStringExtra("target");
        if (pending || !ACTION.equals(intent.getAction()) || !NativePairingPolicy.session(target)
                || context.getSystemService(KeyguardManager.class).isDeviceLocked()) return;
        pending = true;
        NfcTapActivity.waitForPeer(client, main, target, "screen_video", () -> pending, projection -> {
            if (projection == null) { pending = false; return; }
            NfcTapActivity.request(client, projection, target, new JSONArray(), response -> {
                pending = false; NfcTapActivity.accepted(response);
            });
        });
    }
}
