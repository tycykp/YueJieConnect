package io.github.yuejie.connect;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** One-use carrier preface. The enclosing authenticated parent supplies and expires the ticket. */
final class NetworkHandoverHandshake {
    interface Switch { void run() throws Exception; }
    static void exchange(InputStream input, OutputStream output, String ticket, boolean initiator,
            Switch switchParent) throws Exception {
        if (ticket == null || !ticket.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_ticket");
        byte[] token = ticket.getBytes(StandardCharsets.US_ASCII);
        if (initiator) {
            output.write(token); output.flush();
            if (input.read() != 1) throw new SecurityException("handover_rejected");
        } else if (!MessageDigest.isEqual(token, input.readNBytes(64))) {
            throw new SecurityException("handover_rejected");
        }
        switchParent.run();
        if (!initiator) { output.write(1); output.flush(); }
    }
    private NetworkHandoverHandshake() { }
}
