package io.github.yuejie.connect;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** OEM-independent race guard. No prefix matching, no successful native sessions implied. */
final class OplusPeerRegistry {
    /** A saved OFFLINE entry with our exact alias must not hide the live authenticated projection. */
    static java.util.List<Object> overlay(java.util.List<?> original,Object live,java.util.function.Function<Object,String> identity){
        String selected=java.util.Objects.requireNonNull(identity.apply(live));
        java.util.List<Object> result=new java.util.ArrayList<>();boolean replaced=false;
        for(Object item:original){
            if(selected.equals(identity.apply(item))){if(!replaced){result.add(live);replaced=true;}}
            else result.add(item);
        }
        if(!replaced)result.add(live);return result;
    }
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
