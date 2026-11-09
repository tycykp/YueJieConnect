package io.github.yuejie.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Per-peer OEM clipboard consumer bridge. Broadcast consumers with no explicit peer stay native. */
final class OplusClipboardAdapter {
    interface Transport {
        boolean ready(JSONObject projection);
        CompletableFuture<Receipt> write(String id, String session, String request, String text);
        default CompletableFuture<Boolean> cancel(String id, String session, String request) {
            return CompletableFuture.completedFuture(false);
        }
    }
    record Receipt(String id, String session, String request, int utf8Bytes) {}
    private static final String CONSUMER = "com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.";
    private final XposedModule module;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, Binding> consumers = new WeakHashMap<>();
    private final AtomicLong serial = new AtomicLong();
    private final String requestPrefix = "oplus-clip-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-";
    private OplusClipboardEndpoint endpoint;
    private Method initSuccess;
    private OplusClipboardNativeResult nativeResult;
    private OplusClipboardPhoneSource phoneSource;
    private volatile boolean closed;
    private volatile CompletableFuture<Receipt> lastTransfer;

    private static final class Binding {
        final String id, session;
        final WeakReference<Object> listener;
        final WeakReference<Object> consumer;
        volatile boolean initialized;
        volatile CompletableFuture<Receipt> transfer;
        Binding(String id, String session, Object listener, Object consumer) {
            this.id = id; this.session = session; this.listener = new WeakReference<>(listener); this.consumer = new WeakReference<>(consumer);
        }
    }

    OplusClipboardAdapter(XposedModule module, ClassLoader loader, BooleanSupplier allowed,
                          Supplier<JSONObject> projection, Transport transport) {
        this.module = module; this.loader = loader; this.allowed = allowed;
        this.projection = projection; this.transport = transport;
    }

