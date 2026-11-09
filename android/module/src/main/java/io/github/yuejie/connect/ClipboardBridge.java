package io.github.yuejie.connect;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedModule;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Concrete binding to shared-contract v3. The shared owner installs this in the verified host. */
final class ClipboardBridge implements AutoCloseable {
    /** Implemented only by the optional debug probe; never supplies business results. */
    interface Probe extends AutoCloseable {
        boolean permitOutbound(String text);
        boolean permitInbound(String text);
        void received(String session, String request, String text, String error);
        default boolean permitUriOutbound(android.content.ClipData clip) { return true; }
        default boolean permitUriInbound(String name, long size, String sha256) { return true; }
        default void attachUriTransfers(java.util.function.Supplier<? extends CompletableFuture<?>> transfers) {}
        default void uriStaged(String session, String name, android.content.ClipData clip) {}
        default void uriApplied(String session, String name, android.content.ClipData clip) {}
        default void uriConsumed(String session, String name) {}
        @Override void close();
    }
    private static volatile Probe activeProbe;
    static Probe activeProbe() { return activeProbe; }
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final int source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, CompletableFuture<OplusClipboardAdapter.Receipt>> outbound = new ConcurrentHashMap<>();
    private BridgeClient.Subscription projectionSubscription, executorSubscription;
    private OplusClipboardAdapter oplusAdapter;
    private OplusClipboardReceiver oplusReceiver;
    private OplusClipboardEndpoint oplusEndpoint;
    private XiaomiClipboardAdapter xiaomiAdapter;
    private XiaomiClipboardReceiver xiaomiReceiver;
    private XiaomiClipboardEndpoint xiaomiEndpoint;
    private XiaomiClipboardSourceLease xiaomiSource;
    record RichReceipt(String peer, String session, String request, int utf8Bytes) {}
    private ClipboardNativeContent richEndpoint;
    private ClipboardRichReceiver richReceiver;
    private ClipboardRichSource richSource;
    private final ClipboardEchoGuard richEchoes = new ClipboardEchoGuard();
    private BridgeClient.Subscription richExecutorSubscription;
    private final Map<String, CompletableFuture<RichReceipt>> richOutbound = new ConcurrentHashMap<>();
    private CompletableFuture<RichReceipt> lastRichTransfer;
    private final String richPrefix = "rich-clip-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-";
    private long richSerial;
    private volatile boolean closed;
    private boolean installed;
    private Probe probe;
    private final Runnable registration = this::registerWhenReady;

    ClipboardBridge(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed, int source) {
        if (source != 1 && source != 2) throw new IllegalArgumentException("invalid_brand");
        this.module = module; this.client = client; this.loader = loader; this.allowed = allowed; this.source = source;
    }

