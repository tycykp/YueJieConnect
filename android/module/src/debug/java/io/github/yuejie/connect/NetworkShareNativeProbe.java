package io.github.yuejie.connect;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.CompletableFuture;
import org.json.JSONException;
import org.json.JSONObject;

/** Debug-only OEM-process observation, not a synthetic network executor or a ready proof.
 * No SSID, credentials, IPs, user network IDs, clipboard or private app contents are emitted.
 */
final class NetworkShareNativeProbe implements AutoCloseable {
    static final String ACTION = "io.github.yuejie.connect.NETWORK_READINESS";
    private static final String TAG = "YueJieNetworkTest";
    private final Context context;
    private final NetworkShareBinding binding;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private boolean registered;
    private String lastState;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed) return;
            binding.endpoint().status().whenComplete((view, error) -> state("observe", view, error, false));
            main.postDelayed(this, 500);
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || intent == null || !ACTION.equals(intent.getAction())) return;
            // No parameters or mutable operations: shell can ask only for the current snapshot.
            snapshot();
        }
    };
    private NetworkShareNativeProbe(Context context, NetworkShareBinding binding) {
        this.context = context; this.binding = binding;
    }
    static AutoCloseable install(Context context, NetworkShareBinding binding) {
        NetworkShareNativeProbe probe = new NetworkShareNativeProbe(context, binding);
        try {
            context.registerReceiver(probe.receiver, new IntentFilter(ACTION), Manifest.permission.DUMP,
                    probe.main, Context.RECEIVER_EXPORTED);
            probe.registered = true;
            probe.snapshot();
            probe.main.post(probe.poll);
            return probe;
        } catch (RuntimeException error) { probe.close(); throw error; }
    }
    private void snapshot() {
        binding.endpoint().status().whenComplete((view, error) -> state("snapshot", view, error, true));
        binding.endpoint().availability().whenComplete((available, error) -> {
            if (closed) return;
            try {
                JSONObject event = base("availability");
                event.put("tether_permission", granted("android.permission.TETHER_PRIVILEGED"));
                event.put("wifi_read_permission", granted(Manifest.permission.ACCESS_WIFI_STATE));
                event.put("network_read_permission", granted(Manifest.permission.ACCESS_NETWORK_STATE));
                event.put("wifi_change_permission", granted(Manifest.permission.CHANGE_WIFI_STATE));
                event.put("location_permission", granted(Manifest.permission.ACCESS_FINE_LOCATION));
                event.put("phone_state_permission", granted(Manifest.permission.READ_PHONE_STATE));
                event.put("wifi_system_permission", granted("android.permission.NETWORK_SETTINGS")
                        || granted("android.permission.NETWORK_SETUP_WIZARD") || Process.myUid() == Process.SYSTEM_UID);
                if (available != null && error == null) {
                    event.put("provider", available.provider()); event.put("consumer", available.consumer());
                    event.put("upstream", available.upstream() == null ? "none" : available.upstream().name());
                    event.put("metered", available.metered());
                    event.put("error", available.error() == null ? "" : available.error());
                } else event.put("error", "probe_observation_failed");
                Log.i(TAG, event.toString());
            } catch (JSONException | RuntimeException ignored) { /* No private exception text. */ }
        });
    }
    private synchronized void state(String kind, NetworkShareSession.View view, Throwable error, boolean force) {
        if (closed) return;
        try {
            JSONObject event = base(kind);
            String identity;
            if (view != null && error == null) {
                event.put("session", view.ticket().session()); event.put("share_id", view.ticket().request());
                event.put("generation", view.ticket().generation()); event.put("role", view.role().name());
                event.put("phase", view.phase().name()); event.put("upstream", view.upstream().name());
                event.put("error", view.error() == null ? "" : view.error());
                identity = view.ticket() + ":" + view.phase() + ":" + view.error();
            } else {
                identity = error == null ? "empty" : "probe_observation_failed";
                event.put("phase", identity);
            }
            if (force || !identity.equals(lastState)) Log.i(TAG, event.toString());
            lastState = identity;
        } catch (JSONException | RuntimeException ignored) { /* Observation must not affect recovery. */ }
    }
    private JSONObject base(String kind) throws JSONException {
        return new JSONObject().put("event", kind).put("package", context.getPackageName())
                .put("pid", Process.myPid()).put("uid", Process.myUid()).put("elapsed_ms", SystemClock.elapsedRealtime());
    }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    @Override public void close() {
        closed = true; main.removeCallbacks(poll);
        if (registered) {
            registered = false;
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        }
    }
}
