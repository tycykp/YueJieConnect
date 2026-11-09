package io.github.yuejie.connect;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Typed outbound adapter; it does not register a public operation or establish a control bearer. */
final class NetworkShareRpc implements NetworkShareController.Transport {
    interface Link {
        /** Return the SAME opaque lease object while this target's authenticated control bearer is
         * valid, authorized and verified to survive the consumer's WLAN replacement in either
         * direction. Return null otherwise. Ordinary WifiRoute/LAN connectivity cannot issue it.
         * Rotate/revoke on bearer, session, peer or grant changes; never rebuild it per query.
         */
        Object stableLease(NetworkShareController.Target target);

        /** Shared owner sends its actual typed operation with expectedSession=target.session().
         * Validate the authenticated response operation/session/wire request before constructing
         * Reply. Keep the bridge's wire request identity bound to this local requestId; never log
         * payloads. Pin dispatch to expectedStableLease atomically with the actual bearer send;
         * if it is revoked/replaced, do not dispatch. Null is cleanup only: a stop remains allowed
         * for an owned share after start/renew grants are revoked, using the expected session.
         */
        CompletableFuture<Reply> request(NetworkShareController.Target target, String requestId,
                Object expectedStableLease, Map<String, Object> payload);
    }
    record Reply(NetworkShareController.Target target, String requestId, Map<String, ?> data) {
        Reply { Objects.requireNonNull(target); Objects.requireNonNull(requestId); data = Map.copyOf(data); }
        @Override public String toString() { return "NetworkShareReply[payload redacted]"; }
    }
    private final Link link;
    NetworkShareRpc(Link link) { this.link = Objects.requireNonNull(link); }

    @Override public boolean ready(NetworkShareController.Target target) {
        return lease(target) != null;
    }
    @Override public CompletableFuture<NetworkShareEndpoint.Availability> availability(NetworkShareController.Target target) {
        return call(target, Map.of("action", "availability"), false, data -> {
            NetworkShareEndpoint.Availability result = NetworkShareWire.availability(data);
            if (result.provider() && result.upstream() == null) throw invalid();
            return result;
        });
    }
    @Override public CompletableFuture<NetworkShareEndpoint.Offer> start(NetworkShareController.Target target,
            String share, Upstream upstream, boolean allowed) {
        return call(target, Map.of("action", "start", "share_id", share, "upstream", name(upstream), "allow_metered", allowed), false, data -> {
            NetworkShareEndpoint.Offer offer = NetworkShareWire.offer(target.session(), share, data);
            if (offer.upstream() != upstream) throw invalid();
            return offer;
        });
    }
    @Override public CompletableFuture<View> join(NetworkShareController.Target target, String share,
            NetworkShareEndpoint.Offer offer, boolean allowed) {
        if (target == null || offer == null || !target.session().equals(offer.session()) || !Objects.equals(share, offer.request()))
            return CompletableFuture.failedFuture(new SecurityException("offer_identity_mismatch"));
        return call(target, Map.of("action", "join", "share_id", share, "upstream", name(offer.upstream()),
                "allow_metered", allowed, "ssid", offer.ssid(), "passphrase", offer.passphrase()), false, data -> {
            View view = active(target, share, data);
            if (view.role() != Role.CONSUMER || view.upstream() != offer.upstream()) throw invalid();
            return view;
        });
    }
    @Override public CompletableFuture<View> confirm(NetworkShareController.Target target, String share) {
        return call(target, Map.of("action", "confirm", "share_id", share), false, data -> {
            View view = active(target, share, data);
            if (view.role() != Role.PROVIDER) throw invalid();
            return view;
        });
    }
    @Override public CompletableFuture<View> renew(NetworkShareController.Target target, String share) {
        return call(target, Map.of("action", "renew", "share_id", share), false, data -> active(target, share, data));
    }
    @Override public CompletableFuture<View> stop(NetworkShareController.Target target, String share) {
        // Cleanup still needs an authenticated expected-session response, but must not require a
        // stable/new-operation grant that may have been revoked precisely to trigger cleanup.
        return call(target, Map.of("action", "stop", "share_id", share), true, data -> {
            View view = NetworkShareWire.receipt(target.session(), share, data);
            if (view.phase() != Phase.STOPPED && view.phase() != Phase.FAILED && view.phase() != Phase.RECOVERY_REQUIRED) throw invalid();
            return view;
        });
    }
    private <T> CompletableFuture<T> call(NetworkShareController.Target target, Map<String, Object> payload,
            boolean cleanup, Function<Map<String, ?>, T> decode) {
        try {
            Objects.requireNonNull(target);
            NetworkShareWire.decode(target.session(), payload); // Check our outbound schema, too.
            Object expectedLease = lease(target);
            if (!cleanup && expectedLease == null)
                return CompletableFuture.failedFuture(new IllegalStateException("stable_control_unavailable"));
            String requestId = "network-rpc:" + UUID.randomUUID();
            return Objects.requireNonNull(link.request(target, requestId, cleanup ? null : expectedLease, payload)).thenApply(reply -> {
                if (reply == null || !target.equals(reply.target()) || !requestId.equals(reply.requestId()))
                    throw new SecurityException("network_reply_identity_mismatch");
                if (!cleanup && expectedLease != lease(target)) throw new IllegalStateException("stable_control_lost");
                return decode.apply(reply.data());
            });
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private Object lease(NetworkShareController.Target target) {
        if (target == null) return null;
        try { return link.stableLease(target); } catch (RuntimeException error) { return null; }
    }
    private static View active(NetworkShareController.Target target, String share, Map<String, ?> data) {
        View view = NetworkShareWire.receipt(target.session(), share, data);
        if (view.phase() != Phase.ACTIVE || view.error() != null) throw invalid();
        return view;
    }
    private static String name(Upstream upstream) { return upstream.name().toLowerCase(java.util.Locale.ROOT); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_network_result"); }
}
