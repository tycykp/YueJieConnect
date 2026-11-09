package io.github.yuejie.connect;

import android.content.Context;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

/** ColorOS phone -> tablet native type-27 clipboard event, after OEM repeat suppression. */
final class OplusClipboardPhoneSource implements AutoCloseable {
    private final XposedModule module;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final OplusClipboardAdapter.Transport transport;
    private final Consumer<CompletableFuture<OplusClipboardAdapter.Receipt>> observe;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong serial = new AtomicLong();
    private final String prefix = "oplus-pad-clip-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-";
    private HookHandle hook;
    private OplusClipboardEndpoint endpoint;
    private Method clipboardSwitch, contextGetter;
    private Method repeated;
    private Object repeatOwner;
    private ClipboardManager registeredOn;
    private ClipboardManager.OnPrimaryClipChangedListener listener;
    private String lastSession, lastDigest;
    private long lastTimestamp, lastSentAt;
    private volatile boolean closed;
    private CompletableFuture<OplusClipboardAdapter.Receipt> active;
    private String activeId, activeSession;

    OplusClipboardPhoneSource(XposedModule module, ClassLoader loader, BooleanSupplier allowed,
            Supplier<JSONObject> projection, OplusClipboardAdapter.Transport transport,
            Consumer<CompletableFuture<OplusClipboardAdapter.Receipt>> observe) {
        this.module = module; this.loader = loader; this.allowed = allowed;
        this.projection = projection; this.transport = transport; this.observe = observe;
    }

    void install() throws ReflectiveOperationException {
        endpoint = new OplusClipboardEndpoint(loader);
        Class<?> scene = Class.forName("com.oplus.linker.synergy.wisetransfer.ICDFSceneManager", false, loader);
        contextGetter = scene.getMethod("getMContext");
        clipboardSwitch = Class.forName("com.oplus.linker.synergy.common.utils.ConnectPCUtil", false, loader)
                .getMethod("getClipboardSwitchState", Context.class);
        Class<?> nativeScene = Class.forName("com.oplus.linker.synergy.wisetransfer.clipboard.scene.ClipBoardDataBusScene", false, loader);
        repeatOwner = nativeScene.getField("Companion").get(null);
        repeated = repeatOwner.getClass().getMethod("isClipDataRepeat");
        Method event = scene.getDeclaredMethod("dealClipBoardInfo");
        hook = module.hook(event).setId("yj.oplus.clipboard.phone.source").intercept(chain -> {
            JSONObject p = projection.get();
            if (!closed && allowed.getAsBoolean() && tablet(p)) {
                try {
                    Context context = (Context) contextGetter.invoke(chain.getThisObject());
                    if (enabled(context)) {
                        // execute(27,Object,Object) reaches this method only after native null and
                        // isClipDataRepeat checks. Preserve existing native destinations; add only
                        // the authenticated real tablet projection with the same native setting.
                        OplusClipboardEndpoint.Snapshot snapshot = endpoint.snapshot();
                        String id = p.optString("id"), session = p.optString("session_id");
                        main.post(() -> send(context, id, session, snapshot));
                    }
                } catch (ReflectiveOperationException | RuntimeException invalid) { log("source_unavailable"); }
            }
            return chain.proceed();
        });
        // This private method can be inlined into execute; retain the genuine native call site.
        module.deoptimize(scene.getMethod("execute", int.class, Object.class, Object.class));
    }

    /** Covers a confirmed projection that has no OEM Metis peer record. It observes actual
     * platform copies and uses the same OEM setting/read/repeat policy. It does not manufacture
     * a Metis message, PC consumer, native connection state, or a success callback. */
    void update(Context context, boolean authorized) {
        if (closed || !authorized || !enabled(context)) { releaseListener(); return; }
        ClipboardManager manager = context.getSystemService(ClipboardManager.class);
        if (manager == null) { releaseListener(); return; }
        if (registeredOn == manager && listener != null) return;
        releaseListener();
        ClipboardManager.OnPrimaryClipChangedListener next = () -> {
            JSONObject p = projection.get();
            if (closed || !allowed.getAsBoolean() || !tablet(p) || !enabled(context)) return;
            try {
                if (Boolean.TRUE.equals(repeated.invoke(repeatOwner))) return;
                OplusClipboardEndpoint.Snapshot snapshot = endpoint.snapshot();
                String id = p.optString("id"), session = p.optString("session_id");
                main.post(() -> send(context, id, session, snapshot));
            } catch (ReflectiveOperationException | RuntimeException error) { log("source_unavailable"); }
        };
        manager.addPrimaryClipChangedListener(next);
        listener = next; registeredOn = manager;
        // No initial read: wait for a real system copy after authorization.
    }

