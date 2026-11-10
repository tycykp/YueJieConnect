package io.github.yuejie.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkHandoverHandshakeTest {
    private static final String TICKET = "ab".repeat(32);
    @Test public void carrierHandshakeSwitchesBothEndsOnlyAfterTicketAndAcknowledgement() throws Exception {
        AtomicInteger order = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var responder = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    NetworkHandoverHandshake.exchange(socket.getInputStream(), socket.getOutputStream(), TICKET, false,
                        () -> assertEquals(0, order.getAndIncrement()));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (Socket socket = new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(2000);
                NetworkHandoverHandshake.exchange(socket.getInputStream(), socket.getOutputStream(), TICKET, true,
                    () -> assertEquals(1, order.getAndIncrement()));
            }
            responder.get(3, TimeUnit.SECONDS);
        }
        assertEquals(2, order.get());
    }
    @Test public void wrongAndTruncatedTicketsNeverSwitchOrAcknowledge() {
        for (String value : new String[] {"cd".repeat(32), "ab".repeat(31), ""}) {
            var output = new ByteArrayOutputStream();
            assertThrows(SecurityException.class, () -> NetworkHandoverHandshake.exchange(
                new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)), output, TICKET, false,
                () -> fail("Unauthenticated carrier switched the parent")));
            assertEquals(0, output.size());
        }
    }
    @Test public void rejectedOrExpiredParentCannotSwitchConsumer() {
        assertThrows(SecurityException.class, () -> NetworkHandoverHandshake.exchange(new ByteArrayInputStream(new byte[]{0}),
            new ByteArrayOutputStream(), TICKET, true, () -> fail("Rejected carrier switched the parent")));
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalStateException.class, () -> NetworkHandoverHandshake.exchange(
            new ByteArrayInputStream(TICKET.getBytes(java.nio.charset.StandardCharsets.US_ASCII)), output, TICKET, false,
            () -> { throw new IllegalStateException("stale_session"); }));
        assertEquals(0, output.size());
    }
}
