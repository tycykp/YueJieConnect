package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.VideoProfile;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Runs inside the OEM InCallService; never requests a new dialer role or enumerates call logs. */
final class TelephonySyncCalls implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TelephonySyncController controller;
    private final Consumer<TelephonySyncController.Snapshot> events;
    private final TelephonySyncController.Session session;
    private final TelephonySyncController.Policy policy;
    private final Map<Call, Binding> calls = new IdentityHashMap<>();
    private final java.util.Set<String> activeAudio = ConcurrentHashMap.newKeySet();
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!closed) { controller.tick(); main.postDelayed(this, 500); } }
    };
    private InCallService service;
    private volatile boolean closed;
    private volatile boolean ending;
    private record Binding(String id, Call.Callback callback) {}
    TelephonySyncCalls(TelephonySyncController.Session session, TelephonySyncController.Policy policy,
                       Consumer<TelephonySyncController.Snapshot> events) {
        controller = new TelephonySyncController(session, policy, SystemClock::elapsedRealtime);
        this.session = session; this.policy = policy;
        this.events = events;
        main.post(tick);
    }
    void attach(InCallService source) {
        main.post(() -> {
            if (closed) return;
            if (service != null && service != source) { closeOnMain(); return; }
            service = source;
            for (Call call : source.getCalls()) addOnMain(call);
        });
    }
    void added(InCallService source, Call call) {
        main.post(() -> { if (!closed && (service == null || service == source)) { service = source; addOnMain(call); } });
    }
    void removed(InCallService source, Call call) {
        main.post(() -> {
            if (source != service) return;
            Binding b = calls.remove(call);
            if (b != null) {
                activeAudio.remove(b.id());
                call.unregisterCallback(b.callback());
                controller.observe(b.id(), TelephonySyncController.State.ENDED);
                emit(controller.snapshot(b.id())); controller.remove(b.id());
            }
        });
    }
    void destroyed(InCallService source) { main.post(() -> { if (source == service) closeOnMain(); }); }
    CompletableFuture<TelephonySyncController.Result> execute(TelephonySyncController.Command command) {
        return execute(command, SystemClock.elapsedRealtime() + 10_000);
    }
    CompletableFuture<TelephonySyncController.Result> execute(TelephonySyncController.Command command, long deadlineElapsedMs) {
        CompletableFuture<TelephonySyncController.Result> result = new CompletableFuture<>();
        main.post(() -> controller.execute(command, deadlineElapsedMs - SystemClock.elapsedRealtime()).whenComplete((value, failure) -> {
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        }));
        return result;
    }
    void cancel(TelephonySyncController.Session source, String request) { main.post(() -> controller.cancel(source, request)); }
    TelephonySyncAudioSession.Policy audioPolicy() {
        return (source, call, role) -> role == TelephonySyncAudioSession.Role.PHONE && !closed && !ending
                && session.equals(source) && activeAudio.contains(call) && policy.observes(session) && policy.audio(session, call);
    }
    private void addOnMain(Call call) {
        if (call == null || closed || calls.containsKey(call)) return;
        // A native relay presentation must not be exported back as a new local call.
        Call.Details details = call.getDetails();
        if (details == null || relayed(details.getExtras()) || relayed(details.getIntentExtras())
                || (details.getAccountHandle() != null && (XiaomiTelephonyRelayAdapter.PREFIX + "RelayConnectionService")
                .equals(details.getAccountHandle().getComponentName().getClassName()))) return;
        TelephonySyncController.State initial = state(call.getState());
        if (initial == null) return;
        final String id;
        try { id = controller.add(action -> invoke(call, action), initial); }
        catch (IllegalStateException capacity) { return; }
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call c, int s) { update(c); }
            @Override public void onDetailsChanged(Call c, Call.Details details) { update(c); }
            @Override public void onCallDestroyed(Call c) { removed(service, c); }
        };
        calls.put(call, new Binding(id, callback));
        call.registerCallback(callback, main);
        update(call);
    }
    private static boolean relayed(Bundle extras) {
        return extras != null && (extras.containsKey(XiaomiTelephonyRelayAdapter.TOKEN)
                || extras.getBoolean("telecomm.EXTRA_RELAY_CALL", false));
    }
    private void update(Call call) {
        Binding b = calls.get(call);
        TelephonySyncController.State state = state(call.getState());
        if (b == null || closed) return;
        if (state == null) { activeAudio.remove(b.id()); return; }
        Call.Details details = call.getDetails();
        if (state == TelephonySyncController.State.ACTIVE && details != null
                && details.getVideoState() == VideoProfile.STATE_AUDIO_ONLY) activeAudio.add(b.id());
        else activeAudio.remove(b.id());
        controller.observe(b.id(), state); emit(controller.snapshot(b.id()));
    }
    private void emit(TelephonySyncController.Snapshot state) {
        if (state != null && policy.observes(session)) try { events.accept(state); } catch (RuntimeException ignored) { /* Isolate host callback. */ }
    }
    private void invoke(Call call, TelephonySyncController.Action action) {
        if (closed || service == null || !calls.containsKey(call) || !service.getCalls().contains(call))
            throw new IllegalStateException("call_missing");
        // Re-read native state in the same main-loop dispatch as the operation.
        int s = call.getState();
        switch (action) {
            case ANSWER -> {
                if (s != Call.STATE_RINGING || call.getDetails().getVideoState() != VideoProfile.STATE_AUDIO_ONLY)
                    throw new IllegalStateException("call_state_changed_or_video");
                call.answer(VideoProfile.STATE_AUDIO_ONLY);
            }
            case REJECT -> { if (s != Call.STATE_RINGING) throw new IllegalStateException("call_state_changed"); call.reject(false, null); }
            case END -> {
                if (s != Call.STATE_ACTIVE && s != Call.STATE_HOLDING && s != Call.STATE_DIALING && s != Call.STATE_CONNECTING)
                    throw new IllegalStateException("call_state_changed");
                call.disconnect();
            }
            case HOLD, UNHOLD -> {
                if (!call.getDetails().can(Call.Details.CAPABILITY_HOLD)
                        || s != (action == TelephonySyncController.Action.HOLD ? Call.STATE_ACTIVE : Call.STATE_HOLDING))
                    throw new IllegalStateException("hold_unavailable");
                if (action == TelephonySyncController.Action.HOLD) call.hold(); else call.unhold();
            }
        }
    }
    static TelephonySyncController.State state(int state) {
        return switch (state) {
            case Call.STATE_NEW -> TelephonySyncController.State.NEW;
            case Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> TelephonySyncController.State.CONNECTING;
            case Call.STATE_DIALING -> TelephonySyncController.State.DIALING;
            case Call.STATE_RINGING -> TelephonySyncController.State.RINGING;
            case Call.STATE_ACTIVE -> TelephonySyncController.State.ACTIVE;
            case Call.STATE_HOLDING -> TelephonySyncController.State.HOLDING;
            case Call.STATE_DISCONNECTING -> TelephonySyncController.State.DISCONNECTING;
            case Call.STATE_DISCONNECTED -> TelephonySyncController.State.ENDED;
            default -> null; // An unfamiliar native state grants no controls.
        };
    }
    @Override public void close() { ending = true; activeAudio.clear(); main.post(this::closeOnMain); }
    private void closeOnMain() {
        if (closed) return;
        closed = true; main.removeCallbacks(tick);
        for (Map.Entry<Call, Binding> entry : calls.entrySet()) entry.getKey().unregisterCallback(entry.getValue().callback());
        activeAudio.clear(); calls.clear(); service = null; controller.close();
    }
}
