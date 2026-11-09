package io.github.yuejie.connect;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The system NFC dispatcher is the only external caller of this activity. */
public final class NfcTapActivity extends Activity {
    static final String OPERATION = "nfc_tap";
    static final String LOG = "YueJieNfc";
    private static String lastTarget;
    private static long lastTap;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())
                || getSystemService(KeyguardManager.class).isDeviceLocked()) { finish(); return; }
        Intent intent = getIntent();
        String target = readTarget(intent);
        if (target == null) { finish(); return; }
        Log.i(LOG, "factory_tag_read");
        long now = SystemClock.elapsedRealtime();
        if (target.equals(lastTarget) && now - lastTap < 3000) { finish(); return; }
        lastTarget = target;
        lastTap = now;
        try { startForegroundService(new Intent(this,NfcTapService.class).putExtra("target",target)); }
        catch (RuntimeException unavailable) { Log.w(LOG,"background_tap_unavailable",unavailable); }
        finally { finish(); }
    }

    private static String readTarget(Intent intent) {
        try { return XiaomiPadNfcTag.target(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class)); }
        catch (RuntimeException invalid) { return null; }
    }

    static void waitForPeer(BridgeClient client, Handler main, String target, String grant,
                            java.util.function.BooleanSupplier alive, Consumer<JSONObject> result) {
        long startedAt = SystemClock.elapsedRealtime();
        long deadline = startedAt + 120_000;
        BridgeClient.Subscription fast = client.accelerateProjection();
        main.post(new Runnable() {
            private boolean started, pending, done;
            private String selected = "";
            private long nextCommand;
            private void complete(JSONObject value) {
                done = true;
                fast.close();
                Log.i(LOG, "peer_ready success=" + (value != null) + " elapsed_ms=" + (SystemClock.elapsedRealtime() - startedAt));
                result.accept(value);
            }
            @Override public void run() {
                if (done || !alive.getAsBoolean()) { fast.close(); return; }
                long now = SystemClock.elapsedRealtime();
                JSONObject projection = client.projection();
                JSONObject peer = projection == null ? null : projection.optJSONObject("real");
                if (peer != null && "hyper_os4".equals(peer.optString("platform")) && "tablet".equals(peer.optString("kind"))
                        && (selected.isEmpty() || selected.equals(projection.optString("id")))
                        && client.hasLocalGrant(grant) && offers(projection, grant) && offers(projection, OPERATION)) { complete(projection); return; }
                if (now >= deadline) { complete(null); return; }
                if (!pending && now >= nextCommand) {
                    pending = true;
                    nextCommand = now + 200;
                    try {
                        client.pairing(new JSONObject().put("action", started ? "status" : "start"), reply -> {
                            pending = false;
                            if (done || !alive.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline) return;
                            if (reply == null || !"ok".equals(reply.optString("status"))) return;
                            started = true;
                            JSONObject state = reply.optJSONObject("pairing");
                            if (state == null) return;
                            if (!selected.isEmpty()) {
                                if ("disconnected".equals(state.optString("phase")) || "stopped".equals(state.optString("phase"))) complete(null);
                                return;
                            }
                            if (!"listening".equals(state.optString("phase")) && !"awaiting_confirmation".equals(state.optString("phase"))) return;
                            String id = NfcTapPeer.select(reply.optJSONArray("peers"), target);
                            if (id == null) return;
                            pending = true;
                            try {
                                client.pairing(new JSONObject().put("action", "select").put("peer", id), response -> {
                                    pending = false;
                                    if (done || !alive.getAsBoolean()) return;
                                    if (response != null && "ok".equals(response.optString("status"))) selected = id;
                                });
                            } catch (JSONException invalid) { pending = false; complete(null); }
                        });
                    } catch (JSONException invalid) { pending = false; complete(null); return; }
                }
                main.postDelayed(this, 100);
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

}
