package io.github.yuejie.connect;

import android.os.SystemClock;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** One explicit Android share, scoped to the selected pairing and native directory slot. */
record BrowserRelayOffer(String id, String session, String url, long expires) {
    private static final String SOURCE_PREFIX = "io.github.yuejie.connect.browser.p";
    static final long LIFETIME_MS = 60_000;

    BrowserRelayOffer {
        if (!id.matches("[0-9a-f]{32}") || !session.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid_browser_offer");
        validateUrl(url);
    }

    static BrowserRelayOffer create(String session, String url) {
        return new BrowserRelayOffer(UUID.randomUUID().toString().replace("-", ""), session, url,
                SystemClock.elapsedRealtime() + LIFETIME_MS);
    }

    static String fromShare(CharSequence text) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("invalid_browser_uri");
        String url = null;
        // Share sheets can join the browser's title and URL with a space instead of a newline.
        for (String candidate : text.toString().split("\\s+")) {
            if (!candidate.regionMatches(true, 0, "http://", 0, 7)
                    && !candidate.regionMatches(true, 0, "https://", 0, 8)) continue;
            if (url != null) throw new IllegalArgumentException("invalid_browser_uri");
            validateUrl(candidate);
            url = candidate;
        }
        if (url == null) throw new IllegalArgumentException("invalid_browser_uri");
        return url;
    }

    private static void validateUrl(String url) {
        if (url == null || url.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("invalid_browser_uri");
        new AppRelayRequest("browser-offer", "io.github.yuejie.connect", AppRelayRequest.Kind.BROWSER,
                List.of(), url, "", "", "", 0, 0, "");
    }

    String sourcePackage() { return SOURCE_PREFIX + id; }
    static boolean ownsPackage(String pkg) {
        return pkg != null && pkg.startsWith(SOURCE_PREFIX) && pkg.substring(SOURCE_PREFIX.length()).matches("[0-9a-f]{32}");
    }
    boolean expired() { return SystemClock.elapsedRealtime() >= expires; }
    AppRelayCatalog.Candidate candidate(boolean visible) {
        return new AppRelayCatalog.Candidate(sourcePackage(), "网页接力", AppRelayCatalog.Kind.BROWSER,
                visible, visible, 2, "");
    }
    AppRelayRequest request(String requestId) {
        return new AppRelayRequest(requestId, sourcePackage(), AppRelayRequest.Kind.BROWSER,
                List.of(), url, "", "", "", 0, 0, "");
    }
    JSONObject encode() throws JSONException {
        return new JSONObject().put("id", id).put("session", session).put("url", url).put("expires", expires);
    }
    static BrowserRelayOffer decode(JSONObject value) throws JSONException {
        return new BrowserRelayOffer(value.getString("id"), value.getString("session"), value.getString("url"),
                value.getLong("expires"));
    }

    static final class Inbox {
        private BrowserRelayOffer offer;
        private int claimant;
        private String state = "waiting";

        synchronized BrowserRelayOffer submit(String session, String url) {
            if (offer != null && offer.session.equals(session) && !offer.expired() && "waiting".equals(state))
                throw new IllegalStateException("relay_busy");
            offer = create(session, url);
            claimant = 0;
            state = "waiting";
            return offer;
        }

        synchronized BrowserRelayOffer claim(String session, int pid) {
            if (!live(session) || claimant != 0) return null;
            claimant = pid;
            return offer;
        }

        synchronized void finish(String session, int pid, String id, boolean delivered) {
            if (live(session) && pid == claimant && offer.id.equals(id) && "waiting".equals(state))
                state = delivered ? "delivered" : "failed";
        }

        synchronized String status(String session, String id) {
            return live(session) && offer.id.equals(id) ? state : "expired";
        }

        synchronized void session(String session) {
            if (!live(session)) offer = null;
        }

        private boolean live(String session) {
            return offer != null && offer.session.equals(session) && !offer.expired();
        }
    }
}
