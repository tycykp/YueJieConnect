package io.github.yuejie.connect;

import org.json.JSONException;
import org.json.JSONObject;

/** File identity only: Android URIs, paths, grants and destination choices never cross the wire. */
final class ClipboardUriCodec {
    record Request(String transfer, FileTransferEngine.Offer offer) {
        Request {
            if (transfer == null || !transfer.matches("[0-9a-f]{32}") || offer == null)
                throw new IllegalArgumentException("invalid_clipboard_uri_payload");
        }
    }

    static JSONObject encode(Request request) throws JSONException {
        return new JSONObject().put("transfer_id", request.transfer()).put("name", request.offer().name())
                .put("mime", request.offer().mime()).put("size", request.offer().size()).put("sha256", request.offer().sha256());
    }

    static Request decode(JSONObject payload) {
        try {
            if (payload == null || payload.length() != 5 || !(payload.get("size") instanceof Integer || payload.get("size") instanceof Long)
                    || !(payload.get("transfer_id") instanceof String) || !(payload.get("name") instanceof String)
                    || !(payload.get("mime") instanceof String) || !(payload.get("sha256") instanceof String)) throw new IllegalArgumentException();
            return new Request(payload.getString("transfer_id"), new FileTransferEngine.Offer(payload.getString("name"),
                    payload.getString("mime"), payload.getLong("size"), payload.getString("sha256")));
        } catch (JSONException | RuntimeException invalid) { throw new IllegalArgumentException("invalid_clipboard_uri_payload"); }
    }

    /** Call only after the real receiver future settles; an upload receipt alone cannot encode this ACK. */
    static JSONObject applied(FileTransferEngine.Key key, String request, FileTransferEngine.Offer offer,
            ClipboardUriReceiver.Result result) throws JSONException {
        if (result == null || !result.applied() || !key.peer().equals(result.peer()) || !key.session().equals(result.session())
                || !request.equals(result.request()) || !key.transfer().equals(result.transfer())
                || offer.size() != result.size() || !offer.sha256().equals(result.sha256()))
            throw new IllegalArgumentException("unverified_clipboard_uri_result");
        return new JSONObject().put("transfer_id", key.transfer()).put("mime", offer.mime()).put("applied", true)
                .put("size", result.size()).put("sha256", result.sha256());
    }

    /** Envelope peer/session/request/operation must also match at the authenticated caller. */
    static boolean matchesAck(JSONObject data, FileTransferEngine.Key key, FileTransferEngine.Offer offer) {
        return data != null && data.length() == 5 && Boolean.TRUE.equals(data.opt("applied"))
                && key.transfer().equals(data.opt("transfer_id")) && offer.mime().equals(data.opt("mime"))
                && (data.opt("size") instanceof Integer || data.opt("size") instanceof Long)
                && data.optLong("size", -1) == offer.size() && offer.sha256().equals(data.opt("sha256"));
    }
    private ClipboardUriCodec() {}
}
