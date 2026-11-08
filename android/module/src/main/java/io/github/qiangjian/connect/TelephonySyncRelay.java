package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.BiConsumer;
import static io.github.qiangjian.connect.TelephonySyncController.*;

/** Remote call -> OEM Connection -> exact remote command. Contains no phone numbers or dial path. */
final class TelephonySyncRelay implements AutoCloseable {
    record Receipt(String call, long revision, String code) {}
    interface NativeUi {
        void incoming(String token) throws Exception;
        void state(String token, Snapshot snapshot) throws Exception;
        void dismiss(String token, String reason);
    }
    interface Sender { CompletableFuture<Result> send(Command command, long remainingMs); }
    private static final long CREATE_TIMEOUT = 10_000;
    private static final long COMMAND_TIMEOUT = 10_000;
    private final Session session;
    private final Policy policy;
    private final NativeUi ui;
    private final Sender sender;
    private final LongSupplier clock;
    private final Executor dispatch;
    private final BiConsumer<Command, Snapshot> observedGestures;
    // Retain terminated IDs until session withdrawal, including creation failures.
    private final Map<String, Entry> calls = new LinkedHashMap<>();
    private boolean closed;
    private static final class Entry {
        final String token = UUID.randomUUID().toString();
        long deadline;
        final CompletableFuture<Receipt> created = new CompletableFuture<>();
        Snapshot snapshot;
        boolean attached, ended;
        String terminal;
        Command pending;
        long commandDeadline;
        Entry(Snapshot snapshot, long deadline) { this.snapshot = snapshot; this.deadline = deadline; }
    }
    TelephonySyncRelay(Session session, Policy policy, NativeUi ui, Sender sender,
                       LongSupplier clock, Executor dispatch) {
        this(session, policy, ui, sender, clock, dispatch, (command, observed) -> { });
    }
    TelephonySyncRelay(Session session, Policy policy, NativeUi ui, Sender sender,
                       LongSupplier clock, Executor dispatch, BiConsumer<Command, Snapshot> observedGestures) {
        this.session = Objects.requireNonNull(session); this.policy = Objects.requireNonNull(policy);
        this.ui = Objects.requireNonNull(ui); this.sender = Objects.requireNonNull(sender);
        this.clock = Objects.requireNonNull(clock); this.dispatch = Objects.requireNonNull(dispatch);
        this.observedGestures = Objects.requireNonNull(observedGestures);
    }
    synchronized CompletableFuture<Receipt> accept(Session source, Snapshot snapshot, long remainingMs) {
        if (closed || !session.equals(source)) return receipt(snapshot, "session_invalid");
        if (!valid(snapshot)) return receipt(snapshot, "invalid_snapshot");
        if (!policy.observes(session)) { withdraw(); return receipt(snapshot, "permission_denied"); }
        if (remainingMs <= 0) return receipt(snapshot, "deadline_expired");
        Entry e = calls.get(snapshot.call());
        if (e != null) {
            if (!e.ended && !e.attached && clock.getAsLong() >= e.deadline) terminate(e, "creation_timeout");
            if (snapshot.revision() < e.snapshot.revision()) return receipt(snapshot, "stale_call");
            if (snapshot.revision() == e.snapshot.revision()) {
                if (!snapshot.equals(e.snapshot)) return receipt(snapshot, "revision_conflict");
                if (e.ended || e.attached) return receipt(snapshot, e.ended ? e.terminal : "native_applied");
                e.deadline = Math.min(e.deadline, clock.getAsLong() + Math.min(CREATE_TIMEOUT, remainingMs));
                return awaitCreation(e, snapshot);
            }
            if (e.ended) return receipt(snapshot, "call_ended");
            e.snapshot = snapshot;
            if (snapshot.state() == State.ENDED) {
                terminate(e, "remote_ended"); return receipt(snapshot, e.terminal);
            }
            if (!e.attached) {
                e.deadline = Math.min(e.deadline, clock.getAsLong() + Math.min(CREATE_TIMEOUT, remainingMs));
                return awaitCreation(e, snapshot);
            }
            return apply(e) ? receipt(snapshot, "native_applied") : receipt(snapshot, "native_failure");
        }
        if (calls.size() >= 256) return receipt(snapshot, "call_capacity");
        // This OEM path creates incoming calls. Never turn an arbitrary ACTIVE/DIALING update into a ring.
        if (snapshot.state() != State.RINGING && snapshot.state() != State.ENDED)
            return receipt(snapshot, "initial_state_invalid");
        if (snapshot.state() == State.RINGING && calls.values().stream().anyMatch(value -> !value.ended))
            return receipt(snapshot, "relay_busy");
        e = new Entry(snapshot, clock.getAsLong() + Math.min(CREATE_TIMEOUT, remainingMs));
        calls.put(snapshot.call(), e);
        if (snapshot.state() == State.ENDED) { terminate(e, "remote_ended"); return receipt(snapshot, e.terminal); }
        try { ui.incoming(e.token); }
        catch (SecurityException denied) { terminate(e, "permission_denied"); }
        catch (Exception failure) { terminate(e, "native_failure"); }
        return awaitCreation(e, snapshot);
    }
    /** Invoked only after the real OEM service created this token's Connection. */
    synchronized boolean canCreate(String token) {
        Entry e = find(token);
        if (closed || e == null || e.ended || e.attached) return false;
        if (!policy.observes(session)) { terminate(e, "permission_revoked"); return false; }
        if (clock.getAsLong() >= e.deadline) { terminate(e, "creation_timeout"); return false; }
        return true;
    }
    synchronized boolean nativeCreated(String token) {
        Entry e = find(token);
        if (!canCreate(token)) return false;
        e.attached = true;
        if (!apply(e)) return false;
        e.created.complete(new Receipt(e.snapshot.call(), e.snapshot.revision(), "native_created"));
        return true;
    }
    /** Native UI gestures only. No first-call, number, device-type, or global Telecom fallback. */
    synchronized boolean operate(String token, Action action) {
        Entry e = find(token);
        if (closed || e == null || e.ended || !e.attached || e.pending != null || action == null) return false;
        if (!policy.observes(session)) { terminate(e, "permission_revoked"); return false; }
        if (!policy.allows(session, action) || !can(action, e.snapshot.state())) return false;
        Command command = new Command(session, "relay_" + UUID.randomUUID().toString().replace("-", ""),
                e.snapshot.call(), e.snapshot.revision(), action);
        e.pending = command; e.commandDeadline = clock.getAsLong() + COMMAND_TIMEOUT;
        try {
            Objects.requireNonNull(sender.send(command, COMMAND_TIMEOUT)).whenComplete((result, failure) ->
                    dispatch.execute(() -> complete(token, command, result, failure)));
        } catch (RuntimeException failure) { e.pending = null; return false; }
        return true;
    }
    private synchronized void complete(String token, Command command, Result result, Throwable failure) {
        Entry e = find(token);
        if (closed || e == null || e.ended || !command.equals(e.pending)) return;
        e.pending = null;
        if (!policy.observes(session)) { terminate(e, "permission_revoked"); return; }
        if (clock.getAsLong() >= e.commandDeadline || !policy.allows(session, command.action())) return;
        if (failure != null || result == null || !command.request().equals(result.request())
                || !command.call().equals(result.call()) || !"observed".equals(result.code())) return;
        Snapshot observed = result.observed();
        if (!valid(observed) || !command.call().equals(observed.call()) || observed.revision() <= command.revision()
                || !reached(command.action(), observed.state())) return;
        accept(session, observed, COMMAND_TIMEOUT).thenAccept(receipt -> {
            if (("native_applied".equals(receipt.code()) || "native_created".equals(receipt.code()))
                    && receipt.call().equals(command.call()) && receipt.revision() == observed.revision())
                try { observedGestures.accept(command, observed); } catch (RuntimeException ignored) { }
        });
    }
    synchronized void nativeLost(String token) { Entry e = find(token); if (e != null) terminate(e, "native_lost"); }
    synchronized void cancelPending(Session source, String call, long revision) {
        Entry e = calls.get(call);
        if (!closed && session.equals(source) && e != null && !e.ended && !e.attached && e.snapshot.revision() == revision)
            terminate(e, "cancelled");
    }
    synchronized boolean audioActive(Session source, String call) {
        Entry e = calls.get(call);
        return !closed && session.equals(source) && e != null && e.attached && !e.ended
                && e.snapshot.state() == State.ACTIVE && policy.observes(session) && policy.audio(session, call);
    }
    synchronized void tick() {
        if (closed) return;
        if (!policy.observes(session)) { withdraw(); return; }
        for (Entry e : calls.values()) if (!e.ended) {
            if (!e.attached && clock.getAsLong() >= e.deadline) terminate(e, "creation_timeout");
            // Do not retry an uncertain gesture automatically. A later deliberate gesture gets a new request.
            if (e.pending != null && clock.getAsLong() >= e.commandDeadline) e.pending = null;
        }
    }
    @Override public synchronized void close() { closed = true; withdraw(); }
    private void withdraw() { for (Entry e : calls.values()) terminate(e, "session_withdrawn"); }
    private void terminate(Entry e, String reason) {
        if (e.ended) return;
        e.ended = true; e.pending = null; e.terminal = "remote_ended".equals(reason) ? "ended" : reason;
        try { ui.dismiss(e.token, reason); } catch (RuntimeException failure) { e.terminal = "native_cleanup_failed"; }
        e.created.complete(new Receipt(e.snapshot.call(), e.snapshot.revision(), e.terminal));
    }
    private boolean apply(Entry e) {
        try { ui.state(e.token, e.snapshot); return true; }
        catch (Exception failure) { terminate(e, "native_failure"); return false; }
    }
    private Entry find(String token) {
        if (token == null) return null;
        for (Entry e : calls.values()) if (token.equals(e.token)) return e;
        return null;
    }
    private static CompletableFuture<Receipt> awaitCreation(Entry e, Snapshot requested) {
        // A newer native state can arrive before ConnectionService's asynchronous creation callback.
        // Keep each RPC's identity and never ACK an old ringing event with a newer ACTIVE/ENDED revision.
        return e.created.thenApply(result -> new Receipt(requested.call(), requested.revision(),
                result.revision() != requested.revision()
                        && ("native_created".equals(result.code()) || "ended".equals(result.code()))
                        ? "state_superseded" : result.code()));
    }
    private static boolean valid(Snapshot s) {
        return s != null && s.call() != null && s.call().matches("[A-Za-z0-9_-]{1,96}") && s.revision() > 0 && s.state() != null;
    }
    private static CompletableFuture<Receipt> receipt(Snapshot s, String code) {
        return CompletableFuture.completedFuture(new Receipt(s == null ? "" : s.call(), s == null ? 0 : s.revision(), code));
    }
    private static boolean can(Action action, State state) {
        return switch (action) {
            case ANSWER, REJECT -> state == State.RINGING;
            case END -> state == State.ACTIVE || state == State.HOLDING;
            case HOLD -> state == State.ACTIVE;
            case UNHOLD -> state == State.HOLDING;
        };
    }
    private static boolean reached(Action action, State state) {
        return switch (action) {
            case ANSWER, UNHOLD -> state == State.ACTIVE;
            case REJECT, END -> state == State.ENDED;
            case HOLD -> state == State.HOLDING;
        };
    }
}
