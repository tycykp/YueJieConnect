package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class NotificationSyncFragmentsTest {
    static final String TRANSFER = "12345678-1234-1234-1234-123456789abc", SESSION = "a".repeat(64);
    static JSONObject copy(JSONObject object) throws Exception { return new JSONObject(object.toString()); }
    @Test public void maximumFrameUsesFivePartsIncludingWorstAndroidSlashEscaping() throws Exception {
        for (int fill : List.of(0, 255)) {
        byte[] full = new byte[10240]; java.util.Arrays.fill(full, (byte) fill);
        var parts = NotificationSyncFragments.split(TRANSFER, full);
        assertEquals(5, parts.size());
        int reconstructed = 0;
        for (var object : parts) {
            var part = NotificationSyncFragments.parse(object); reconstructed += part.bytes().length;
            // Both outbound submit and inbound authenticated executor task retain ample space under 8192 bytes.
            var submit = new JSONObject().put("op", "bridge_submit").put("projection_id", "b".repeat(64))
                    .put("session_id", SESSION).put("request_id", TRANSFER + ":part" + part.index())
                    .put("operation", "notification_post").put("token", "c".repeat(64)).put("sequence", Long.MAX_VALUE).put("payload", object);
            var task = new JSONObject().put("status", "ok").put("task", new JSONObject()
                    .put("projection_id", "b".repeat(64)).put("session_id", SESSION).put("request_id", "18446744073709551615")
                    .put("operation", "notification_post").put("remaining_ms", 6000).put("deadline_elapsed_ms", Long.MAX_VALUE).put("payload", object));
            assertTrue(submit.toString().getBytes(StandardCharsets.UTF_8).length < 8192);
            assertTrue(task.toString().getBytes(StandardCharsets.UTF_8).length < 8192);
        }
        assertEquals(10240, reconstructed);
        }
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.split(TRANSFER, new byte[10241]));
    }
    @Test public void canonicalFieldsLengthsAndIntegerTypesAreMandatory() throws Exception {
        JSONObject valid = NotificationSyncFragments.split(TRANSFER, new byte[]{0}).get(0);
        for (JSONObject invalid : List.of(copy(valid).put("extra", false), copy(valid).put("index", "0"), copy(valid).put("index", 0.0),
                copy(valid).put("count", 2), copy(valid).put("length", 0), copy(valid).put("length", 10241),
                copy(valid).put("sha256", "A".repeat(64)), copy(valid).put("transfer_id", TRANSFER.toUpperCase()),
                copy(valid).put("data", "AA"), copy(valid).put("data", "AB=="), copy(valid).put("data", "AAAA"))) {
            assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.parse(invalid));
        }
        JSONObject abort = NotificationSyncFragments.abort(TRANSFER, NotificationSyncFragments.hash(new byte[0]));
        assertTrue(NotificationSyncFragments.parse(abort).abort());
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.parse(copy(abort).put("abort", "true")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.parse(copy(abort).put("data", "")));
    }
    @Test public void bufferedAckNeverCountsAsDeliveryAndReceiptBudgetIsExact() throws Exception {
        var frame = NotificationSyncCodec.action("x".repeat(96), 1, "0", "");
        byte[] receipt = NotificationSyncCodec.encode(NotificationSyncCodec.receipt(frame, "pending_intent_finished"));
        assertEquals(140, receipt.length); assertEquals(188, Base64.getEncoder().encodeToString(receipt).length());
        var last = NotificationSyncFragments.parse(NotificationSyncFragments.split(TRANSFER, NotificationSyncCodec.encode(frame)).get(0));
        var ack = NotificationSyncFragments.acknowledgement(last, receipt);
        assertArrayEquals(receipt, NotificationSyncFragments.checkAcknowledgement(ack, last, frame));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.checkAcknowledgement(NotificationSyncFragments.acknowledgement(last, null), last, frame));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.checkAcknowledgement(copy(ack).put("sha256", "b".repeat(64)), last, frame));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncFragments.checkAcknowledgement(copy(ack).put("data", Base64.getEncoder().encodeToString(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(NotificationSyncCodec.action("other", 1, "0", ""), "pending_intent_finished")))), last, frame));
    }
}
