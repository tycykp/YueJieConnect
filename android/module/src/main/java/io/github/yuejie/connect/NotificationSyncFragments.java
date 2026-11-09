package io.github.yuejie.connect;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Notification-only carrier. 2 KiB parts also fit Binder when Android JSON escapes every '/'. */
final class NotificationSyncFragments {
    static final int PART_BYTES = 2048, MAX_PARTS = 5, MAX_ACTIVE = 8, MAX_TRANSFERS = 256;
    static final long LIFETIME_MS = 6000;
    record Part(String transfer, int index, int count, int length, String sha256, byte[] bytes, boolean abort) {
        Part { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    static List<JSONObject> split(String transfer, byte[] bytes) throws JSONException {
        validTransfer(transfer);
        if (bytes == null || bytes.length < 1 || bytes.length > NotificationSyncCodec.MAX_BYTES) throw invalid();
        int count = (bytes.length + PART_BYTES - 1) / PART_BYTES;
        String hash = hash(bytes);
        java.util.ArrayList<JSONObject> parts = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) parts.add(new JSONObject().put("transfer_id", transfer).put("index", i)
                .put("count", count).put("length", bytes.length).put("sha256", hash)
                .put("data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, i * PART_BYTES,
                        Math.min(bytes.length, (i + 1) * PART_BYTES)))));
        return List.copyOf(parts);
    }
    static JSONObject abort(String transfer, String hash) throws JSONException {
        validTransfer(transfer); validHash(hash);
        return new JSONObject().put("transfer_id", transfer).put("sha256", hash).put("abort", true);
    }
    static Part parse(JSONObject object) throws JSONException {
        if (object == null) throw invalid();
        String transfer = string(object, "transfer_id"), hash = string(object, "sha256");
        validTransfer(transfer); validHash(hash);
        if (object.has("abort")) {
            if (object.length() != 3 || !Boolean.TRUE.equals(object.opt("abort"))) throw invalid();
            return new Part(transfer, -1, 0, 0, hash, new byte[0], true);
        }
        if (object.length() != 6) throw invalid();
        int index = integer(object, "index"), count = integer(object, "count"), length = integer(object, "length");
        if (length < 1 || length > NotificationSyncCodec.MAX_BYTES || count < 1 || count > MAX_PARTS
                || count != (length + PART_BYTES - 1) / PART_BYTES || index < 0 || index >= count) throw invalid();
        String encoded = string(object, "data");
        if (encoded.length() > 2732) throw invalid();
        byte[] bytes = Base64.getDecoder().decode(encoded);
        int expected = Math.min(PART_BYTES, length - index * PART_BYTES);
        if (bytes.length != expected || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw invalid();
        return new Part(transfer, index, count, length, hash, bytes, false);
    }
    static JSONObject acknowledgement(Part p, byte[] receipt) throws JSONException {
        JSONObject result = new JSONObject().put("transfer_id", p.transfer).put("sha256", p.sha256)
                .put("index", p.index).put("state", receipt == null ? "buffered" : "complete");
        if (receipt != null) result.put("data", Base64.getEncoder().encodeToString(receipt));
        return result;
    }
    /** A buffered acknowledgement can never be interpreted as an execution receipt. */
    static byte[] checkAcknowledgement(JSONObject result, Part part, NotificationSyncCodec.Frame frame) throws JSONException {
        boolean last = part.index == part.count - 1;
        if (result == null || result.length() != (last ? 5 : 4)
                || !part.transfer.equals(result.opt("transfer_id")) || !part.sha256.equals(result.opt("sha256"))
                || integer(result, "index") != part.index || !(last ? "complete" : "buffered").equals(result.opt("state"))) throw invalid();
        if (!last) return null;
        String encoded = string(result, "data");
        if (encoded.length() > NotificationSyncCodec.MAX_RECEIPT_BASE64) throw invalid();
        byte[] receipt = Base64.getDecoder().decode(encoded);
        if (receipt.length > NotificationSyncCodec.MAX_RECEIPT_BYTES || !Base64.getEncoder().encodeToString(receipt).equals(encoded)) throw invalid();
        NotificationSyncCodec.checkReceipt(frame, receipt); return receipt;
    }
    static boolean operation(String value) {
        return NotificationSyncCodec.OP_POST.equals(value) || NotificationSyncCodec.OP_REMOVE.equals(value)
                || NotificationSyncCodec.OP_ACTION.equals(value);
    }
    static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String string(JSONObject object, String key) {
        if (!(object.opt(key) instanceof String value)) throw invalid(); return value;
    }
    private static int integer(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Integer || value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < 0 || number > Integer.MAX_VALUE) throw invalid(); return (int) number;
    }
    private static void validTransfer(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !UUID.fromString(value).toString().equals(value)) throw invalid();
    }
    private static void validHash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("notification_invalid_fragment"); }
}
