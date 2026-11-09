package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Actual platform HTML copy observer in the OEM process; no synthetic native message/callback.
 * Callback must use a separately authorized rich transport, and must report its real receipt. */
final class ClipboardRichSource implements AutoCloseable {
    record Event(String peer, String session, ClipboardRichContent.Html html, long timestamp) {}
    private final Context context;
    private final ClipboardEchoGuard echoes;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final Consumer<Event> consumer;
    private ClipboardManager registeredOn;
    private ClipboardManager.OnPrimaryClipChangedListener listener;
    private String previousSession, previousDigest;
    private long previousTimestamp;
    private boolean closed;

    ClipboardRichSource(Context context, ClipboardEchoGuard echoes, BooleanSupplier allowed,
            Supplier<JSONObject> projection, Consumer<Event> consumer) {
        if (context == null || !("com.xiaomi.mirror".equals(context.getPackageName())
                || "com.oplus.linker".equals(context.getPackageName()))) throw new SecurityException("invalid_executor_context");
        this.context = context; this.echoes = Objects.requireNonNull(echoes); this.allowed = Objects.requireNonNull(allowed);
        this.projection = Objects.requireNonNull(projection); this.consumer = Objects.requireNonNull(consumer);
    }
    void update() {
        requireMain();
        if (closed || !allowed.getAsBoolean() || projection.get() == null) { release(); return; }
        ClipboardManager manager = context.getSystemService(ClipboardManager.class);
        if (manager == null) { release(); return; }
        if (registeredOn == manager && listener != null) return;
        release(); listener = this::changed; registeredOn = manager;
        manager.addPrimaryClipChangedListener(listener);
        // Never import clipboard history when a peer becomes authorized.
    }
    private void changed() {
        requireMain();
        JSONObject p = projection.get();
        if (closed || !allowed.getAsBoolean() || p == null) return;
        String peer = p.optString("id"), session = p.optString("session_id");
        if (peer.isBlank() || !session.matches("[0-9a-f]{64}")) return;
        final ClipboardRichContent.Html html;
        final long timestamp;
        try {
            ClipData clip = registeredOn.getPrimaryClip();
            if (echoes.suppress(session, clip)) return;
            html = ClipboardRichContent.snapshot(clip); timestamp = clip.getDescription().getTimestamp();
        } catch (RuntimeException unavailable) { return; }
        if (timestamp > 0 && session.equals(previousSession) && timestamp == previousTimestamp
                && html.digest().equals(previousDigest)) return;
        JSONObject current = projection.get();
        if (!allowed.getAsBoolean() || current == null || !peer.equals(current.optString("id"))
                || !session.equals(current.optString("session_id"))) return;
        previousSession = session; previousTimestamp = timestamp; previousDigest = html.digest();
        consumer.accept(new Event(peer, session, html, timestamp));
    }
    private void release() {
        if (listener != null) registeredOn.removePrimaryClipChangedListener(listener);
        listener = null; registeredOn = null; previousSession = null; previousDigest = null; previousTimestamp = 0;
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_requires_main");
    }
    @Override public void close() { requireMain(); closed = true; release(); }
}
