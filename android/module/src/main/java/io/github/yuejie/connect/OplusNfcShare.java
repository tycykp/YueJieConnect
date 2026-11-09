package io.github.yuejie.connect;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;

/** Runs in Linker's :service, where the system chooser registers its real TAP_SHARE agent. */
final class OplusNfcShare {
    static final String ACTION = "io.github.yuejie.connect.NATIVE_NFC_SHARE";
    private final XposedModule module;
    private final BridgeClient client;
    private final Context context;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private OplusNfcShareUi ui;
    private Object repository;
    private Attempt active;

    private static final class Attempt {
        final Object agent;
        final String target;
        volatile boolean done;
        Object entity;
        FileTransferBridge files;
        CompletableFuture<?> transfer;
        CompletableFuture<?> waiting;
        final CompletableFuture<Void> swiped = new CompletableFuture<>();
        Attempt(Object agent, String target) { this.agent = agent; this.target = target; }
    }

    OplusNfcShare(XposedModule module, BridgeClient client, ClassLoader loader) {
        this.module = module; this.client = client; this.loader = loader; context = client.context();
    }

    void install() throws ReflectiveOperationException {
        ui = new OplusNfcShareUi(loader);
        Class<?> repo = type("nfc.NfcEventRepository");
        repository = repo.getField("INSTANCE").get(null);
        module.hook(repo.getMethod("share", type("nfc.share.operation.ShareOperationEntity")))
                .setId("yj.nfc.native.share").intercept(chain -> {
                    Object entity = chain.getArg(0);
                    Attempt attempt;
                    synchronized (this) {
                        attempt = active;
                        if (attempt == null || attempt.done || attempt.entity != null
                                || !sameAgent(attempt.agent, call(entity, "getAgent"))) return chain.proceed();
                        attempt.entity = entity;
                    }
                    main.post(() -> prepare(attempt));
                    return null;
                });
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                String target = intent.getStringExtra("target");
                if (!NativePairingPolicy.session(target) || host.getSystemService(KeyguardManager.class).isDeviceLocked()) return;
                try {
                    Object registration = call(repository, "getRegisterNfcData");
                    Object agent = registration == null ? null : call(registration, "getAgent");
                    // Only the active system share sheet may supply files. No cached URI lists.
                    if (agent == null || !"com.android.intentresolver".equals(call(agent, "getPkg"))) return;
                    setResultCode(OplusNfcTap.ActivityResult.HANDLED);
                    begin(agent, target);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    Log.w(NfcTapActivity.LOG, "native_share_entry_unavailable", failure);
                }
            }
        }, new IntentFilter(ACTION), "android.permission.DISPATCH_NFC_MESSAGE", main, Context.RECEIVER_EXPORTED);
        Log.i(NfcTapActivity.LOG, "native_share_ready");
    }

    private synchronized void begin(Object agent, String target) throws ReflectiveOperationException {
        if (active != null || ui.running()) return;
        Attempt attempt = new Attempt(agent, target);
        active = attempt;
        try {
            ui.start(() -> {
                if (!attempt.done && attempt.swiped.complete(null)) Log.i(NfcTapActivity.LOG, "native_share_swiped");
            }, () -> finish(attempt, false));
            Object nativeClient = type("share.sdk.InternalShareAgentClient")
                    .getConstructor(String.class, String.class, int.class, String.class)
                    .newInstance(call(agent, "getAgentId"), call(agent, "getPkg"), call(agent, "getPid"), call(agent, "getAppName"));
            Object event = type("share.sdk.InternalShareAgent").getConstructor(int.class, nativeClient.getClass()).newInstance(1, nativeClient);
            Object callback = call(agent, "getCallback");
            callback.getClass().getMethod("serializeResponse", String.class, type("service.serialization.ISerializeEntry"))
                    .invoke(callback, "onShareAgentFound", event);
            main.postDelayed(() -> { if (active == attempt && attempt.entity == null) finish(attempt, false); }, 15_000);
        } catch (ReflectiveOperationException | RuntimeException failure) { finish(attempt, false); throw failure; }
    }

    private void prepare(Attempt attempt) {
        if (attempt.done) { completeNative(attempt, false); return; }
        try {
            Object data = call(attempt.entity, "getData");
            List<?> records = (List<?>) call(data, "getRecords");
            if (records.isEmpty() || records.size() > 4) throw new IOException("invalid_file_count");
            List<Uri> uris = new ArrayList<>();
            for (Object record : records) {
                Uri uri = Uri.parse((String) call(record, "getUri"));
                FileTransferUris.requireContent(uri);
                uris.add(uri);
            }
            attempt.files = FileTransferBridge.nfcSender(context, client);
            updatePeerName(client.projection());
            ui.card(attempt.entity).whenComplete((ignored, error) -> main.post(() -> {
                if (attempt.done) return;
                if (error != null) { finish(attempt, false); return; }
                worker.execute(() -> snapshotAndSend(attempt, uris));
            }));
            main.postDelayed(() -> finish(attempt, false), 720_000);
        } catch (Exception failure) { finish(attempt, false); }
    }

    private void snapshotAndSend(Attempt attempt, List<Uri> uris) {
        List<FileTransferSource> sources = new ArrayList<>();
        boolean success = false;
        try {
            for (Uri uri : uris) {
                if (attempt.done) throw new IOException("cancelled");
                String name; long size;
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IOException("metadata_unavailable");
                    name = cursor.getString(0); size = cursor.isNull(1) ? -1 : cursor.getLong(1);
                }
                String mime = context.getContentResolver().getType(uri);
                sources.add(FileTransferUris.snapshot(context, uri, name, mime == null ? "application/octet-stream" : mime, size));
            }
            CompletableFuture<org.json.JSONObject> ready = new CompletableFuture<>();
            waiting(attempt, ready);
            main.post(() -> NfcTapActivity.waitForPeer(client, main, attempt.target, "file_prepare", () -> !attempt.done, projection -> {
                if (!attempt.done) updatePeerName(projection);
                ready.complete(projection);
            }));
            var projection = ready.get(125, TimeUnit.SECONDS);
            if (attempt.done || projection == null || !attempt.files.ready()) throw new IOException("peer_unavailable");
            // Tapping prepares/connects; only the OEM upward gesture authorizes a send.
            waiting(attempt, attempt.swiped);
            attempt.swiped.get(60, TimeUnit.SECONDS);
            if (attempt.done) throw new IOException("cancelled");
            String session = projection.getString("session_id");
            List<String> tokens = sources.stream().map(source -> UUID.randomUUID().toString().replace("-", "")).toList();
            CompletableFuture<org.json.JSONObject> accepted = new CompletableFuture<>();
            waiting(attempt, accepted);
            main.post(() -> {
                if (attempt.done) { accepted.complete(null); return; }
                NfcTapActivity.request(client, projection, attempt.target, new JSONArray(tokens), accepted::complete);
            });
            if (!NfcTapActivity.accepted(accepted.get(30, TimeUnit.SECONDS))) throw new IOException("rejected");
            long total = sources.stream().mapToLong(source -> source.offer().size()).sum();
            long committedBytes = 0;
            java.util.concurrent.atomic.AtomicInteger lastPercent = new java.util.concurrent.atomic.AtomicInteger(-1);
            for (int i = 0; i < sources.size(); i++) {
                if (attempt.done) throw new IOException("cancelled");
                var allocation = attempt.files.allocate(FileTransferNegotiation.Purpose.NFC_TAP, tokens.get(i));
                FileTransferEngine.Key key;
                try { key = allocation.get(7, TimeUnit.SECONDS); }
                catch (Exception failure) { allocation.thenAccept(attempt.files::release); throw failure; }
                try {
                    if (!session.equals(key.session()) || attempt.done) throw new IOException("stale_session");
                    long prior = committedBytes;
                    CompletableFuture<?> transfer = attempt.files.send(key, sources.get(i), k -> !attempt.done && attempt.files.current(k), bytes -> {
                        int percent = total == 0 ? 0 : (int) Math.min(99, (prior + bytes) * 100 / total);
                        if (lastPercent.getAndSet(percent) != percent) main.post(() -> { if (!attempt.done) ui.progress(percent); });
                    });
                    synchronized (attempt) {
                        attempt.transfer = transfer;
                        if (attempt.done) transfer.cancel(true);
                    }
                    try { transfer.get(2, TimeUnit.MINUTES); }
                    catch (Exception failure) { transfer.cancel(true); throw failure; }
                    Log.i(NfcTapActivity.LOG, "native_share_file_committed");
                    committedBytes += sources.get(i).offer().size();
                } finally { attempt.files.release(key); }
            }
            success = !attempt.done;
        } catch (Exception failure) { Log.w(NfcTapActivity.LOG, "native_share_failed " + failure.getClass().getSimpleName()); }
        finally {
            for (FileTransferSource source : sources) try { source.close(); } catch (IOException ignored) { }
            boolean committed = success;
            main.post(() -> finish(attempt, committed));
        }
    }

    private synchronized void finish(Attempt attempt, boolean success) {
        if (attempt.done) return;
        synchronized (attempt) {
            attempt.done = true;
            if (attempt.transfer != null) attempt.transfer.cancel(true);
            if (attempt.waiting != null) attempt.waiting.cancel(true);
            attempt.swiped.cancel(true);
        }
        if (attempt.files != null) attempt.files.close();
        completeNative(attempt, success);
        if (active == attempt) {
            ui.finish(success);
            active = null;
        }
    }

    private static void waiting(Attempt attempt, CompletableFuture<?> future) {
        synchronized (attempt) { attempt.waiting = future; if (attempt.done) future.cancel(true); }
    }

    private void updatePeerName(org.json.JSONObject projection) {
        var peer = projection == null ? null : projection.optJSONObject("real");
        if (peer != null) ui.peerName(peer.optString("name"));
    }

    private void completeNative(Attempt attempt, boolean success) {
        if (attempt.entity == null) return;
        try {
            Object result = type("share.sdk.InternalSealedResult").getConstructor(int.class, String.class)
                    .newInstance(success ? 0 : 1023, success ? "" : "file_transfer_failed");
            var constructor = Class.forName("kotlin.Result", false, loader).getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);
            Object deferred = call(attempt.entity, "getSealedResultDeffer");
            deferred.getClass().getMethod("N", Object.class).invoke(deferred, constructor.newInstance(result));
        } catch (ReflectiveOperationException failure) { Log.w(NfcTapActivity.LOG, "native_share_result_unavailable", failure); }
    }

    private static boolean sameAgent(Object first, Object second) throws ReflectiveOperationException {
        return call(first, "getAgentId").equals(call(second, "getAgentId"))
                && call(first, "getPkg").equals(call(second, "getPkg")) && call(first, "getPid").equals(call(second, "getPid"));
    }
    private Class<?> type(String suffix) throws ClassNotFoundException { return Class.forName("com.oplus.interconnect." + suffix, false, loader); }
    static Object call(Object target, String name) throws ReflectiveOperationException { return target.getClass().getMethod(name).invoke(target); }
}
