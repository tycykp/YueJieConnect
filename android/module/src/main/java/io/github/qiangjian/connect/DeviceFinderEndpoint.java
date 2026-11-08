package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Authenticated vendor-task sink. Construct, bind and close on the main looper. */
final class DeviceFinderEndpoint implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeviceFinderController controller;
    private final AutoCloseable backend;
    private String peerName = "已配对设备";
    private final AtomicInteger pending = new AtomicInteger();
    private boolean closed;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try { controller.tick(); } catch (Exception failure) { cancel(); }
            main.postDelayed(this, 250);
        }
    };

    DeviceFinderEndpoint(Context context, ClassLoader loader, int source) throws Exception {
        this(context, loader, source, null);
    }

    DeviceFinderEndpoint(Context context, ClassLoader loader, int source, io.github.libxposed.api.XposedModule module) throws Exception {
        requireMain();
        DeviceFinderController.Backend selected;
        if (source == 1) selected = new XiaomiFinderEndpoint(context, loader, this::cancel);
        else if (source == 2 && "com.coloros.findmyphone".equals(context.getPackageName()))
            selected = new OplusFinderNativeEndpoint(context, loader, module, this::cancel);
        else throw new IllegalArgumentException("invalid_finder_source");
        backend = (AutoCloseable) selected;
        controller = new DeviceFinderController(selected, SystemClock::elapsedRealtime);
        main.post(monitor);
    }

    void bind(String sessionId, String verifiedPeerName) throws Exception {
        requireMain();
        try { controller.bind(sessionId); }
        catch (DeviceFinderController.Failure failure) { if (!"cleanup_pending".equals(failure.code)) throw failure; }
        peerName = verifiedPeerName == null ? "已配对设备" : verifiedPeerName.replaceAll("[\\p{Cntrl}]", "");
        if (peerName.length() > 80) peerName = peerName.substring(0, 80);
    }

    void execute(String sessionId, String requestId, long deadlineElapsedMs, JSONObject payload, Consumer<JSONObject> completion) {
        String copied = payload == null ? "{}" : payload.toString();
        if (pending.incrementAndGet() > 32) {
            pending.decrementAndGet();
            JSONObject busy = new JSONObject();
            try { busy.put("status", "error").put("code", "finder_busy"); } catch (org.json.JSONException ignored) { }
            completion.accept(busy);
            return;
        }
        main.post(new Runnable() { @Override public void run() {
            JSONObject result = new JSONObject();
            try {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) throw new DeviceFinderController.Failure("request_timeout");
                JSONObject input = new JSONObject(copied);
                Object rawAction = input.opt("action");
                if (!(rawAction instanceof String action)) throw new DeviceFinderController.Failure("invalid_action");
                var keys = input.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"action".equals(key) && !("duration_ms".equals(key) && "start".equals(action)))
                        throw new DeviceFinderController.Failure("invalid_finder_payload");
                }
                Object durationValue = input.opt("duration_ms");
                if (durationValue != null && !(durationValue instanceof Integer) && !(durationValue instanceof Long))
                    throw new DeviceFinderController.Failure("invalid_duration");
                long duration = "start".equals(action) ? input.optLong("duration_ms", 10_000) : 0;
                DeviceFinderController.Result state = controller.execute(sessionId, requestId, action, duration, peerName);
                result.put("status", "ok").put("ringing", state.ringing()).put("remaining_ms", state.remainingMs())
                        .put("reason", state.reason()).put("replayed", state.replayed());
            } catch (Exception failure) {
                if (failure instanceof DeviceFinderController.Failure f && "cleanup_pending".equals(f.code)
                        && !closed && SystemClock.elapsedRealtime() < deadlineElapsedMs) {
                    main.postDelayed(this, 25);
                    return;
                }
                try { result.put("status", "error").put("code", DeviceFinderController.terminalFailureCode(failure, closed)); }
                catch (org.json.JSONException ignored) { }
            }
            pending.decrementAndGet();
            completion.accept(result);
        }});
    }

    void cancel() {
        main.post(() -> { try { controller.cancel(); } catch (Exception ignored) { /* monitor retries cleanup */ } });
    }

    void cancel(String sessionId, String requestId) {
        main.post(() -> { try { controller.cancel(sessionId, requestId); } catch (Exception ignored) { } });
    }

    @Override public void close() throws Exception {
        requireMain();
        closed = true;
        main.removeCallbacks(monitor);
        try { controller.close(); }
        catch (DeviceFinderController.Failure failure) { if (!"cleanup_pending".equals(failure.code)) throw failure; }
        finally { backend.close(); }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("finder_main_thread_required");
    }
}