    void install() throws ReflectiveOperationException {
        endpoint = new OplusClipboardEndpoint(loader);
        nativeResult = new OplusClipboardNativeResult(loader);
        Class<?> consumer = Class.forName(CONSUMER + "ClipBoardConsumerImpl", false, loader);
        Class<?> listener = Class.forName(CONSUMER + "ClipBoardServiceConsumer$StatusListener", false, loader);
        initSuccess = listener.getMethod("initSuccess", String.class);
        try {
            hooks.add(module.hook(consumer.getMethod("setStatusListener", String.class, listener))
                    .setId("yj.oplus.clipboard.bind").intercept(chain -> {
                        Object result = chain.proceed(); // Sets native fields; does not create OAF connections.
                        Object instance = chain.getThisObject();
                        String id = (String) chain.getArg(0);
                        JSONObject p = projection.get();
                        synchronized (consumers) {
                            Binding previous = consumers.remove(instance);
                            abandon(previous, "binding_replaced");
                            if (!closed && allowed.getAsBoolean() && p != null && id != null && id.equals(p.optString("id"))
                                    && p.optString("session_id").matches("[0-9a-f]{64}") && consumers.size() < 16)
                                consumers.put(instance, new Binding(id, p.optString("session_id"), chain.getArg(1), instance));
                        }
                        return result;
                    }));
            hooks.add(module.hook(consumer.getMethod("getInstance", Context.class))
                    .setId("yj.oplus.clipboard.initialize").intercept(chain -> {
                        Binding binding = binding(chain.getThisObject());
                        if (binding == null) return chain.proceed();
                        main.post(() -> {
                            binding.initialized = current(binding);
                            status(binding, binding.initialized ? binding.id : "");
                        });
                        return null;
                    }));
            hooks.add(module.hook(consumer.getMethod("copyToClip"))
                    .setId("yj.oplus.clipboard.copy").intercept(chain -> {
                        Binding binding = binding(chain.getThisObject());
                        if (binding == null) return chain.proceed();
                        main.post(() -> send(binding, null));
                        return null;
                    }));
            hooks.add(module.hook(consumer.getMethod("copyToClipOld", byte[].class))
                    .setId("yj.oplus.clipboard.copy.legacy").intercept(chain -> {
                        Binding binding = binding(chain.getThisObject());
                        if (binding == null) return chain.proceed();
                        byte[] source = (byte[]) chain.getArg(0);
                        byte[] copy = source == null || source.length > OplusClipboardCodec.MAX_BYTES ? null : source.clone();
                        main.post(() -> {
                            if (binding.transfer != null && !binding.transfer.isDone()) notifyFailure(binding, "clipboard_busy");
                            else if (copy == null) reject(binding, "invalid_payload");
                            else send(binding, copy);
                        });
                        return null;
                    }));
            hooks.add(module.hook(consumer.getMethod("getClipData"))
                    .setId("yj.oplus.clipboard.get").intercept(chain -> {
                        Binding binding = binding(chain.getThisObject());
                        if (binding == null) return chain.proceed();
                        main.post(() -> reject(binding, "clipboard_read_not_granted"));
                        return null;
                    }));
            hooks.add(module.hook(consumer.getMethod("destroy"))
                    .setId("yj.oplus.clipboard.destroy").intercept(chain -> {
                        Binding binding;
                        synchronized (consumers) { binding = consumers.remove(chain.getThisObject()); }
                        if (binding == null) return chain.proceed();
                        main.post(() -> abandon(binding, "consumer_closed"));
                        return null;
                    }));
            phoneSource = new OplusClipboardPhoneSource(module, loader, allowed, projection, transport,
                    transfer -> lastTransfer = transfer);
            phoneSource.install();
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }

    private Binding binding(Object consumer) { synchronized (consumers) { return consumers.get(consumer); } }
    private boolean current(Binding binding) {
        JSONObject p = projection.get();
        return !closed && allowed.getAsBoolean() && p != null && binding.id.equals(p.optString("id"))
                && binding.session.equals(p.optString("session_id")) && transport.ready(p);
    }

    private void send(Binding binding, byte[] bytes) {
        if (!binding.initialized || !current(binding)) { reject(binding, "clipboard_peer_unavailable"); return; }
        if (binding.transfer != null && !binding.transfer.isDone()) { notifyFailure(binding, "clipboard_busy"); return; }
        try {
            String text = bytes == null ? endpoint.readText() : OplusClipboardCodec.decode(0, 0, bytes);
            int count = OplusClipboardCodec.encode(text).length;
            Object consumer = binding.consumer.get();
            if (consumer == null) { reject(binding, "consumer_closed"); return; }
            Object nativeObserver = nativeResult.observer(consumer);
            String request = requestPrefix + serial.incrementAndGet();
            CompletableFuture<Receipt> completion = new CompletableFuture<>();
            lastTransfer = completion;
            binding.transfer = completion;
            Runnable timeout = () -> completion.completeExceptionally(new IllegalStateException("request_timeout"));
            main.postDelayed(timeout, 12_000);
            completion.whenComplete((ignored, error) -> {
                main.removeCallbacks(timeout);
                if (error != null) {
                    try { transport.cancel(binding.id, binding.session, request); }
                    catch (RuntimeException ignoredCancel) { log("clipboard_cancel_unavailable"); }
                }
                main.post(() -> {
                    try { nativeResult.deliver(nativeObserver, error); }
                    catch (ReflectiveOperationException | RuntimeException callbackFailure) { log("clipboard_native_result_failed"); }
                });
            });
            try { transport.write(binding.id, binding.session, request, text).whenComplete((receipt, error) -> main.post(() -> {
                if (completion.isDone()) return;
                if (!current(binding)) completion.completeExceptionally(new IllegalStateException("stale_session"));
                else if (error != null) completion.completeExceptionally(error);
                else if (receipt == null || !binding.id.equals(receipt.id()) || !binding.session.equals(receipt.session())
                        || !request.equals(receipt.request()) || count != receipt.utf8Bytes())
                    completion.completeExceptionally(new IllegalStateException("invalid_remote_receipt"));
                else completion.complete(receipt);
                log(completion.isCompletedExceptionally() ? "clipboard_remote_write_failed" : "clipboard_remote_write_verified");
            })); } catch (RuntimeException failure) {
                completion.completeExceptionally(new IllegalStateException("clipboard_transport_failed"));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            reject(binding, error instanceof SecurityException ? "permission_denied" : "clipboard_payload_unavailable");
        }
    }

    private void reject(Binding binding, String error) {
        // A second rejected invocation must not complete or replace the first request.
        notifyFailure(binding, error);
        if (binding.transfer == null || binding.transfer.isDone()) {
            binding.transfer = CompletableFuture.failedFuture(new IllegalStateException(error));
            lastTransfer = binding.transfer;
        }
    }

    private void notifyFailure(Binding binding, String error) {
        Object consumer = binding.consumer.get();
        if (consumer != null && nativeResult != null) {
            try { nativeResult.deliver(nativeResult.observer(consumer), new IllegalStateException(error)); }
            catch (ReflectiveOperationException | RuntimeException failure) { log("clipboard_native_result_failed"); }
        }
        log(error);
    }

    private void status(Binding binding, String id) {
        Object listener = binding.listener.get();
        if (listener == null) return;
        try { initSuccess.invoke(listener, id); }
        catch (ReflectiveOperationException | RuntimeException error) { log("clipboard_native_callback_failed"); }
    }

    private void abandon(Binding binding, String error) {
        if (binding == null) return;
        binding.initialized = false;
        if (binding.transfer != null && !binding.transfer.isDone())
            binding.transfer.completeExceptionally(new IllegalStateException(error));
        // Local completion cancellation does not claim a remote write was undone.
    }

    CompletableFuture<Receipt> lastTransfer() { return lastTransfer; }
    void updateSource(Context context, boolean authorized) { if (phoneSource != null) phoneSource.update(context, authorized); }
    void sessionChanged() {
        if (phoneSource != null) phoneSource.sessionChanged();
        main.post(() -> {
            synchronized (consumers) {
                for (Binding binding : consumers.values()) if (!current(binding)) abandon(binding, "stale_session");
                // Keep stale bindings until destroy/rebind: an old proxy must not fall back into OAF.
            }
        });
    }
    void close() {
        closed = true;
        if (phoneSource != null) phoneSource.close();
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        main.post(() -> {
            synchronized (consumers) { for (Binding b : consumers.values()) abandon(b, "adapter_closed"); consumers.clear(); }
        });
    }
    private void log(String message) { module.log(Log.INFO, "YueJie", "oplus_" + message); }
}
