package io.github.qiangjian.connect;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** OEM-independent race guard. No prefix matching, no successful native sessions implied. */
final class OplusPeerRegistry {
    record Ticket(long number, long generation, String id) {}
    private String id, session;
    private long generation, serial;
    private final Map<Long, Ticket> pending = new HashMap<>();

    synchronized boolean update(String nextId, String nextSession) {
        if (Objects.equals(id, nextId) && Objects.equals(session, nextSession)) return false;
        id = nextId;
        session = nextSession;
        generation++;
        pending.clear();
        return true;
    }

    synchronized boolean owns(String candidate) {
        return id != null && id.equals(candidate);
    }

    synchronized Ticket begin(String candidate) {
        if (!owns(candidate)) throw new IllegalStateException("peer_offline");
        if (pending.size() >= 32) throw new IllegalStateException("busy");
        Ticket ticket = new Ticket(++serial, generation, candidate);
        pending.put(ticket.number(), ticket);
        return ticket;
    }

    synchronized boolean finish(Ticket ticket) {
        return ticket.generation() == generation && owns(ticket.id())
                && pending.remove(ticket.number(), ticket);
    }

    synchronized void cancel(String candidate) {
        pending.values().removeIf(ticket -> ticket.id().equals(candidate));
    }

    synchronized int pendingCount() { return pending.size(); }
}
