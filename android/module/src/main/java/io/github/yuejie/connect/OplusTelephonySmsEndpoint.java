package io.github.yuejie.connect;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.List;
import java.util.function.BiPredicate;

/** Legacy nearcomm provider request. The captured Mms APK does not implement this method; do not register it.
 * Android phone/pad Mms uses communicationshare.operation instead (see calls-sms verification ledger).
 */
final class OplusTelephonySmsEndpoint implements AutoCloseable {
    enum State { UNREAD, READ, DELETED }
    private final Context context;
    private final BiPredicate<TelephonySyncController.Session, State> authorized;
    private final TelephonySyncSmsState registry;
    OplusTelephonySmsEndpoint(Context context, TelephonySyncController.Session session,
                              BiPredicate<TelephonySyncController.Session, State> authorized) {
        this.context = context; this.authorized = authorized; registry = new TelephonySyncSmsState(session);
    }
    String observedNativeMessage(int nativeSession, String nativeReference) { return registry.observed(nativeSession, nativeReference); }
    void nativeDisconnected(int nativeSession) { registry.nativeDisconnected(nativeSession); }
    String update(TelephonySyncController.Session session, List<String> ids, State state) {
        if (state == null) return "invalid_payload";
        if (!authorized.test(session, state)) return "permission_denied";
        final List<TelephonySyncSmsState.NativeTarget> messages;
        try { messages = registry.resolve(session, ids); }
        catch (IllegalArgumentException invalid) { return "invalid_payload"; }
        catch (IllegalStateException missing) { return "sms_target_invalid"; }
        int nativeSession = messages.get(0).session();
        Bundle extras = new Bundle();
        extras.putString("nearcomm.sms.status", Integer.toString(state.ordinal()));
        extras.putInt("nearcomm.sms.session_id", nativeSession);
        extras.putInt("nearcomm.sms.msg_ref_count", messages.size());
        for (int i = 0; i < messages.size(); i++) extras.putString("nearcomm.sms.msg_ref" + (messages.size() == 1 ? "" : i + 1), messages.get(i).reference());
        try {
            Bundle result = context.getContentResolver().call(Uri.parse("content://message-external/"), "update_sms_status", null, extras);
            return result != null && result.getBoolean("result", false) ? "applied" : "native_rejected";
        } catch (SecurityException denied) { return "permission_denied"; }
        catch (RuntimeException failure) { return "provider_unavailable"; }
    }
    @Override public void close() { registry.close(); }
}
