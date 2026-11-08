package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.Test;

public class NotificationSyncBridgeTransportTest {
    private static final String TRANSFER = NotificationSyncFragmentsTest.TRANSFER, SESSION = NotificationSyncFragmentsTest.SESSION;
    private static final class Channel implements NotificationSyncBridgeTransport.Channel, AutoCloseable {
        JSONObject projection;
        final List<JSONObject> sent = new ArrayList<>();
        final List<String> identities = new ArrayList<>(), cancelled = new ArrayList<>();
        int executed;
        boolean hold, wrongSession, wrongAck;
        Consumer<JSONObject> callback;
        JSONObject heldResponse;
        final NotificationSyncIngress ingress = new NotificationSyncIngress(new NotificationSyncIngress.Executor() {
            @Override public java.util.concurrent.CompletableFuture<byte[]> execute(String p, String s, String request, String operation, byte[] bytes, long deadline) {
                executed++;
                var frame = NotificationSyncCodec.decode(bytes);
                return java.util.concurrent.CompletableFuture.completedFuture(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(frame, frame.kind() == 1 ? "posted" : "removed")));
            }
            @Override public void cancel(String session, String request) { }
        }, (p, s, o) -> true, () -> 1000);
        Channel() throws Exception {
            projection = new JSONObject().put("id", "peer").put("session_id", SESSION).put("operations", new org.json.JSONArray().put("notification_post").put("notification_remove"));
            ingress.onProjection("peer", SESSION);
        }
        @Override public JSONObject projection() { return projection; }
        @Override public void cancel(String session, String request) { cancelled.add(session + "/" + request); }
        @Override public void request(String peer, String session, String operation, String request, JSONObject payload, Consumer<JSONObject> completion) {
            assertEquals("peer", peer); assertEquals(SESSION, session); assertTrue(request.matches("[A-Za-z0-9_:-]{1,128}"));
            sent.add(payload); identities.add(request);
            ingress.accept(peer, session, operation, payload, 7000).whenComplete((result, error) -> {
                try {
                    JSONObject response = new JSONObject().put("status", error == null ? "ok" : "error")
                            .put("session_id", wrongSession ? "b".repeat(64) : session).put("request_id", request);
                    if (error == null) {
                        if (wrongAck && !payload.has("abort")) result.put("state", "buffered");
                        response.put("result", new JSONObject().put("operation", operation).put("data", result));
                    }
                    if (hold && !payload.has("abort")) { callback = completion; heldResponse = response; }
                    else completion.accept(response);
                } catch (Exception invalid) { throw new AssertionError(invalid); }
            });
        }
        @Override public void close() { ingress.close(); }
    }
    @Test public void fragmentedTransportReturnsOnlyVerifiedTerminalReceipt() throws Exception {
        try (var channel = new Channel()) {
            var transport = new NotificationSyncBridgeTransport(channel, () -> true);
            byte[] bytes = NotificationSyncIngressTest.large();
            byte[] receipt = transport.send("peer", SESSION, TRANSFER, "notification_post", bytes).join();
            NotificationSyncCodec.checkReceipt(NotificationSyncCodec.decode(bytes), receipt);
            assertEquals(4, channel.sent.size()); assertEquals(1, channel.executed);
            assertEquals(TRANSFER + ":part3", channel.identities.get(3));
        }
    }
    @Test public void cancellationFencesContinuationAndSendsTypedAbort() throws Exception {
        try (var channel = new Channel()) {
            channel.hold = true;
            var transport = new NotificationSyncBridgeTransport(channel, () -> true);
            var future = transport.send("peer", SESSION, TRANSFER, "notification_post", NotificationSyncIngressTest.large());
            assertFalse(future.isDone()); assertEquals(0, channel.executed);
            transport.cancel("other", SESSION, TRANSFER); assertFalse(future.isDone());
            transport.cancel("peer", SESSION, TRANSFER); assertTrue(future.isCompletedExceptionally());
            assertEquals(TRANSFER + ":abort", channel.identities.get(1)); assertTrue(channel.sent.get(1).getBoolean("abort"));
            assertEquals(List.of(SESSION + "/" + TRANSFER + ":part0"), channel.cancelled);
            channel.callback.accept(channel.heldResponse); assertEquals(2, channel.sent.size()); assertEquals(0, channel.executed);
        }
    }
    @Test public void reconnectedSessionAndWrongEnvelopeStopBeforeSendingNextPart() throws Exception {
        try (var channel = new Channel()) {
            channel.hold = true;
            var transport = new NotificationSyncBridgeTransport(channel, () -> true);
            var future = transport.send("peer", SESSION, TRANSFER, "notification_post", NotificationSyncIngressTest.large());
            channel.projection.put("session_id", "b".repeat(64)); channel.callback.accept(channel.heldResponse);
            assertTrue(future.isCompletedExceptionally()); assertEquals(1, channel.sent.size()); assertEquals(0, channel.executed);
        }
        try (var channel = new Channel()) {
            channel.wrongSession = true;
            var future = new NotificationSyncBridgeTransport(channel, () -> true).send("peer", SESSION, TRANSFER, "notification_post", NotificationSyncIngressTest.large());
            assertThrows(CompletionException.class, future::join); assertEquals(0, channel.executed);
            assertTrue(channel.sent.get(1).has("abort"));
        }
    }
    @Test public void finalBufferedAckDoesNotClaimSuccessEvenWhenRemoteMayHaveExecuted() throws Exception {
        try (var channel = new Channel()) {
            channel.wrongAck = true;
            var future = new NotificationSyncBridgeTransport(channel, () -> true).send("peer", SESSION, TRANSFER, "notification_remove", NotificationSyncCodec.encode(NotificationSyncCodec.removed("a", 1)));
            assertThrows(CompletionException.class, future::join); assertEquals(1, channel.executed);
            assertTrue(channel.sent.get(1).has("abort"));
        }
    }
    @Test public void canonicalBase64AndExactPayloadFieldsAreRequired() throws Exception {
        byte[] bytes = NotificationSyncCodec.encode(NotificationSyncCodec.removed("a", 1));
        assertArrayEquals(bytes, NotificationSyncBridgeTransport.bytes(NotificationSyncBridgeTransport.payload(bytes)));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.bytes(new JSONObject().put("data", "YQ")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.bytes(new JSONObject().put("data", "YR==")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.bytes(new JSONObject().put("data", "YQ==").put("extra", true)));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.bytes(new JSONObject().put("data", 10)));
    }
    @Test public void receiptMustMatchWireSessionRequestOperationAndNotificationRevision() throws Exception {
        var request = NotificationSyncCodec.removed("synthetic", 3);
        var response = new JSONObject().put("status", "ok").put("session_id", "session-a").put("request_id", "request-a")
                .put("result", new JSONObject().put("operation", "notification_remove").put("data",
                        NotificationSyncBridgeTransport.payload(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(request, "removed")))));
        NotificationSyncBridgeTransport.receipt(response, "session-a", "request-a", request);
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.receipt(response, "session-b", "request-a", request));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.receipt(response, "session-a", "request-b", request));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.receipt(response, "session-a", "request-a", NotificationSyncCodec.removed("synthetic", 4)));
        response.getJSONObject("result").put("operation", "notification_post");
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.receipt(response, "session-a", "request-a", request));
    }
    @Test public void pendingIsNotDeliveryAndUnsupportedCapabilitiesStayClosed() throws Exception {
        var request = NotificationSyncCodec.removed("synthetic", 1);
        var response = new JSONObject().put("status", "pending").put("session_id", "session-a").put("request_id", "request-a");
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncBridgeTransport.receipt(response, "session-a", "request-a", request));
        var projection = new JSONObject().put("session_id", "a".repeat(64)).put("operations", new org.json.JSONArray().put("clipboard_write"));
        assertFalse(NotificationSyncBridgeTransport.supports(projection, "notification_post"));
        projection.getJSONArray("operations").put("notification_post");
        assertTrue(NotificationSyncBridgeTransport.supports(projection, "notification_post"));
    }
}
