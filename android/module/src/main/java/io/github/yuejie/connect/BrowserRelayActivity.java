package io.github.yuejie.connect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit share-sheet entry for browsers that do not ship an OEM handoff SDK. */
public final class BrowserRelayActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private IBridge bridge;
    private boolean bound;
    private String url, session, offerId;
    private long deadline;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bridge = IBridge.Stub.asInterface(binder);
            if (offerId != null) poll();
            else call(command("browser_relay_peer"), BrowserRelayActivity.this::confirm);
        }
        @Override public void onServiceDisconnected(ComponentName name) { complete(R.string.browser_relay_failed); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Intent intent = getIntent();
            if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType()))
                throw new IllegalArgumentException();
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            var clip = intent.getClipData();
            if (text == null && clip != null && clip.getItemCount() == 1 && clip.getDescription().hasMimeType("text/plain"))
                text = clip.getItemAt(0).getText();
            url = BrowserRelayOffer.fromShare(text);
        } catch (RuntimeException invalid) {
            complete(R.string.browser_relay_invalid);
            return;
        }
        if (saved != null) {
            session = saved.getString("session");
            offerId = saved.getString("offer");
            deadline = saved.getLong("deadline");
        }
        bound = bindService(new Intent(this, BridgeService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) complete(R.string.browser_relay_unavailable);
    }

    private void confirm(JSONObject peer) {
        if (!ok(peer)) {
            complete(R.string.browser_relay_unavailable);
            return;
        }
        session = peer.optString("session");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.browser_relay)
                .setMessage(getString(R.string.browser_relay_confirm, peer.optString("name"), url))
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> finish())
                .setPositiveButton(R.string.browser_relay_send, (ignored, which) -> submit())
                .setOnCancelListener(ignored -> finish()).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    }

    private void submit() {
        TextView waiting = new TextView(this);
        waiting.setGravity(Gravity.CENTER);
        waiting.setText(R.string.browser_relay_waiting);
        setContentView(waiting);
        deadline = SystemClock.elapsedRealtime() + 17_000;
        try {
            call(command("browser_relay_offer").put("session", session).put("url", url), reply -> {
                if (!ok(reply)) complete(R.string.browser_relay_failed);
                else {
                    offerId = reply.optString("id");
                    poll();
                }
            });
        } catch (JSONException invalid) {
            complete(R.string.browser_relay_invalid);
        }
    }

    private void poll() {
        if (SystemClock.elapsedRealtime() >= deadline) {
            complete(R.string.browser_relay_failed);
            return;
        }
        try {
            call(command("browser_relay_status").put("session", session).put("id", offerId), reply -> {
                if (!ok(reply)) complete(R.string.browser_relay_failed);
                else switch (reply.optString("state")) {
                    case "waiting" -> main.postDelayed(this::poll, 400);
                    case "delivered" -> complete(R.string.browser_relay_delivered);
                    default -> complete(R.string.browser_relay_failed);
                }
            });
        } catch (JSONException invalid) {
            complete(R.string.browser_relay_failed);
        }
    }

    private void call(JSONObject command, Consumer<JSONObject> callback) {
        IBridge selected = bridge;
        worker.execute(() -> {
            JSONObject reply = null;
            try { if (selected != null) reply = new JSONObject(selected.transactJson(command.toString())); }
            catch (Exception unavailable) { /* The UI reports service unavailability. */ }
            JSONObject result = reply;
            main.post(() -> { if (!isFinishing() && !isDestroyed()) callback.accept(result); });
        });
    }

    private static JSONObject command(String op) {
        try { return new JSONObject().put("op", op); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean ok(JSONObject reply) { return reply != null && "ok".equals(reply.optString("status")); }
    private void complete(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("session", session);
        out.putString("offer", offerId);
        out.putLong("deadline", deadline);
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
