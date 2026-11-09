package io.github.yuejie.connect;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The system NFC dispatcher is the only external caller of this activity. */
public final class NfcTapActivity extends Activity {
    static final String SHARE_TOKEN = "io.github.yuejie.connect.NFC_SHARE_TOKEN";
    static final String OPERATION = "nfc_tap";
    static final String LOG = "YueJieNfc";
    private static String lastTarget;
    private static long lastTap;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeClient client;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())
                || getSystemService(KeyguardManager.class).isDeviceLocked()) { finish(); return; }
        Intent intent = getIntent();
        String target = readTarget(intent);
        if (target == null) { finish(); return; }
        Log.i(LOG, "factory_tag_read");
        String token = intent.getStringExtra(SHARE_TOKEN);
        if (token != null) {
            NfcShareActivity.tap(token, target);
            finish();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (target.equals(lastTarget) && now - lastTap < 3000) { finish(); return; }
        lastTarget = target;
        lastTap = now;
        client = new BridgeClient();
        client.attach(getApplicationContext());
        waitForPeer(client, main, "screen_video", projection -> {
            if (isFinishing()) return;
            if (projection == null) { complete(R.string.nfc_tap_no_peer); return; }
            request(client, projection, target, new JSONArray(), reply -> {
                if (!isFinishing()) complete(accepted(reply) ? R.string.nfc_tap_mirror_started : R.string.nfc_tap_rejected);
            });
        });
    }

    private static String readTarget(Intent intent) {
        try { return XiaomiPadNfcTag.target(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class)); }
        catch (RuntimeException invalid) { return null; }
    }

    static void waitForPeer(BridgeClient client, Handler main, String grant, Consumer<JSONObject> result) {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        main.post(new Runnable() {
            @Override public void run() {
                JSONObject projection = client.projection();
                JSONObject peer = projection == null ? null : projection.optJSONObject("real");
                if (peer != null && "hyper_os4".equals(peer.optString("platform")) && "tablet".equals(peer.optString("kind"))
                        && client.hasLocalGrant(grant) && offers(projection, OPERATION)) result.accept(projection);
                else if (SystemClock.elapsedRealtime() >= deadline) result.accept(null);
                else main.postDelayed(this, 100);
            }
        });
    }

    private static boolean offers(JSONObject projection, String operation) {
        JSONArray operations = projection.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if (operation.equals(operations.optString(i))) return true;
        return false;
    }

    static void request(BridgeClient client, JSONObject projection, String target, JSONArray files, Consumer<JSONObject> reply) {
        try {
            client.request(projection.getString("id"), projection.getString("session_id"), OPERATION,
                    "nfc:" + UUID.randomUUID(), new JSONObject().put("target", target).put("files", files), reply);
        } catch (JSONException invalid) { reply.accept(null); }
    }

    static boolean accepted(JSONObject reply) {
        if (reply == null || !"ok".equals(reply.optString("status"))) {
            Log.i(LOG, "tap_rejected " + (reply == null ? "invalid_request" : reply.optString("code")));
            return false;
        }
        return true;
    }

    private void complete(int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        super.onDestroy();
    }
}