    void install(Context context) throws ReflectiveOperationException {
        if (installed || closed) throw new IllegalStateException("clipboard_lifecycle");
        String pkg = source == 1 ? "com.xiaomi.mirror" : "com.oplus.linker";
        String process = source == 1 ? "com.xiaomi.mirror" : "com.oplus.linker:app";
        if (context == null || !allowed.getAsBoolean() || !pkg.equals(context.getPackageName())
                || !process.equals(Application.getProcessName())) throw new SecurityException("invalid_executor_context");
        installed = true;
        try {
            if (source == 1) {
                xiaomiEndpoint = new XiaomiClipboardEndpoint(loader);
                xiaomiSource = new XiaomiClipboardSourceLease(loader);
                xiaomiReceiver = new XiaomiClipboardReceiver(context, loader, this::authorized, client::projection);
                xiaomiAdapter = new XiaomiClipboardAdapter(module, loader, this::authorized, client::projection, transport);
                xiaomiAdapter.install();
            } else {
                oplusEndpoint = new OplusClipboardEndpoint(loader);
                oplusReceiver = new OplusClipboardReceiver(context, loader, this::authorized, client::projection);
                oplusAdapter = new OplusClipboardAdapter(module, loader, this::authorized, client::projection, transport);
                oplusAdapter.install();
            }
            try {
                richEndpoint = new ClipboardNativeContent(module, context, loader, source, "yj.clipboard.local.html");
                richReceiver = new ClipboardRichReceiver(richEndpoint, richEchoes, this::richAuthorized, client::projection);
                richSource = new ClipboardRichSource(context, richEchoes, this::richAuthorized,
                        client::projection, this::sendHtml);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                if (richEndpoint != null) { richEndpoint.close(); richEndpoint = null; }
                richReceiver = null; richSource = null;
                module.log(android.util.Log.WARN, "YueJie", "clipboard_rich_endpoint_unavailable");
            }
            projectionSubscription = client.addProjectionListener(ignored -> sessionChanged());
            // attach() precedes Application.onCreate(): do not advertise Mirror while its actual
            // ResourceManager/ClipboardManager do not exist. No clipboard read is used here.
            main.post(registration);
            main.post(() -> installProbe(context));
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    private void installProbe(Context context) {
        if (closed) return;
        try {
            // src/debug only: release APKs do not contain this class or a probe receiver.
            probe = (Probe) Class.forName("io.github.yuejie.connect.ClipboardNativeProbe", false,
                    ClipboardBridge.class.getClassLoader()).getDeclaredMethod("install", Context.class,
                    XposedModule.class, ClassLoader.class, BridgeClient.class, int.class,
                    java.util.function.Supplier.class, java.util.function.Supplier.class).invoke(null, context, module, loader, client, source,
                    (java.util.function.Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>>) this::lastTransfer,
                    (java.util.function.Supplier<CompletableFuture<RichReceipt>>) this::lastRichTransfer);
            activeProbe = probe;
        } catch (ClassNotFoundException releaseBuild) {
            // Expected in release builds.
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(android.util.Log.WARN, "YueJie", "clipboard_probe_unavailable");
        }
    }

    private CompletableFuture<OplusClipboardAdapter.Receipt> lastTransfer() {
        return xiaomiAdapter != null ? xiaomiAdapter.lastTransfer() : oplusAdapter.lastTransfer();
    }

    private boolean authorized() { return !closed && allowed.getAsBoolean()
            && (oplusEndpoint == null || oplusEndpoint.enabled(client.context())); }
    private void registerWhenReady() {
        if (closed) return;
        boolean available = authorized() && (xiaomiEndpoint == null || xiaomiEndpoint.available());
        if (xiaomiSource != null) try { xiaomiSource.update(available && transport.ready(client.projection())); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            available = false;
            module.log(android.util.Log.WARN, "YueJie", "xiaomi_clipboard_source_unavailable");
        }
        if (oplusAdapter != null) try { oplusAdapter.updateSource(client.context(), available && transport.ready(client.projection())); }
        catch (RuntimeException failure) {
            available = false;
            module.log(android.util.Log.WARN, "YueJie", "oplus_clipboard_source_unavailable");
        }
        if (available && executorSubscription == null) executorSubscription = client.registerExecutor("clipboard_write", executor);
        else if (!available && executorSubscription != null) {
            executorSubscription.close(); executorSubscription = null; sessionChanged();
        }
        boolean richAvailable = available && richEndpoint != null;
        if (richAvailable && richExecutorSubscription == null)
            richExecutorSubscription = client.registerExecutor("clipboard_rich_write", richExecutor);
        else if (!richAvailable && richExecutorSubscription != null) {
            richExecutorSubscription.close(); richExecutorSubscription = null;
            if (richReceiver != null) richReceiver.sessionChanged();
        }
        if (richSource != null) richSource.update();
        main.postDelayed(registration, 1000);
    }

    private boolean richAuthorized() {
        if (richEndpoint == null || !authorized() || xiaomiEndpoint != null && !xiaomiEndpoint.available()) return false;
        JSONObject p = client.projection();
        if (p == null || !p.optString("session_id").matches("[0-9a-f]{64}")) return false;
        JSONArray operations = p.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("clipboard_rich_write".equals(operations.optString(i))) return true;
        return false;
    }
    private boolean richCurrent(String peer, String session) {
        JSONObject p = client.projection();
        return richAuthorized() && p != null && peer.equals(p.optString("id")) && session.equals(p.optString("session_id"));
    }
    CompletableFuture<RichReceipt> lastRichTransfer() { return lastRichTransfer; }
    private void sendHtml(ClipboardRichSource.Event event) {
        if (!richCurrent(event.peer(), event.session()) || probe != null && !probe.permitOutbound(event.html().text())) return;
        if (!richOutbound.isEmpty()) {
            module.log(android.util.Log.INFO, "YueJie", "clipboard_rich_busy"); return;
        }
        String request = richPrefix + ++richSerial;
        CompletableFuture<RichReceipt> result = new CompletableFuture<>();
        richOutbound.put(event.session() + ":" + request, result); lastRichTransfer = result;
        Runnable timeout = () -> result.completeExceptionally(new IllegalStateException("request_timeout"));
        main.postDelayed(timeout, 12_000);
        result.whenComplete((receipt, failure) -> {
            main.removeCallbacks(timeout); richOutbound.remove(event.session() + ":" + request, result);
            if (failure != null) client.cancel(event.session(), request, ignored -> {});
            module.log(android.util.Log.INFO, "YueJie", failure == null
                    ? "clipboard_rich_remote_write_verified" : "clipboard_rich_remote_write_failed");
        });
        try {
            client.request(event.peer(), event.session(), "clipboard_rich_write", request,
                    ClipboardRichCodec.encode(event.html()), response -> {
                JSONObject receipt = response == null ? null : response.optJSONObject("result");
                JSONObject data = receipt == null ? null : receipt.optJSONObject("data");
                if (!richCurrent(event.peer(), event.session())) result.completeExceptionally(new IllegalStateException("stale_session"));
                else if (response != null && "ok".equals(response.optString("status"))
                        && event.session().equals(response.optString("session_id")) && request.equals(response.optString("request_id"))
                        && receipt != null && "clipboard_rich_write".equals(receipt.optString("operation"))
                        && data != null && data.length() == 3 && "text/html".equals(data.optString("mime")) && Boolean.TRUE.equals(data.opt("applied"))
                        && (data.opt("utf8_bytes") instanceof Integer || data.opt("utf8_bytes") instanceof Long)
                        && data.optLong("utf8_bytes", -1) == event.html().utf8Bytes())
                    result.complete(new RichReceipt(event.peer(), event.session(), request, event.html().utf8Bytes()));
                else result.completeExceptionally(new IllegalStateException("clipboard_rich_remote_write_failed"));
            });
        } catch (JSONException | RuntimeException rejected) {
            result.completeExceptionally(new IllegalStateException("clipboard_payload_rejected"));
        }
    }

    private final BridgeClient.VendorExecutor richExecutor = new BridgeClient.VendorExecutor() {
        @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
            if (!richAuthorized() || richReceiver == null) { completion.fail("permission_denied"); return; }
            final ClipboardRichContent.Html html;
            try { html = ClipboardRichCodec.decode(task.optJSONObject("payload")); }
            catch (RuntimeException invalid) { completion.fail("invalid_payload"); return; }
            if (probe != null && !probe.permitInbound(html.text())) { completion.fail("clipboard_probe_guard"); return; }
            String peer = task.optString("projection_id"), session = task.optString("session_id"), request = task.optString("request_id");
            richReceiver.executeHtml(peer, session, request, html, task.optLong("deadline_elapsed_ms"))
                    .whenComplete((result, failure) -> {
                if (failure != null || result == null) { completion.fail("native_clipboard_failed"); return; }
                if (probe != null) probe.received(session, request, html.text(), result.error());
                if (!result.applied()) { completion.fail(result.error()); return; }
                try { completion.success(new JSONObject().put("mime", "text/html").put("applied", true).put("utf8_bytes", result.utf8Bytes())); }
                catch (JSONException impossible) { completion.fail("invalid_result"); }
            });
        }
        @Override public void cancel(String session, String request) {
            JSONObject p = client.projection();
            if (richReceiver != null && p != null) richReceiver.cancel(p.optString("id"), session, request);
        }
        @Override public void sessionChanged() { ClipboardBridge.this.sessionChanged(); }
    };

    private final OplusClipboardAdapter.Transport transport = new OplusClipboardAdapter.Transport() {
        @Override public boolean ready(JSONObject p) {
            if (!authorized() || p == null || !p.optString("session_id").matches("[0-9a-f]{64}")) return false;
            JSONArray operations = p.optJSONArray("operations");
            if (operations != null) for (int i = 0; i < operations.length(); i++)
                if ("clipboard_write".equals(operations.optString(i))) return true;
            return false;
        }
        @Override public CompletableFuture<OplusClipboardAdapter.Receipt> write(String id, String session, String request, String text) {
            if (probe != null && !probe.permitOutbound(text))
                return CompletableFuture.failedFuture(new IllegalStateException("clipboard_probe_guard"));
            JSONObject p = client.projection();
            if (!ready(p) || !id.equals(p.optString("id")) || !session.equals(p.optString("session_id")))
                return CompletableFuture.failedFuture(new IllegalStateException("stale_session"));
            CompletableFuture<OplusClipboardAdapter.Receipt> result = new CompletableFuture<>();
            String key = session + ":" + request;
            if (outbound.putIfAbsent(key, result) != null) return CompletableFuture.failedFuture(new IllegalStateException("clipboard_busy"));
            result.whenComplete((value, error) -> outbound.remove(key, result));
            try {
                JSONObject payload = new JSONObject().put("mime", "text/plain").put("utf8_b64", ClipboardWireCodec.encode(text));
                client.request(id, session, "clipboard_write", request, payload, response -> {
                    JSONObject live = client.projection();
                    if (!ready(live) || !id.equals(live.optString("id")) || !session.equals(live.optString("session_id"))) {
                        result.completeExceptionally(new IllegalStateException("stale_session")); return;
                    }
                    JSONObject receipt = response == null ? null : response.optJSONObject("result");
                    JSONObject data = receipt == null ? null : receipt.optJSONObject("data");
                    if (response != null && "ok".equals(response.optString("status"))
                            && session.equals(response.optString("session_id")) && request.equals(response.optString("request_id"))
                            && receipt != null && "clipboard_write".equals(receipt.optString("operation"))
                            && data != null && Boolean.TRUE.equals(data.opt("applied"))
                            && data.optInt("utf8_bytes", -1) == OplusClipboardCodec.encode(text).length)
                        result.complete(new OplusClipboardAdapter.Receipt(id, session, request, data.optInt("utf8_bytes")));
                    else result.completeExceptionally(new IllegalStateException(response != null && "cancelled".equals(response.optString("code"))
                            ? "cancelled" : "clipboard_remote_write_failed"));
                });
            } catch (JSONException | RuntimeException failure) {
                result.completeExceptionally(new IllegalStateException("clipboard_payload_rejected"));
            }
            return result;
        }
        @Override public CompletableFuture<Boolean> cancel(String id, String session, String request) {
            CompletableFuture<OplusClipboardAdapter.Receipt> pending = outbound.get(session + ":" + request);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            // Always send the cancellation, including when a local timeout already completed.
            client.cancel(session, request, response -> {
                if (pending == null || response == null || !"pending".equals(response.optString("status"))) result.complete(false);
                else pending.whenComplete((receipt, failure) -> result.complete(failure != null && "cancelled".equals(failure.getMessage())));
            });
            return result;
        }
    };

    private final BridgeClient.VendorExecutor executor = new BridgeClient.VendorExecutor() {
        @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
            if (!authorized()) { completion.fail("permission_denied"); return; }
            JSONObject payload = task.optJSONObject("payload");
            final String text;
            try {
                if (payload == null || payload.length() != 2 || !"text/plain".equals(payload.getString("mime")))
                    throw new IllegalArgumentException();
                text = ClipboardWireCodec.decode(payload.getString("utf8_b64"));
            } catch (JSONException | RuntimeException invalid) { completion.fail("invalid_payload"); return; }
            if (probe != null && !probe.permitInbound(text)) { completion.fail("clipboard_probe_guard"); return; }
            String session = task.optString("session_id"), request = task.optString("request_id");
            long deadline = task.optLong("deadline_elapsed_ms");
            if (xiaomiReceiver != null) xiaomiReceiver.execute(session, request, text, deadline)
                    .whenComplete((result, error) -> complete(completion, session, request, text, error == null ? result.error() : "native_clipboard_failed", error == null ? result.utf8Bytes() : 0));
            else oplusReceiver.execute(session, request, text, deadline)
                    .whenComplete((result, error) -> complete(completion, session, request, text, error == null ? result.error() : "native_clipboard_failed", error == null ? result.utf8Bytes() : 0));
        }
        @Override public void cancel(String session, String request) {
            if (xiaomiReceiver != null) xiaomiReceiver.cancel(session, request);
            if (oplusReceiver != null) oplusReceiver.cancel(session, request);
        }
        @Override public void sessionChanged() { ClipboardBridge.this.sessionChanged(); }
    };

