package io.github.yuejie.connect;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.security.SecureRandom;
import org.json.JSONObject;

/** One-use ticket delivered over the authenticated LAN before replacing it with Bluetooth.
 * Pairing still verifies the same Noise identity and restores only bilateral saved grants. */
final class NetworkCardHandover implements AutoCloseable {
    interface Host { void switchTransport(String session) throws Exception; }
    private final Context context;
    private final Host host;
    private NetworkBluetoothSession carrier;
    private String parentSession, peer;
    private long deadline;
    private boolean consumer, requested, wasActive, switched;
    NetworkCardHandover(Context context, Host host) { this.context = context; this.host = host; }

    synchronized JSONObject prepare(String session, String id) throws Exception {
        if (carrier != null) throw new IllegalStateException("bluetooth_busy");
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String ticket = java.util.HexFormat.of().formatHex(secret);
        begin(session, id, ticket, false);
        try {
            carrier.start(new JSONObject().put("mode", "listen"));
            carrier.awaitListening();
            return new JSONObject().put("ticket", ticket);
        } catch (Exception failure) { close(); throw failure; }
    }
    synchronized void connect(String session, String id, String ticket, String address) throws Exception {
        if (carrier != null || !ticket.matches("[0-9a-f]{64}")) throw new IllegalStateException("invalid_handover");
        begin(session, id, ticket, true);
        try { carrier.start(new JSONObject().put("mode", "connect").put("address", address)); }
        catch (Exception failure) { close(); throw failure; }
    }
    private void begin(String session, String id, String ticket, boolean initiator) {
        parentSession = session; peer = id; consumer = initiator;
        deadline = SystemClock.elapsedRealtime() + 60_000;
        carrier = new NetworkBluetoothSession(context, fd -> {
            // Use the carrier's Unix endpoint: the Bluetooth socket is already owned by its pump.
            try (var input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()));
                 var output = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
                Os.setsockoptTimeval(fd.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(5000));
                NetworkHandoverHandshake.exchange(input, output, ticket, initiator, () -> {
                    synchronized (NetworkCardHandover.this) {
                        if (carrier == null || SystemClock.elapsedRealtime() >= deadline)
                            throw new IllegalStateException("handover_expired");
                    }
                    host.switchTransport(session);
                    android.util.Log.i("YueJieNetwork","bluetooth_parent_switched initiator="+initiator);
                    synchronized (NetworkCardHandover.this) { switched = true; }
                });
            }
        });
    }
    synchronized boolean active() { return carrier != null; }
    synchronized boolean switched() { return switched; }
    /** Returns start only after the new authenticated identity, grant and provider readiness agree. */
    synchronized String action(JSONObject state, JSONObject network, long observedAt) {
        if (carrier == null) return "";
        if (wasActive && ("disconnected".equals(state.optString("phase")) || "stopped".equals(state.optString("phase")))) return "failed";
        if (!wasActive && SystemClock.elapsedRealtime() >= deadline) return "failed";
        JSONObject p = state.optJSONObject("projection");
        if (!"active".equals(state.optString("phase")) || !"bluetooth".equals(state.optString("control_transport"))
                || parentSession.equals(state.optString("session")) || p == null || !peer.equals(p.optString("id"))) return "";
        if (!consumer) { wasActive = true; return ""; }
        if (network == null || !state.optString("session").equals(network.optString("session"))
                || SystemClock.elapsedRealtime() - observedAt >= 6500) return "";
        if ("ACTIVE".equals(network.optString("phase"))) wasActive = true;
        // Keep the authenticated Bluetooth connection when the phone is on mobile
        // data. Closing it cannot restore a LAN connection that no longer exists.
        if (wasActive && "STOPPED".equals(network.optString("phase")) && network.optBoolean("wifi_upstream")) return "restore";
        if (!requested && network.optBoolean("available")) { requested = true; return "start"; }
        return "";
    }
    @Override public synchronized void close() {
        if (carrier != null) carrier.close();
        carrier = null; requested = false; wasActive = false; switched = false;
    }
}
