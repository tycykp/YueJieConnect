package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Real native-process platform URI-copy events. Grants and Android objects stay local. */
final class ClipboardUriSource implements AutoCloseable {
    record Event(String peer, String session, String ownerToken, ClipData clip, long timestamp, long generation) {}
    interface Sink { void copied(Event event); void invalidated(); }
    private final Context context;
    private final ClipboardEchoGuard echoes;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final Sink sink;
    private ClipboardManager manager;
    private ClipboardManager.OnPrimaryClipChangedListener listener;
    private volatile long generation;
    private volatile Event active;
    private volatile boolean closed;

    ClipboardUriSource(Context context, ClipboardEchoGuard echoes, BooleanSupplier allowed, Supplier<JSONObject> projection, Sink sink) {
        if (context == null || !("com.xiaomi.mirror".equals(context.getPackageName()) || "com.oplus.linker".equals(context.getPackageName())))
            throw new SecurityException("invalid_executor_context");
        this.context = context; this.echoes = Objects.requireNonNull(echoes); this.allowed = Objects.requireNonNull(allowed);
        this.projection = Objects.requireNonNull(projection); this.sink = Objects.requireNonNull(sink);
    }
    void update() {
        main();
        if (closed || !allowed.getAsBoolean() || projection.get() == null) { release(); return; }
        ClipboardManager next = context.getSystemService(ClipboardManager.class);
        if (next == null) { release(); return; }
        if (manager == next && listener != null) {
            Event event = active; if (event != null && !current(event)) invalidate();
            return;
        }
        release(); manager = next; listener = this::changed; manager.addPrimaryClipChangedListener(listener);
        // An authorization or reconnect never reads/sends historical clipboard content.
    }
    private void changed() {
        main();
        JSONObject p = projection.get();
        if (closed || !allowed.getAsBoolean() || p == null || !p.optString("session_id").matches("[0-9a-f]{64}")) return;
        try {
            ClipData clip = manager.getPrimaryClip();
            if (echoes.suppress(p.optString("session_id"), clip)) return;
            if (clip == null || clip.getItemCount() != 1) { invalidate(); return; }
            ClipData.Item item = clip.getItemAt(0); Uri uri = item.getUri();
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || item.getText() != null || item.getHtmlText() != null || item.getIntent() != null) { invalidate(); return; }
            Event previous = active; long stamp = clip.getDescription().getTimestamp();
            if (previous != null && current(previous) && stamp > 0 && previous.timestamp() == stamp
                    && uri.equals(previous.clip().getItemAt(0).getUri())) return;
            invalidate();
            Event event = new Event(p.optString("id"), p.optString("session_id"), UUID.randomUUID().toString().replace("-", ""),
                    new ClipData(clip), stamp, generation);
            if (event.peer().isBlank()) return;
            active = event;
            if (current(event)) sink.copied(event); else invalidate();
        } catch (RuntimeException denied) { invalidate(); }
    }
    boolean current(Event event) {
        try {
            JSONObject p = projection.get();
            return !closed && active == event && event.generation() == generation && allowed.getAsBoolean() && p != null
                    && event.peer().equals(p.optString("id")) && event.session().equals(p.optString("session_id"));
        } catch (RuntimeException unavailable) { return false; }
    }
    private void invalidate() { generation++; active = null; sink.invalidated(); }
    private void release() {
        if (listener != null) manager.removePrimaryClipChangedListener(listener);
        listener = null; manager = null;
        if (active != null) invalidate();
    }
    private static void main() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_requires_main"); }
    @Override public void close() { main(); closed = true; release(); }
}
