package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Observes Mirror's native clipboard broadcast after OEM settings and loop suppression. */
final class XiaomiClipboardAdapter {
    private final XposedModule module;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final OplusClipboardAdapter.Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong serial = new AtomicLong();
    private final String requestPrefix = "xiaomi-clip-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-";
    private HookHandle hook;
    private XiaomiClipboardEndpoint endpoint;
    private volatile boolean closed;
    private volatile CompletableFuture<OplusClipboardAdapter.Receipt> lastTransfer;
    private String activeId, activeSession, activeRequest;

    XiaomiClipboardAdapter(XposedModule module, ClassLoader loader, BooleanSupplier allowed,
                           Supplier<JSONObject> projection, OplusClipboardAdapter.Transport transport) {
        this.module = module; this.loader = loader; this.allowed = allowed;
        this.projection = projection; this.transport = transport;
    }

    void install() throws ReflectiveOperationException {
        endpoint = new XiaomiClipboardEndpoint(loader);
        Class<?> manager = Class.forName("com.xiaomi.mirror.message.MessageManagerImpl", false, loader);
        Class<?> message = Class.forName("com.xiaomi.mirror.message.Message", false, loader);
        hook = module.hook(manager.getMethod("broadcastAll", message)).setId("yj.xiaomi.clipboard.broadcast")
                .intercept(chain -> {
                    // Existing native peers still receive their normal broadcast. This bridge adds
                    // only the explicitly authorized peer in the current authenticated projection.
                    Object value = chain.getArg(0);
                    JSONObject p = projection.get();
                    if (!closed && allowed.getAsBoolean() && p != null && transport.ready(p)
                            && endpoint.isClipboardMessage(value)) {
                        String id = p.optString("id"), session = p.optString("session_id");
                        try {
                            String text = endpoint.readSourceMessage(value); // Snapshot before native object reuse.
                            main.post(() -> send(id, session, text));
                        } catch (ReflectiveOperationException | RuntimeException invalid) {
                            log("clipboard_payload_rejected");
                        }
                    }
                    return chain.proceed();
                });
    }

    private boolean current(String id, String session) {
        JSONObject p = projection.get();
        return !closed && allowed.getAsBoolean() && p != null && !id.isEmpty()
                && session.matches("[a-f0-9]{64}") && id.equals(p.optString("id"))
                && session.equals(p.optString("session_id")) && transport.ready(p);
    }

    private void send(String id, String session, String text) {
        if (!current(id, session)) return;
        if (lastTransfer != null && !lastTransfer.isDone()) { log("clipboard_busy"); return; }
        String request = requestPrefix + serial.incrementAndGet();
        int count = OplusClipboardCodec.encode(text).length;
        CompletableFuture<OplusClipboardAdapter.Receipt> completion = new CompletableFuture<>();
        lastTransfer = completion;
        activeId = id; activeSession = session; activeRequest = request;
        Runnable timeout = () -> completion.completeExceptionally(new IllegalStateException("request_timeout"));
        main.postDelayed(timeout, 12_000);
        completion.whenComplete((receipt, failure) -> {
            main.removeCallbacks(timeout);
            if (failure != null) cancel(id, session, request);
            // Mirror broadcastAll has no native response callback. This is the actual remote
            // receipt, available to the integration/probe; never invent a native send-success.
            log(failure == null ? "clipboard_remote_write_verified" : "clipboard_remote_write_failed");
        });
        try {
            transport.write(id, session, request, text).whenComplete((receipt, error) -> main.post(() -> {
                if (completion.isDone()) return;
                if (!current(id, session)) completion.completeExceptionally(new IllegalStateException("stale_session"));
                else if (error != null) completion.completeExceptionally(error);
                else if (receipt == null || !id.equals(receipt.id()) || !session.equals(receipt.session())
                        || !request.equals(receipt.request()) || count != receipt.utf8Bytes())
                    completion.completeExceptionally(new IllegalStateException("invalid_remote_receipt"));
                else completion.complete(receipt);
            }));
        } catch (RuntimeException error) {
            completion.completeExceptionally(new IllegalStateException("clipboard_transport_failed"));
        }
    }

    private void cancel(String id, String session, String request) {
        try { transport.cancel(id, session, request); }
        catch (RuntimeException ignored) { log("clipboard_cancel_unavailable"); }
    }
    void sessionChanged() {
        main.post(() -> {
            if (lastTransfer != null && !lastTransfer.isDone() && !current(activeId, activeSession))
                lastTransfer.completeExceptionally(new IllegalStateException("stale_session"));
        });
    }
    CompletableFuture<OplusClipboardAdapter.Receipt> lastTransfer() { return lastTransfer; }
    void close() {
        closed = true;
        if (hook != null) { hook.unhook(); hook = null; }
        sessionChanged();
    }
    private void log(String value) { module.log(Log.INFO, "YueJie", "xiaomi_" + value); }
}
