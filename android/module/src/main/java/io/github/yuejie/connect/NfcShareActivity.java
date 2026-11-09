package io.github.yuejie.connect;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Retains the share-sheet URI grants while the phone waits for a physical tap. */
public final class NfcShareActivity extends Activity {
    private static NfcShareActivity armed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String tapToken = UUID.randomUUID().toString();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<FileTransferSource> sources = new CopyOnWriteArrayList<>();
    private NfcAdapter adapter;
    private PendingIntent dispatch;
    private BridgeClient client;
    private FileTransferBridge files;
    private List<Uri> selection;
    private TextView message;
    private volatile boolean cancelled;
    private boolean sending;
    private boolean prepared;
    private String touchedTarget;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { finish(); return; }
        adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null || !adapter.isEnabled()) { complete(getString(R.string.nfc_tap_unsupported)); return; }
        try { selection = selection(getIntent()); }
        catch (RuntimeException invalid) { complete(getString(R.string.nfc_tap_invalid_files)); return; }
        if (armed != null) armed.finish();
        armed = this;
        dispatch = PendingIntent.getActivity(this, 0, new Intent(this, NfcTapActivity.class)
                .putExtra(NfcTapActivity.SHARE_TOKEN, tapToken), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setText(R.string.nfc_tap_reading);
        setContentView(message);
        main.postDelayed(() -> { if (!sending) finish(); }, 120_000);
        worker.execute(this::prepareSources);
    }

    private static List<Uri> selection(Intent intent) {
        if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new IllegalArgumentException();
        List<Uri> selected = new ArrayList<>();
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) throw new IllegalArgumentException();
        var clip = intent.getClipData();
        if (clip != null) {
            if (clip.getItemCount() > 4) throw new IllegalArgumentException();
            for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
        } else if (Intent.ACTION_SEND.equals(action)) selected.add(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class));
        else {
            var streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (streams != null) selected.addAll(streams);
        }
        if (selected.isEmpty() || selected.size() > 4) throw new IllegalArgumentException();
        for (Uri uri : selected) FileTransferUris.requireContent(uri);
        return List.copyOf(selected);
    }

    @Override protected void onResume() {
        super.onResume();
        if (dispatch == null || sending || isFinishing()) return;
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        filter.addDataScheme("vnd.android.nfc");
        filter.addDataAuthority("ext", null);
        filter.addDataPath("/" + XiaomiPadNfcTag.TYPE, android.os.PatternMatcher.PATTERN_LITERAL);
        adapter.enableForegroundDispatch(this, dispatch, new IntentFilter[]{filter}, null);
    }

    @Override protected void onPause() {
        if (dispatch != null) adapter.disableForegroundDispatch(this);
        super.onPause();
    }

    static void tap(String token, String target) {
        NfcShareActivity share = armed;
        if (share == null || share.isFinishing() || share.sending || !share.tapToken.equals(token)) return;
        if (share.prepared) share.send(target);
        else share.touchedTarget = target;
    }

    private void prepareSources() {
        try {
            for (Uri uri : selection) {
                if (cancelled) return;
                FileTransferSource source = snapshot(uri);
                sources.add(source);
                if (cancelled) { closeSource(source); return; }
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                prepared = true;
                Log.i(NfcTapActivity.LOG, "share_files_ready count=" + sources.size());
                if (touchedTarget == null) message.setText(R.string.nfc_tap_waiting);
                else send(touchedTarget);
            });
        } catch (IOException | RuntimeException failure) {
            Log.w(NfcTapActivity.LOG, "file_source_failed " + failure.getClass().getSimpleName());
            main.post(() -> complete(getString(R.string.nfc_tap_invalid_files)));
        }
    }

    private void send(String target) {
        sending = true;
        message.setText(R.string.nfc_tap_file_pending);
        client = new BridgeClient();
        client.attach(getApplicationContext());
        try { files = FileTransferBridge.nfcSender(getApplicationContext(), client); }
        catch (IOException | RuntimeException unavailable) { failed(0); return; }
        List<String> tokens = selection.stream().map(uri -> UUID.randomUUID().toString().replace("-", "")).toList();
        NfcTapActivity.waitForPeer(client, main, "file_prepare", projection -> {
            if (isFinishing()) return;
            if (projection == null || !files.ready()) { complete(getString(R.string.nfc_tap_no_peer)); return; }
            NfcTapActivity.request(client, projection, target, new JSONArray(tokens), reply -> {
                if (isFinishing()) return;
                if (NfcTapActivity.accepted(reply)) worker.execute(() -> sendFiles(projection.optString("session_id"), tokens));
                else complete(getString(R.string.nfc_tap_rejected));
            });
        });
    }

    private void sendFiles(String session, List<String> tokens) {
        int sent = 0;
        try {
            for (FileTransferSource source : sources) {
                JSONObject projection = client.projection();
                if (cancelled || projection == null || !session.equals(projection.optString("session_id")))
                    throw new IOException("stale_session");
                var allocation = files.allocate(FileTransferNegotiation.Purpose.NFC_TAP, tokens.get(sent));
                FileTransferEngine.Key key;
                try { key = allocation.get(7, TimeUnit.SECONDS); }
                catch (Exception failure) { allocation.thenAccept(files::release); throw failure; }
                try {
                    if (!session.equals(key.session())) throw new IOException("stale_session");
                    var transfer = files.send(key, source, this::current);
                    try { transfer.get(2, TimeUnit.MINUTES); }
                    catch (Exception failure) { transfer.cancel(true); throw failure; }
                    sent++;
                    Log.i(NfcTapActivity.LOG, "file_committed");
                } finally { files.release(key); }
            }
            main.post(() -> complete(getString(R.string.nfc_tap_file_complete)));
        } catch (Exception failure) {
            Log.w(NfcTapActivity.LOG, "file_send_failed " + failure.getClass().getSimpleName());
            int delivered = sent;
            main.post(() -> failed(delivered));
        }
    }

    private FileTransferSource snapshot(Uri uri) throws IOException {
        String name;
        long size;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("file_metadata_missing");
            name = cursor.getString(0);
            size = cursor.isNull(1) ? -1 : cursor.getLong(1);
        }
        String mime = getContentResolver().getType(uri);
        return FileTransferUris.snapshot(this, uri, name, mime == null ? "application/octet-stream" : mime, size);
    }

    private boolean current(FileTransferEngine.Key key) { return !cancelled && files.current(key); }

    private void failed(int sent) { complete(getString(R.string.nfc_tap_file_failed, sent, selection.size())); }

    private void complete(String text) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static void closeSource(FileTransferSource source) {
        try { source.close(); }
        catch (IOException failure) { Log.w(NfcTapActivity.LOG, "file_source_cleanup_failed"); }
    }

    @Override protected void onDestroy() {
        cancelled = true;
        if (armed == this) armed = null;
        main.removeCallbacksAndMessages(null);
        if (dispatch != null) dispatch.cancel();
        worker.shutdownNow();
        for (FileTransferSource source : sources) closeSource(source);
        if (files != null) {
            files.close();
            files.whenRetired().thenRun(client::close);
        } else if (client != null) client.close();
        super.onDestroy();
    }
}