    private void complete(BridgeClient.TaskCompletion completion, String session, String request, String text, String error, int bytes) {
        if (probe != null) probe.received(session, request, text, error);
        if (error != null) { completion.fail(error); return; }
        try { completion.success(new JSONObject().put("applied", true).put("utf8_bytes", bytes)); }
        catch (JSONException impossible) { completion.fail("invalid_result"); }
    }
    void sessionChanged() {
        if (xiaomiAdapter != null) xiaomiAdapter.sessionChanged();
        if (xiaomiReceiver != null) xiaomiReceiver.sessionChanged();
        if (oplusAdapter != null) oplusAdapter.sessionChanged();
        if (oplusReceiver != null) oplusReceiver.sessionChanged();
        if (richReceiver != null) richReceiver.sessionChanged();
        for (var item : richOutbound.entrySet()) {
            JSONObject p = client.projection();
            if (!richAuthorized() || p == null || !item.getKey().startsWith(p.optString("session_id") + ":"))
                item.getValue().completeExceptionally(new IllegalStateException("stale_session"));
        }
        if (richSource != null) main.post(richSource::update);
    }
    @Override public void close() {
        closed = true; main.removeCallbacks(registration);
        if (richSource != null) main.post(richSource::close);
        if (richReceiver != null) richReceiver.close();
        if (richEndpoint != null) richEndpoint.close();
        if (richExecutorSubscription != null) { richExecutorSubscription.close(); richExecutorSubscription = null; }
        for (var pending : richOutbound.values()) pending.completeExceptionally(new IllegalStateException("stale_session"));
        if (xiaomiSource != null) xiaomiSource.close();
        if (probe != null) { probe.close(); if (activeProbe == probe) activeProbe = null; probe = null; }
        if (projectionSubscription != null) { projectionSubscription.close(); projectionSubscription = null; }
        if (executorSubscription != null) { executorSubscription.close(); executorSubscription = null; }
        if (xiaomiAdapter != null) xiaomiAdapter.close();
        if (xiaomiReceiver != null) xiaomiReceiver.close();
        if (oplusAdapter != null) oplusAdapter.close();
        if (oplusReceiver != null) oplusReceiver.close();
    }
}
