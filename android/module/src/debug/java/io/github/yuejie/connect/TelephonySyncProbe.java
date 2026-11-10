package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import org.json.JSONObject;

/** ADB-only synthetic call: a reserved fictional NANP number is displayed, never dialed. No audio allocation. */
final class TelephonySyncProbe {
    static void install(BridgeClient client) {
        client.context().registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getStringExtra("action");
                String request = intent.getStringExtra("request");
                if (request == null || !request.matches("[a-f0-9]{32}")) return;
                try {
                    JSONObject projection = client.projection();
                    if (projection == null) { log(request, new JSONObject().put("code", "no_projection")); return; }
                    if ("status".equals(action)) {
                        log(request, new JSONObject().put("projection", projection)); return;
                    }
                    boolean control = "missing".equals(action);
                    if (!control && !"ringing".equals(action) && !"ended".equals(action)) return;
                    JSONObject payload = new JSONObject().put("call_id", "yj_device_probe").put("revision", "ended".equals(action) ? 2 : 1);
                    if (control) payload.put("action", "end"); else payload.put("state", action).put("address", "+12025550123");
                    String operation = control ? "telephony_call_control" : "telephony_call_state";
                    client.request(projection.getString("id"), projection.getString("session_id"), operation, request, payload,
                            result -> log(request, result));
                } catch (Exception failure) {
                    android.util.Log.i("YueJieCallTest", request + " error=" + failure.getClass().getSimpleName());
                }
            }
        }, new IntentFilter("io.github.yuejie.connect.CALL_PROBE"), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }
    private static void log(String request, JSONObject result) {
        android.util.Log.i("YueJieCallTest", request + " " + (result == null ? "null" : result.toString()));
    }
}
