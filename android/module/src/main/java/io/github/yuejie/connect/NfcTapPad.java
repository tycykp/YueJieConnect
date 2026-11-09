package io.github.yuejie.connect;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Executes tap requests inside the authenticated Xiaomi Mirror process. */
final class NfcTapPad implements BridgeClient.VendorExecutor, FileTransferBridge.NativeReceiver {
    private record Approval(String peer, String session, String request, long expires) {
        boolean allows(FileTransferEngine.Key key) {
            return peer.equals(key.peer()) && session.equals(key.session()) && SystemClock.elapsedRealtime() < expires;
        }
    }

    private final BridgeClient client;
    private final FileTransferBridge files;
    private final Context context;
    private final Supplier<ScreenCastSinkRuntime> screen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Approval> approved = new HashMap<>();

    NfcTapPad(BridgeClient client, FileTransferBridge files, Supplier<ScreenCastSinkRuntime> screen) {
        this.client = client;
        this.files = files;
        this.screen = screen;
        context = client.context();
        if (localTarget() == null) return;
        if (files != null) files.registerReceiver(FileTransferNegotiation.Purpose.NFC_TAP, this);
        client.registerExecutor(NfcTapActivity.OPERATION, this);
        advertiseTarget();
        Log.i(NfcTapActivity.LOG, "pad_executor_ready");
    }

    private void advertiseTarget() {
        try {
            client.pairing(new JSONObject().put("action", "nfc_target").put("target", localTarget()), reply -> {
                if (reply == null || !"ok".equals(reply.optString("status"))) main.postDelayed(this::advertiseTarget, 5000);
            });
        } catch (JSONException ignored) { }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private String localTarget() {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            var adapter = manager == null ? null : manager.getAdapter();
            return adapter == null ? null : XiaomiPadNfcTag.targetForAddress(adapter.getAddress());
        } catch (SecurityException unavailable) { return null; }
    }

    @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        JSONObject projection = client.projection();
        JSONObject peer = projection == null ? null : projection.optJSONObject("real");
        JSONObject payload = task.optJSONObject("payload");
        String id = task.optString("projection_id"), session = task.optString("session_id");
        if (peer == null || !"color_os17".equals(peer.optString("platform")) || !current(id, session)
                || payload == null || !payload.optString("target").equals(localTarget())) {
            completion.fail("permission_denied");
            return;
        }
        var tokens = payload.optJSONArray("files");
        if (tokens == null) { completion.fail("invalid_payload"); return; }
        if (tokens.length() == 0) {
            var sink = screen.get();
            if (sink == null || !sink.start(id, session)) { completion.fail("executor_unavailable"); return; }
            Log.i(NfcTapActivity.LOG, "mirror_requested");
            accept(completion);
            return;
        }
        approved.values().removeIf(approval -> SystemClock.elapsedRealtime() >= approval.expires());
        if (files == null || !files.ready() || context.getSystemService(KeyguardManager.class).isDeviceLocked()) {
            completion.fail("receive_consent_required");
            return;
        }
        if (SystemClock.elapsedRealtime() >= task.optLong("deadline_elapsed_ms")) {
            completion.fail("request_timeout");
            return;
        }
        // The paired phone sends this request only after the native upward swipe.
        // Keep each single-use token bound to the authenticated peer/session and request.
        approved.clear();
        Approval approval = new Approval(id, session, task.optString("request_id"), SystemClock.elapsedRealtime() + 600_000);
        for (int i = 0; i < tokens.length(); i++) approved.put(tokens.optString(i), approval);
        Log.i(NfcTapActivity.LOG, "file_receive_authorized");
        accept(completion);
    }

    private boolean current(String id, String session) {
        JSONObject projection = client.projection();
        return projection != null && id.equals(projection.optString("id")) && session.equals(projection.optString("session_id"));
    }

    private static void accept(BridgeClient.TaskCompletion completion) {
        try { completion.success(new JSONObject().put("accepted", true)); }
        catch (JSONException invalid) { completion.fail("invalid_result"); }
    }

    @Override public void cancel(String session, String request) {
        approved.values().removeIf(approval -> session.equals(approval.session()) && request.equals(approval.request()));
    }

    @Override public void sessionChanged() {
        approved.values().removeIf(approval -> !current(approval.peer(), approval.session()));
    }

    @Override public void prepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint endpoint,
                                   FileTransferNegotiation negotiation) {
        FileTransferEngine.Key key = prepared.key();
        Approval approval = approved.remove(prepared.metadata().nativeOwnerToken());
        if (approval == null || !approval.allows(key) || !files.current(key)) { negotiation.revoke(key); return; }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        ReceiveCleanup cleanup = new ReceiveCleanup(endpoint, key);
        if (!negotiation.approve(prepared, files::current, ready, cleanup::release)) { negotiation.revoke(key); return; }
        endpoint.authorizeTracked(key, prepared.metadata().offer(), new Download()).whenComplete((authorization, error) -> {
            if (error != null) { ready.completeExceptionally(error); return; }
            ready.complete(null);
            authorization.committed().whenComplete((receipt, failure) -> {
                if (failure != null) { negotiation.revoke(key); return; }
                Log.i(NfcTapActivity.LOG, "download_verified bytes=" + receipt.size());
                main.post(() -> Toast.makeText(context, "文件已保存到 Download/YueJie", Toast.LENGTH_SHORT).show());
                // The endpoint posts the verified commit to the wire before retiring its slot.
                main.postDelayed(() -> negotiation.revoke(key), 2000);
            });
        });
    }

    private static final class ReceiveCleanup {
        private final FileTransferEndpoint endpoint;
        private final FileTransferEngine.Key key;
        private CompletableFuture<Void> revoked;

        ReceiveCleanup(FileTransferEndpoint endpoint, FileTransferEngine.Key key) { this.endpoint = endpoint; this.key = key; }

        synchronized boolean release() {
            if (revoked == null || revoked.isCompletedExceptionally()) revoked = endpoint.revoke(key);
            return revoked.isDone() && !revoked.isCompletedExceptionally();
        }
    }

    private final class Download implements FileTransferEngine.Sink {
        private FileTransferEngine.Sink destination;

        @Override public void publishVerified(File staged, FileTransferEngine.Offer offer,
                                              FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, offer.name());
            values.put(MediaStore.Downloads.MIME_TYPE, offer.mime());
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YueJie");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri target = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("destination_unavailable");
            destination = FileTransferUris.newDocumentSink(context, target);
            destination.publishVerified(staged, offer, gate, key);
            ContentValues published = new ContentValues();
            published.put(MediaStore.Downloads.IS_PENDING, 0);
            if (context.getContentResolver().update(target, published, null, null) != 1) throw new IOException("destination_publish_failed");
        }

        @Override public void rollback() throws IOException { if (destination != null) destination.rollback(); }
    }
}
