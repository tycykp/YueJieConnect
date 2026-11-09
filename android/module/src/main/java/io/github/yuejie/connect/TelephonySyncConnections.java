package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.VideoProfile;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import static io.github.yuejie.connect.TelephonySyncController.*;

/** Exact live ConnectionService objects in the verified phone process. Never reads a phone/call-log row. */
final class TelephonySyncConnections implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Session session;
    private final Policy policy;
    private final Consumer<Snapshot> events;
    private final BiConsumer<Command, Snapshot> observedControls;
    private final TelephonySyncController controller;
    private final Class<?> nativeConnectionType;
    private final Map<Connection, String> connections = new IdentityHashMap<>();
    private final Set<String> activeAudio = ConcurrentHashMap.newKeySet();
    private ConnectionService service;
    private volatile boolean ending, closed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (service != null) {
                var live = service.getAllConnections();
                for (Connection connection : connections.keySet().toArray(Connection[]::new)) if (!live.contains(connection)) removeOnMain(connection);
                for (Connection connection : live) add(connection);
            }
            for (Connection connection : connections.keySet().toArray(Connection[]::new)) update(connection);
            controller.tick(); main.postDelayed(this, 100);
        }
    };
    TelephonySyncConnections(Session session, Policy policy, Consumer<Snapshot> events, ClassLoader loader) throws ReflectiveOperationException {
        this(session, policy, events, loader, (command, observed) -> { });
    }
    TelephonySyncConnections(Session session, Policy policy, Consumer<Snapshot> events, ClassLoader loader,
                             BiConsumer<Command, Snapshot> observedControls) throws ReflectiveOperationException {
        this.session = session; this.policy = policy; this.events = events;
        this.observedControls = java.util.Objects.requireNonNull(observedControls);
        nativeConnectionType = Class.forName("com.android.services.telephony.TelephonyConnection", false, loader);
        if (!Connection.class.isAssignableFrom(nativeConnectionType)) throw new IllegalStateException("invalid_native_connection");
        controller = new TelephonySyncController(session, policy, SystemClock::elapsedRealtime); main.post(tick);
    }
    void attach(ConnectionService source) { main.post(() -> {
        if (closed || ending) return;
        if (service != null && service != source) { closeOnMain(); return; }
        service = source; for (Connection connection : source.getAllConnections()) add(connection);
    }); }
    void added(ConnectionService source, Connection connection) { main.post(() -> {
        if (!closed && !ending && (service == null || service == source)) { service = source; add(connection); }
    }); }
    void removed(ConnectionService source, Connection connection) { main.post(() -> {
        if (source == service) removeOnMain(connection);
    }); }
    private void removeOnMain(Connection connection) {
        String id = connections.remove(connection); if (id == null) return;
        activeAudio.remove(id); emitted.remove(id); controller.observe(id, State.ENDED); emit(controller.snapshot(id)); controller.remove(id);
    }
    void destroyed(ConnectionService source) { main.post(() -> { if (source == service) closeOnMain(); }); }
    CompletableFuture<Result> execute(Command command, long deadlineElapsedMs) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        main.post(() -> controller.execute(command, deadlineElapsedMs - SystemClock.elapsedRealtime()).whenComplete((value, error) -> {
            if (error == null && value != null && "observed".equals(value.code()) && value.observed() != null
                    && command.request().equals(value.request()) && command.call().equals(value.call())
                    && value.observed().revision() > command.revision() && !closed && !ending)
                try { observedControls.accept(command, value.observed()); } catch (RuntimeException ignored) { }
            if (error == null) result.complete(value); else result.completeExceptionally(error);
        })); return result;
    }
    void cancel(Session source, String request) { main.post(() -> controller.cancel(source, request)); }
    TelephonySyncAudioSession.Policy audioPolicy() {
        return (source, call, role) -> role == TelephonySyncAudioSession.Role.PHONE && !ending && !closed
                && session.equals(source) && activeAudio.contains(call) && policy.observes(session) && policy.audio(session, call);
    }
    private void add(Connection connection) {
        if (closed || ending || connection == null || !nativeConnectionType.isInstance(connection) || connections.containsKey(connection)) return;
        State initial = state(connection.getState()); if (initial == null) return;
        try { connections.put(connection, controller.add(action -> invoke(connection, action), initial)); update(connection); }
        catch (IllegalStateException capacity) { /* Bounded call table; never substitute a different call. */ }
    }
    private void update(Connection connection) {
        String id = connections.get(connection); if (id == null) return;
        State state = state(connection.getState());
        if (state == null || closed || ending) { activeAudio.remove(id); return; }
        if (state == State.ACTIVE && connection.getVideoState() == VideoProfile.STATE_AUDIO_ONLY) activeAudio.add(id); else activeAudio.remove(id);
        Snapshot before = controller.snapshot(id); controller.observe(id, state); Snapshot after = controller.snapshot(id);
        if (!emitted.contains(id) || !java.util.Objects.equals(before, after)) emit(after);
        emitted.add(id);
    }
    private final Set<String> emitted = new java.util.HashSet<>();
    private void emit(Snapshot snapshot) {
        if (snapshot != null && policy.observes(session)) try { events.accept(snapshot); } catch (RuntimeException ignored) { }
    }
    private void invoke(Connection connection, Action action) {
        if (closed || ending || service == null || !connections.containsKey(connection) || !service.getAllConnections().contains(connection))
            throw new IllegalStateException("call_missing");
        int state = connection.getState();
        switch (action) {
            case ANSWER -> {
                if (state != Connection.STATE_RINGING || connection.getVideoState() != VideoProfile.STATE_AUDIO_ONLY) throw new IllegalStateException();
                connection.onAnswer(VideoProfile.STATE_AUDIO_ONLY);
            }
            case REJECT -> { if (state != Connection.STATE_RINGING) throw new IllegalStateException(); connection.onReject(); }
            case END -> {
                if (state != Connection.STATE_ACTIVE && state != Connection.STATE_HOLDING && state != Connection.STATE_DIALING)
                    throw new IllegalStateException();
                connection.onDisconnect();
            }
            case HOLD, UNHOLD -> {
                if ((connection.getConnectionCapabilities() & Connection.CAPABILITY_HOLD) == 0
                        || state != (action == Action.HOLD ? Connection.STATE_ACTIVE : Connection.STATE_HOLDING)) throw new IllegalStateException();
                if (action == Action.HOLD) connection.onHold(); else connection.onUnhold();
            }
        }
    }
    private static State state(int value) {
        return switch (value) {
            case Connection.STATE_NEW, Connection.STATE_INITIALIZING -> State.NEW;
            case Connection.STATE_DIALING -> State.DIALING; case Connection.STATE_RINGING -> State.RINGING;
            case Connection.STATE_ACTIVE -> State.ACTIVE; case Connection.STATE_HOLDING -> State.HOLDING;
            case Connection.STATE_DISCONNECTED -> State.ENDED; default -> null;
        };
    }
    @Override public void close() { ending = true; activeAudio.clear(); main.post(this::closeOnMain); }
    private void closeOnMain() {
        if (closed) return; closed = true; main.removeCallbacks(tick); activeAudio.clear(); emitted.clear(); connections.clear(); service = null; controller.close();
    }
}