    private void releaseListener() {
        if (listener == null) return;
        registeredOn.removePrimaryClipChangedListener(listener);
        listener = null; registeredOn = null;
    }

    private boolean tablet(JSONObject p) {
        JSONObject real = p == null ? null : p.optJSONObject("real");
        return p != null && p.optInt("sdk_device_type") == 10 && real != null
                && "tablet".equals(real.optString("kind")) && transport.ready(p);
    }
    private boolean current(String id, String session) {
        JSONObject p = projection.get();
        return !closed && allowed.getAsBoolean() && tablet(p) && !id.isEmpty()
                && session.matches("[0-9a-f]{64}") && id.equals(p.optString("id")) && session.equals(p.optString("session_id"));
    }
    private boolean enabled(Context context) {
        try { return context != null && "com.oplus.linker".equals(context.getPackageName())
                    && Boolean.TRUE.equals(clipboardSwitch.invoke(null, context)); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    private void send(Context context, String id, String session, OplusClipboardEndpoint.Snapshot snapshot) {
        if (!current(id, session) || !enabled(context)) return;
        String text = snapshot.text();
        String digest = digest(text);
        long now = SystemClock.elapsedRealtime();
        if (session.equals(lastSession) && digest.equals(lastDigest)
                && (snapshot.timestamp() > 0 && snapshot.timestamp() == lastTimestamp
                    || snapshot.timestamp() == 0 && now - lastSentAt < 500)) return;
        if (active != null && !active.isDone()) { log("busy"); return; }
        String request = prefix + serial.incrementAndGet();
        int bytes = OplusClipboardCodec.encode(text).length;
        CompletableFuture<OplusClipboardAdapter.Receipt> completion = new CompletableFuture<>();
        active = completion; activeId = id; activeSession = session; observe.accept(completion);
        lastSession = session; lastDigest = digest; lastTimestamp = snapshot.timestamp(); lastSentAt = now;
        Runnable timeout = () -> completion.completeExceptionally(new IllegalStateException("request_timeout"));
        main.postDelayed(timeout, 12_000);
        completion.whenComplete((receipt, failure) -> {
            main.removeCallbacks(timeout);
            if (failure != null) {
                try { transport.cancel(id, session, request); }
                catch (RuntimeException unavailable) { log("cancel_unavailable"); }
            }
            // This DataBus source branch has no response callback. Record only the real remote
            // receipt, as with Mirror.broadcastAll; never construct an unrelated PC/OAF observer.
            log(failure == null ? "remote_write_verified" : "remote_write_failed");
        });
        try {
            transport.write(id, session, request, text).whenComplete((receipt, error) -> main.post(() -> {
                if (completion.isDone()) return;
                if (!current(id, session) || !enabled(context)) completion.completeExceptionally(new IllegalStateException("stale_session"));
                else if (error != null) completion.completeExceptionally(error);
                else if (receipt == null || !id.equals(receipt.id()) || !session.equals(receipt.session())
                        || !request.equals(receipt.request()) || bytes != receipt.utf8Bytes())
                    completion.completeExceptionally(new IllegalStateException("invalid_remote_receipt"));
                else completion.complete(receipt);
            }));
        } catch (RuntimeException error) { completion.completeExceptionally(new IllegalStateException("clipboard_transport_failed")); }
    }
    void sessionChanged() {
        main.post(() -> {
            if (active != null && !active.isDone() && !current(activeId, activeSession))
                active.completeExceptionally(new IllegalStateException("stale_session"));
        });
    }
    @Override public void close() {
        closed = true;
        releaseListener();
        if (hook != null) { hook.unhook(); hook = null; }
        sessionChanged();
    }
    private static String digest(String text) {
        try { return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(OplusClipboardCodec.encode(text))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable", impossible); }
    }
    private void log(String code) { module.log(Log.INFO, "YueJie", "oplus_clipboard_phone_" + code); }
}
