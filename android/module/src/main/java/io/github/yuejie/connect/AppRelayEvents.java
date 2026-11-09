package io.github.yuejie.connect;

/** Redacted lifecycle evidence. Never pass a URL, package label, document name, token or payload. */
final class AppRelayEvents {
    enum Event { NATIVE_CLICK, SYNC_SUBMITTED, SOURCE_REQUESTED, SOURCE_PAYLOAD, TARGET_FOREGROUND, SOURCE_NATIVE_RESULT, NATIVE_CLICK_RESULT }
    enum Outcome { PENDING, SUCCEEDED, FAILED, CANCELLED }
    static void emit(Event event, String id, Outcome outcome) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}")) return;
        android.util.Log.i("YueJieRelay", "relay_event=" + event.name() + " id=" + id + " outcome=" + outcome.name());
    }
    static void linked(String sync, String data) {
        if (sync != null && data != null && sync.matches("[A-Za-z0-9_-]{1,96}") && data.matches("[A-Za-z0-9_-]{1,96}"))
            android.util.Log.i("YueJieRelay", "relay_link=" + sync + " data=" + data);
    }
    static Outcome outcome(AppRelayTasks.Result result) {
        return result != null && result.state() == AppRelayTasks.State.COMPLETED ? Outcome.SUCCEEDED
                : result != null && result.state() == AppRelayTasks.State.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED;
    }
    private AppRelayEvents() {}
}
