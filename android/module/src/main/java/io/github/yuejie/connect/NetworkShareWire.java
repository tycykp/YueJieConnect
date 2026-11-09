package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Strict business payload conversion. The bridge owns JSON parsing, authenticated session and RPC names.
 * share_id is stable across the start/join/confirm/stop RPCs; it is NOT their individual wire request_id.
 * Offers contain generated temporary credentials: never include this map in diagnostics/logging.
 */
final class NetworkShareWire {
    record Command(String action, String shareId, Upstream upstream, boolean meteredAllowed, NetworkShareEndpoint.Offer offer) {
        @Override public String toString() { return "NetworkShareCommand[" + action + ", payload redacted]"; }
    }
    static Command decode(String authenticatedSession, Map<String, ?> payload) {
        if (authenticatedSession == null || !authenticatedSession.matches("[a-f0-9]{64}")) throw invalid();
        String action = string(payload, "action");
        Set<String> keys = switch (action) {
            case "availability" -> Set.of("action");
            case "start" -> Set.of("action", "share_id", "upstream", "allow_metered");
            case "join" -> Set.of("action", "share_id", "upstream", "allow_metered", "ssid", "passphrase");
            case "confirm", "renew", "stop" -> Set.of("action", "share_id");
            default -> throw invalid();
        };
        if (!payload.keySet().equals(keys)) throw invalid();
        if (action.equals("availability")) return new Command(action, null, null, false, null);
        String shareId = string(payload, "share_id");
        if (!shareId.matches("[A-Za-z0-9_:-]{1,128}")) throw invalid();
        if (!action.equals("start") && !action.equals("join")) return new Command(action, shareId, null, false, null);
        Upstream upstream = upstream(string(payload, "upstream"));
        if (!(payload.get("allow_metered") instanceof Boolean allow)) throw invalid();
        if (upstream == Upstream.CELLULAR && !allow) throw new SecurityException("metered_network_not_authorized");
        NetworkShareEndpoint.Offer offer = action.equals("join") ? new NetworkShareEndpoint.Offer(authenticatedSession, shareId,
                string(payload, "ssid"), string(payload, "passphrase"), upstream) : null;
        return new Command(action, shareId, upstream, allow, offer);
    }

    /** Call only after the shared bridge validates task session, deadline, caller and operation grants. */
    static CompletableFuture<Map<String, Object>> execute(NetworkShareController.Local endpoint, String session, Command command) {
        return switch (command.action()) {
            case "availability" -> endpoint.availability().thenApply(NetworkShareWire::availability);
            case "start" -> endpoint.startProvider(session, command.shareId(), command.upstream(), command.meteredAllowed()).thenApply(NetworkShareWire::offer);
            case "join" -> endpoint.joinConsumer(session, command.shareId(), command.offer(), command.meteredAllowed()).thenApply(NetworkShareWire::receipt);
            case "confirm" -> endpoint.confirmConsumer(session, command.shareId()).thenApply(NetworkShareWire::receipt);
            case "renew" -> endpoint.renew(session, command.shareId()).thenApply(NetworkShareWire::receipt);
            case "stop" -> endpoint.stop(session, command.shareId()).thenApply(NetworkShareWire::receipt);
            default -> throw invalid();
        };
    }
    static Map<String, Object> availability(NetworkShareEndpoint.Availability a) {
        return Map.of("provider", a.provider(), "consumer", a.consumer(), "upstream", a.upstream() == null ? "none" : name(a.upstream()),
                "metered", a.metered(), "error", a.error() == null ? "" : a.error());
    }
    static NetworkShareEndpoint.Availability availability(Map<String, ?> map) {
        exact(map, "provider", "consumer", "upstream", "metered", "error");
        String type = string(map, "upstream"), error = string(map, "error");
        return new NetworkShareEndpoint.Availability(bool(map, "provider"), bool(map, "consumer"), type.equals("none") ? null : upstream(type),
                bool(map, "metered"), error.isEmpty() ? null : error);
    }
    static Map<String, Object> offer(NetworkShareEndpoint.Offer offer) {
        return Map.of("share_id", offer.request(), "ssid", offer.ssid(), "passphrase", offer.passphrase(), "upstream", name(offer.upstream()));
    }
    static NetworkShareEndpoint.Offer offer(String session, String expectedShareId, Map<String, ?> map) {
        exact(map, "share_id", "ssid", "passphrase", "upstream");
        if (!expectedShareId.equals(string(map, "share_id"))) throw new SecurityException("offer_identity_mismatch");
        return new NetworkShareEndpoint.Offer(session, expectedShareId, string(map, "ssid"), string(map, "passphrase"), upstream(string(map, "upstream")));
    }
    static Map<String, Object> receipt(View v) {
        return Map.of("share_id", v.ticket().request(), "role", v.role().name().toLowerCase(java.util.Locale.ROOT),
                "upstream", name(v.upstream()), "phase", v.phase().name().toLowerCase(java.util.Locale.ROOT), "error", v.error() == null ? "" : v.error());
    }
    static View receipt(String session, String expectedShareId, Map<String, ?> map) {
        exact(map, "share_id", "role", "upstream", "phase", "error");
        if (!session.matches("[a-f0-9]{64}") || !expectedShareId.equals(string(map, "share_id"))) throw new SecurityException("receipt_identity_mismatch");
        String role = string(map, "role"), phase = string(map, "phase"), error = string(map, "error");
        try {
            Role r = Role.valueOf(role.toUpperCase(java.util.Locale.ROOT));
            Phase p = Phase.valueOf(phase.toUpperCase(java.util.Locale.ROOT));
            if (!role.equals(r.name().toLowerCase(java.util.Locale.ROOT)) || !phase.equals(p.name().toLowerCase(java.util.Locale.ROOT))) throw invalid();
            return new View(new Ticket(session, expectedShareId, 0), r, upstream(string(map, "upstream")), p, 0, error.isEmpty() ? null : error);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static void exact(Map<String, ?> map, String... keys) { if (map == null || !map.keySet().equals(Set.of(keys))) throw invalid(); }
    private static String string(Map<String, ?> map, String key) {
        if (map == null || !(map.get(key) instanceof String text) || text.length() > 256) throw invalid(); return text;
    }
    private static boolean bool(Map<String, ?> map, String key) { if (!(map.get(key) instanceof Boolean value)) throw invalid(); return value; }
    private static Upstream upstream(String value) { return switch (value) { case "wifi" -> Upstream.WIFI; case "cellular" -> Upstream.CELLULAR; default -> throw invalid(); }; }
    private static String name(Upstream upstream) { return upstream.name().toLowerCase(java.util.Locale.ROOT); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_network_payload"); }
    private NetworkShareWire() {}
}
