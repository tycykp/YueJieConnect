package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import static io.github.yuejie.connect.TelephonySyncController.*;
import static io.github.yuejie.connect.TelephonySyncWire.*;

/** Process lifetime hooks, with call objects and request ledgers scoped to the connected peer. */
final class TelephonySyncRuntime implements BridgeClient.VendorExecutor {
    private final BridgeClient client;
    private final ClassLoader loader;
    private final int source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OplusTelephonyConnectionAdapter phone;
    private XiaomiTelephonyRelayAdapter pad;
    private TelephonySyncConnections calls;
    private TelephonySyncEndpoint endpoint;
    private TelephonySyncBridgeTransport transport;
    private Session session;
    private final Map<String, Snapshot> pending = new HashMap<>();
    private final java.util.Set<String> sending = new java.util.HashSet<>();

    TelephonySyncRuntime(XposedModule module, BridgeClient client, ClassLoader loader, int source) throws ReflectiveOperationException {
        this.client = client; this.loader = loader; this.source = source;
        if (source == 2) {
            phone = new OplusTelephonyConnectionAdapter(() -> calls); phone.install(module, loader);
        } else {
            pad = new XiaomiTelephonyRelayAdapter(client.context(), loader); pad.install(module);
        }
        // Register before pairing: otherwise the remote capability advertisement cannot discover us.
        client.registerExecutor(source == 2 ? CALL_CONTROL : CALL_STATE, this);
        client.addProjectionListener(ignored -> refresh());
        main.post(new Runnable() { public void run() {
            refresh(); if (endpoint != null) endpoint.tick(); flush(); main.postDelayed(this, 500);
        }});
        android.util.Log.i("YueJieCall", "runtime_installed source=" + source);
        try {
            Class.forName("io.github.yuejie.connect.TelephonySyncProbe").getDeclaredMethod("install", BridgeClient.class).invoke(null, client);
        } catch (ClassNotFoundException release) { }
        catch (ReflectiveOperationException failure) { android.util.Log.w("YueJieCall", "debug_probe_unavailable"); }
    }
    private boolean current(Session expected) {
        JSONObject p = client.projection();
        return expected != null && p != null && expected.peer().equals(p.optString("id"))
                && expected.id().equals(p.optString("session_id"))
                && client.hasLocalGrant(CALL_STATE) && client.hasLocalGrant(CALL_CONTROL);
    }
    private void refresh() {
        if (current(session)) return;
        clear();
        JSONObject p = client.projection();
        if (p == null || !client.hasLocalGrant(CALL_STATE) || !client.hasLocalGrant(CALL_CONTROL)) return;
        try {
            Session selected = new Session(p.getString("id"), p.getString("session_id"));
            session = selected;
            Policy policy = new Policy() {
                public boolean observes(Session s) { return selected.equals(s) && current(s); }
                public boolean allows(Session s, Action a) { return observes(s); }
            };
            transport = new TelephonySyncBridgeTransport(client, selected);
            if (source == 2) {
                calls = new TelephonySyncConnections(selected, policy, snapshot -> pending.put(snapshot.call(), snapshot), loader);
                TelephonySyncConnections owned = calls;
                endpoint = new TelephonySyncEndpoint(selected, new TelephonySyncEndpoint.Calls() {
                    public java.util.concurrent.CompletableFuture<Result> execute(Command c, long deadline) { return owned.execute(c, deadline); }
                    public void cancel(Session s, String request) { owned.cancel(s, request); }
                }, null, null, () -> current(selected), SystemClock::elapsedRealtime, owned::close);
                phone.refresh();
            } else {
                XiaomiTelephonyRelayAdapter.Binding binding = pad.bind(selected, policy, transport);
                endpoint = new TelephonySyncEndpoint(selected, null, new TelephonySyncEndpoint.States() {
                    public java.util.concurrent.CompletableFuture<TelephonySyncRelay.Receipt> accept(Session s, Snapshot snapshot, long deadline) {
                        return binding.accept(s, snapshot, deadline);
                    }
                    public void cancel(Session s, String call, long revision) { binding.cancelPending(s, call, revision); }
                }, null, () -> current(selected), SystemClock::elapsedRealtime, binding::close);
            }
            android.util.Log.i("YueJieCall", "session_ready source=" + source);
        } catch (Exception failure) { clear(); android.util.Log.e("YueJieCall", "session_install_failed", failure); }
    }
    private void flush() {
        if (!current(session) || transport == null) return;
        Session selected = session;
        for (Snapshot snapshot : pending.values().toArray(Snapshot[]::new)) {
            if (!sending.add(snapshot.call())) continue;
            transport.state(snapshot).whenComplete((receipt, failure) -> main.post(() -> {
                if (!selected.equals(session)) return;
                sending.remove(snapshot.call());
                if (failure == null && receipt != null && java.util.Set.of("native_created", "native_applied", "ended").contains(receipt.code())) pending.remove(snapshot.call(), snapshot);
                Throwable cause = failure;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof Failure f && java.util.Set.of("initial_state_invalid", "call_ended", "call_capacity", "native_failure").contains(f.code))
                    pending.remove(snapshot.call(), snapshot);
            }));
        }
    }
    @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        refresh();
        TelephonySyncEndpoint target = endpoint;
        try {
            if (target == null || !current(session) || !session.id().equals(task.getString("session_id"))
                    || !session.peer().equals(task.getString("projection_id"))) throw new Failure("session_invalid");
            Object deadline = task.get("deadline_elapsed_ms");
            if (!(deadline instanceof Long) && !(deadline instanceof Integer)) throw new Failure("invalid_payload");
            target.execute(session, task.getString("operation"), task.getString("request_id"),
                    TelephonySyncBridgeTransport.map(task.getJSONObject("payload")), ((Number) deadline).longValue())
                    .whenComplete((value, failure) -> {
                        if (failure == null) completion.success(new JSONObject(value));
                        else { while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
                            completion.fail(failure instanceof Failure f ? f.code : "outcome_unknown"); }
                    });
        } catch (Exception failure) { completion.fail(failure instanceof Failure f ? f.code : "invalid_payload"); }
    }
    @Override public void cancel(String id, String request) { if (endpoint != null && session.id().equals(id)) endpoint.cancel(session, request); }
    @Override public void sessionChanged() { clear(); }
    private void clear() {
        if (endpoint != null) endpoint.close();
        endpoint = null; calls = null; session = null; transport = null; pending.clear(); sending.clear();
    }
}
