package io.github.yuejie.connect;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;
import org.json.JSONObject;

/** Opened only by the module's immutable notification PendingIntent. */
public final class BrowserRelayOpenActivity extends Activity {
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            IBridge bridge = IBridge.Stub.asInterface(binder);
            String session = getIntent().getStringExtra("session"), pkg = getIntent().getStringExtra("package");
            new Thread(() -> {
                boolean accepted = false;
                try {
                    JSONObject command = new JSONObject().put("op", "browser_relay_click")
                            .put("session", session).put("package", pkg);
                    accepted = "ok".equals(new JSONObject(bridge.transactJson(command.toString())).optString("status"));
                } catch (Exception unavailable) { /* An expired or disconnected offer cannot be opened. */ }
                boolean success = accepted;
                runOnUiThread(() -> {
                    if (!success) Toast.makeText(BrowserRelayOpenActivity.this, R.string.browser_relay_expired, Toast.LENGTH_LONG).show();
                    finish();
                });
            }, "yj-browser-click").start();
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(); }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bound = bindService(new Intent(this, BridgeService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) finish();
    }
    @Override protected void onDestroy() {
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
