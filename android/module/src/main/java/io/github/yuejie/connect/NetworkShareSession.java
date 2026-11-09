package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One owned network change per executor. Clock values are monotonic, never peer wall time. */
final class NetworkShareSession {
    enum Role { PROVIDER, CONSUMER }
    enum Upstream { WIFI, CELLULAR }
    enum Phase { REQUESTED, STARTING, READY, ACTIVE, STOPPING, STOPPED, FAILED, RECOVERY_REQUIRED }
    record Ticket(String session, String request, long generation) {}
    record View(Ticket ticket, Role role, Upstream upstream, Phase phase, long deadline, String error) {}
    private static final long START_TIMEOUT = 30_000, MAX_LEASE = 120_000;
    private final Map<String, View> history = new LinkedHashMap<>();
    private String session;
    private long generation;
    private View current;

    /** A journal is resource ownership evidence only; it cannot renew or reauthenticate a session. */
    synchronized void recover(Ticket ticket, Role role, Upstream upstream) {
        if (current != null || session != null || ticket == null || role == null || upstream == null
                || ticket.session() == null || !ticket.session().matches("[a-f0-9]{64}")
                || ticket.request() == null || !ticket.request().matches("[A-Za-z0-9_:-]{1,128}") || ticket.generation() < 1)
            throw new IllegalStateException("invalid_recovery_state");
        session = ticket.session(); generation = ticket.generation();
        update(new View(ticket, role, upstream, Phase.RECOVERY_REQUIRED, 0, "owner_process_restarted"));
    }

    synchronized void bind(String next) {
        if (Objects.equals(session, next)) return;
        if (ownsResource()) throw new IllegalStateException("restore_before_rebind");
        if (next != null && !next.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid_session");
        session = next;
        current = null;
        history.clear();
    }

    synchronized Ticket request(String expectedSession, String request, Role role, Upstream upstream, long now) {
        if (session == null || !session.equals(expectedSession)) throw new IllegalStateException("stale_session");
        if (request == null || !request.matches("[A-Za-z0-9_:-]{1,128}") || role == null || upstream == null)
            throw new IllegalArgumentException("invalid_request");
        View previous = history.get(request);
        if (previous != null) {
            if (previous.role != role || previous.upstream != upstream) throw new IllegalArgumentException("request_id_reused");
            // Endpoint callers may reset their native handles after request returns. A historical
            // ticket must never be returned while another ticket owns (or last owned) those handles.
            if (current == null || !previous.ticket.equals(current.ticket))
                throw new IllegalStateException("request_already_finished");
            return previous.ticket;
        }
        if (ownsResource()) throw new IllegalStateException("network_busy");
        if (history.size() >= 128) throw new IllegalStateException("executor_capacity");
        Ticket ticket = new Ticket(session, request, ++generation);
        update(new View(ticket, role, upstream, Phase.REQUESTED, now + START_TIMEOUT, null));
        return ticket;
    }

    synchronized boolean confirm(Ticket ticket, long now) {
        View v = require(ticket);
        if (v.phase != Phase.REQUESTED) return false;
        if (now >= v.deadline) { stop(ticket, "confirmation_timeout"); return false; }
        update(new View(ticket, v.role, v.upstream, Phase.STARTING, now + START_TIMEOUT, null));
        return true;
    }

    synchronized boolean ready(Ticket ticket, Upstream observed, boolean ownedNativeNetwork, long now) {
        View v = require(ticket);
        if (v.phase != Phase.STARTING) return false;
        if (now >= v.deadline) { stop(ticket, "start_timeout"); return false; }
        if (!ownedNativeNetwork || observed != v.upstream) {
            stop(ticket, "native_network_mismatch"); return false;
        }
        update(new View(ticket, v.role, v.upstream, Phase.READY, now + START_TIMEOUT, null));
        return true; // A hotspot alone is not an end-to-end network share.
    }

    synchronized boolean attached(Ticket ticket, boolean verifiedConsumer, long now) {
        View v = require(ticket);
        if (v.phase != Phase.READY) return false;
        if (now >= v.deadline) { stop(ticket, "consumer_timeout"); return false; }
        if (!verifiedConsumer) throw new IllegalArgumentException("consumer_not_verified");
        update(new View(ticket, v.role, v.upstream, Phase.ACTIVE, now + MAX_LEASE, null));
        return true;
    }

    synchronized void renew(Ticket ticket, long now) {
        View v = require(ticket);
        if (v.phase != Phase.ACTIVE || now >= v.deadline) throw new IllegalStateException("lease_expired");
        update(new View(ticket, v.role, v.upstream, v.phase, now + MAX_LEASE, null));
    }

    synchronized boolean stop(Ticket ticket, String error) {
        View v = require(ticket);
        if (v.phase == Phase.STOPPED || v.phase == Phase.FAILED || v.phase == Phase.STOPPING) return false;
        update(new View(ticket, v.role, v.upstream, Phase.STOPPING, v.deadline, error));
        return true;
    }

    synchronized void restored(Ticket ticket, boolean verified) {
        View v = require(ticket);
        if (v.phase != Phase.STOPPING) throw new IllegalStateException("not_stopping");
        Phase phase = !verified ? Phase.RECOVERY_REQUIRED : v.error == null ? Phase.STOPPED : Phase.FAILED;
        update(new View(ticket, v.role, v.upstream, phase, v.deadline,
                verified ? v.error : "network_restore_unverified"));
    }

    synchronized Ticket expire(long now) {
        if (!ownsResource() || current.phase == Phase.STOPPING || current.phase == Phase.RECOVERY_REQUIRED
                || now < current.deadline) return null;
        Ticket ticket = current.ticket;
        stop(ticket, current.phase == Phase.ACTIVE ? "lease_expired" : "network_timeout");
        return ticket;
    }

    synchronized View view() { return current; }
    synchronized View view(Ticket ticket) {
        View v = history.get(ticket.request);
        if (v == null || !v.ticket.equals(ticket)) throw new IllegalStateException("stale_request");
        return v;
    }
    synchronized boolean ownsResource() {
        return current != null && current.phase != Phase.STOPPED && current.phase != Phase.FAILED;
    }
    private View require(Ticket ticket) {
        if (current == null || !current.ticket.equals(ticket)) throw new IllegalStateException("stale_request");
        return current;
    }
    private void update(View v) { current = v; history.put(v.ticket.request, v); }
}
