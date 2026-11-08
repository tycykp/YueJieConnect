package io.github.qiangjian.connect;

import java.util.Map;
import java.util.Set;
import static io.github.qiangjian.connect.TelephonySyncController.*;

/** Exact, content-free call/state and local-reference SMS schemas shared by both native adapters. */
final class TelephonySyncWire {
    static final String CALL_STATE = "telephony_call_state", CALL_CONTROL = "telephony_call_control", SMS_STATUS = "telephony_sms_status";
    static final Set<String> OPERATIONS = Set.of(CALL_STATE, CALL_CONTROL, SMS_STATUS);
    static final Set<String> ERRORS = Set.of("session_invalid", "permission_denied", "permission_revoked", "deadline_expired",
            "invalid_payload", "invalid_snapshot", "invalid_result", "request_conflict", "request_capacity", "call_capacity",
            "call_missing", "call_ended", "call_busy", "stale_call", "invalid_call_state", "native_failed", "native_failure",
            "native_mismatch", "outcome_unknown", "cancelled", "state_superseded", "revision_conflict", "initial_state_invalid",
            "relay_busy", "creation_timeout", "native_lost", "native_cleanup_failed", "session_withdrawn", "sms_missing",
            "stale_message", "invalid_message_state", "sms_busy", "remote_error", "native_unavailable");
    private TelephonySyncWire() {}
    static final class Failure extends IllegalArgumentException {
        final String code;
        Failure(String code) { super(code != null && ERRORS.contains(code) ? code : "remote_error"); this.code = getMessage(); }
    }
    static Snapshot state(Map<String, Object> raw) {
        fields(raw, Set.of("call_id", "revision", "state"));
        return new Snapshot(id(raw, "call_id", false), positive(raw, "revision"), state(text(raw, "state")));
    }
    static Map<String, Object> state(Snapshot state) {
        if (state == null || state.call() == null || !state.call().matches("[A-Za-z0-9_-]{1,96}") || state.revision() < 1)
            throw new Failure("invalid_snapshot");
        String value = state.state() == null ? "" : state.state().name().toLowerCase(java.util.Locale.ROOT);
        state(value); return Map.of("call_id", state.call(), "revision", state.revision(), "state", value);
    }
    static Command control(Session session, String request, Map<String, Object> raw) {
        fields(raw, Set.of("call_id", "revision", "action"));
        Action action = switch (text(raw, "action")) {
            case "answer" -> Action.ANSWER; case "reject" -> Action.REJECT; case "end" -> Action.END;
            case "hold" -> Action.HOLD; case "unhold" -> Action.UNHOLD; default -> throw new Failure("invalid_payload");
        };
        return new Command(session, request, id(raw, "call_id", false), positive(raw, "revision"), action);
    }
    static Map<String, Object> control(Command command) {
        Map<String, Object> raw = Map.of("call_id", command.call(), "revision", command.revision(),
                "action", command.action().name().toLowerCase(java.util.Locale.ROOT));
        control(command.session(), command.request(), raw); return raw;
    }
    static Map<String, Object> stateReceipt(Snapshot requested, TelephonySyncRelay.Receipt result) {
        if (result == null || !requested.call().equals(result.call()) || requested.revision() != result.revision())
            throw new Failure("invalid_result");
        String effect = switch (result.code()) {
            case "native_created" -> "created"; case "native_applied" -> "applied"; case "ended" -> "ended";
            default -> throw new Failure(result.code());
        };
        if ("ended".equals(effect) != (requested.state() == State.ENDED)) throw new Failure("invalid_result");
        return Map.of("call_id", result.call(), "revision", result.revision(), "effect", effect);
    }
    static TelephonySyncRelay.Receipt stateReceipt(Snapshot requested, Map<String, Object> raw) {
        fields(raw, Set.of("call_id", "revision", "effect"));
        String code = switch (text(raw, "effect")) {
            case "created" -> "native_created"; case "applied" -> "native_applied"; case "ended" -> "ended";
            default -> throw new Failure("invalid_result");
        };
        TelephonySyncRelay.Receipt result = new TelephonySyncRelay.Receipt(id(raw, "call_id", false), positive(raw, "revision"), code);
        stateReceipt(requested, result); return result;
    }
    static Map<String, Object> controlReceipt(Command command, Result result) {
        if (result == null || !command.request().equals(result.request()) || !command.call().equals(result.call()))
            throw new Failure("invalid_result");
        if (!"observed".equals(result.code())) throw new Failure(result.code());
        Snapshot observed = result.observed();
        if (observed == null || !command.call().equals(observed.call()) || observed.revision() <= command.revision()
                || !reached(command.action(), observed.state())) throw new Failure("invalid_result");
        Map<String, Object> state = state(observed);
        return Map.of("call_id", observed.call(), "revision", observed.revision(), "state", state.get("state"), "observed", true);
    }
    static Result controlReceipt(Command command, Map<String, Object> raw) {
        fields(raw, Set.of("call_id", "revision", "state", "observed"));
        if (!Boolean.TRUE.equals(raw.get("observed"))) throw new Failure("invalid_result");
        Snapshot observed = new Snapshot(id(raw, "call_id", false), positive(raw, "revision"), state(text(raw, "state")));
        Result result = new Result(command.request(), observed.call(), "observed", observed);
        controlReceipt(command, result); return result;
    }
    static TelephonySyncSmsNative.Command sms(Session session, String request, Map<String, Object> raw) {
        fields(raw, Set.of("message_id", "revision", "status"));
        long status = integer(raw, "status");
        if (status != 1 && status != 8 && status != 9) throw new Failure("invalid_payload");
        return new TelephonySyncSmsNative.Command(session, request, id(raw, "message_id", true), positive(raw, "revision"), (int) status);
    }
    static Map<String, Object> smsReceipt(TelephonySyncSmsNative.Command command, TelephonySyncSmsNative.Result result) {
        if (result == null || !command.request().equals(result.request()) || !command.message().equals(result.message()))
            throw new Failure("invalid_result");
        if (!"native_applied".equals(result.code())) throw new Failure(result.code());
        TelephonySyncSmsNative.Snapshot observed = result.observed();
        if (observed == null || !command.message().equals(observed.message()) || observed.revision() <= command.revision()
                || observed.status() != command.status()) throw new Failure("invalid_result");
        return Map.of("message_id", observed.message(), "revision", observed.revision(), "status", observed.status(), "applied", true);
    }
    static boolean reached(Action action, State state) {
        return switch (action) {
            case ANSWER, UNHOLD -> state == State.ACTIVE; case REJECT, END -> state == State.ENDED; case HOLD -> state == State.HOLDING;
        };
    }
    private static State state(String value) {
        return switch (value) {
            case "ringing" -> State.RINGING; case "active" -> State.ACTIVE; case "holding" -> State.HOLDING;
            case "disconnecting" -> State.DISCONNECTING; case "ended" -> State.ENDED; default -> throw new Failure("invalid_payload");
        };
    }
    private static String id(Map<String, Object> raw, String key, boolean sms) {
        String value = text(raw, key);
        if (!value.matches(sms ? "[0-9a-f]{32}" : "[A-Za-z0-9_-]{1,96}")) throw new Failure("invalid_payload");
        return value;
    }
    private static long positive(Map<String, Object> raw, String key) {
        long value = integer(raw, key); if (value < 1) throw new Failure("invalid_payload"); return value;
    }
    private static long integer(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new Failure("invalid_payload");
        return ((Number) value).longValue();
    }
    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key); if (!(value instanceof String text)) throw new Failure("invalid_payload"); return text;
    }
    private static void fields(Map<String, Object> raw, Set<String> expected) {
        if (raw == null || !raw.keySet().equals(expected)) throw new Failure("invalid_payload");
    }
}
