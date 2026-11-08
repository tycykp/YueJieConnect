package io.github.qiangjian.connect;

import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded application RPC codec, not OAF or MiExpress wire framing. */
public final class FileTransferProtocol {
    public static final Set<String> OPERATIONS = Set.of("file_offer", "file_chunk", "file_commit", "file_cancel");
    private final FileTransferEngine receiver;

    public FileTransferProtocol(FileTransferEngine receiver) { this.receiver = receiver; }

    /** peer/session must come from the authenticated bridge task, never the payload. */
    public JSONObject execute(String peer, String session, String operation, JSONObject payload) throws IOException {
        try {
            if (payload == null) throw new IOException("invalid_file_payload");
            String transfer = string(payload, "transfer");
            FileTransferEngine.Key key = new FileTransferEngine.Key(peer, session, transfer);
            JSONObject result = new JSONObject().put("transfer", transfer);
            switch (operation) {
                case "file_offer" -> {
                    fields(payload, "transfer", "name", "mime", "size", "sha256");
                    FileTransferEngine.Offer offer = offer(payload);
                    result.put("offset", receiver.begin(key, offer));
                }
                case "file_chunk" -> {
                    fields(payload, "transfer", "offset", "data");
                    String encoded = string(payload, "data");
                    if (encoded.length() > FileTransferEngine.CHUNK_BYTES * 4 / 3) throw new IOException("invalid_chunk");
                    byte[] bytes = Base64.getDecoder().decode(encoded);
                    if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IOException("invalid_chunk_encoding");
                    result.put("offset", receiver.append(key, integer(payload, "offset"), bytes));
                }
                case "file_commit" -> {
                    fields(payload, "transfer");
                    FileTransferEngine.Receipt receipt = receiver.commit(key);
                    result.put("size", receipt.size()).put("sha256", receipt.sha256()).put("committed", true);
                }
                case "file_cancel" -> {
                    fields(payload, "transfer");
                    result.put("cancelled", receiver.cancel(key));
                }
                default -> throw new IOException("invalid_file_operation");
            }
            return result;
        } catch (JSONException | IllegalArgumentException error) { throw new IOException("invalid_file_payload", error); }
    }

    public static FileTransferEngine.Offer offer(JSONObject payload) throws IOException, JSONException {
        return new FileTransferEngine.Offer(string(payload, "name"), string(payload, "mime"),
                integer(payload, "size"), string(payload, "sha256"));
    }

    public static JSONObject offerPayload(FileTransferEngine.Key key, FileTransferEngine.Offer offer) throws JSONException {
        return identity(key).put("name", offer.name()).put("mime", offer.mime()).put("size", offer.size()).put("sha256", offer.sha256());
    }

    public static JSONObject identity(FileTransferEngine.Key key) throws JSONException { return new JSONObject().put("transfer", key.transfer()); }

    static String string(JSONObject object, String key) throws IOException, JSONException {
        Object value = object.get(key);
        if (!(value instanceof String text)) throw new IOException("invalid_file_payload");
        return text;
    }

    static long integer(JSONObject object, String key) throws IOException, JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("invalid_file_payload");
        long result = ((Number) value).longValue();
        if (result < 0) throw new IOException("invalid_file_payload");
        return result;
    }

    static void fields(JSONObject object, String... names) throws IOException {
        Set<String> allowed = Set.of(names);
        if (object.length() != allowed.size()) throw new IOException("invalid_file_payload");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IOException("invalid_file_payload");
    }
}
