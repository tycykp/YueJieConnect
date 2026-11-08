package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded at-most-once execution guard. Stores request digests, never clipboard contents. */
final class OplusClipboardTasks {
    enum Phase { QUEUED, RUNNING, APPLIED, FAILED, CANCELLED }
    enum Admission { NEW, DUPLICATE_PENDING, REPLAY_APPLIED, REPLAY_FAILED, REPLAY_CANCELLED }
    record Key(String session, String request) {}
    record Permit(Key key, long generation) {}
    record Accepted(Admission admission, Permit permit, String error) {}
    private static final class Entry {
        final Permit permit;
        final String digest;
        final long deadline;
        Phase phase = Phase.QUEUED;
        String error;
        Entry(Permit permit, String digest, long deadline) {
            this.permit = permit; this.digest = digest; this.deadline = deadline;
        }
    }
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private String session;
    private long generation;

    synchronized void session(String next) {
        if (!Objects.equals(session, next)) { session = next; generation++; entries.clear(); }
    }

    synchronized Accepted accept(String expectedSession, String request, String digest, long deadline, long now) {
        if (session == null || !session.equals(expectedSession)) throw new IllegalStateException("stale_session");
        if (request == null || !request.matches("[A-Za-z0-9_:-]{1,128}") || digest == null || !digest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid_request");
        Key key = new Key(expectedSession, request);
        Entry existing = entries.get(key);
        if (existing != null) {
            if (!existing.digest.equals(digest)) throw new IllegalArgumentException("request_id_reused");
            Admission admission = switch (existing.phase) {
                case QUEUED, RUNNING -> Admission.DUPLICATE_PENDING;
                case APPLIED -> Admission.REPLAY_APPLIED;
                case FAILED -> Admission.REPLAY_FAILED;
                case CANCELLED -> Admission.REPLAY_CANCELLED;
            };
            return new Accepted(admission, existing.permit, existing.error);
        }
        if (deadline <= now || deadline - now > 30_000) throw new IllegalArgumentException("expired_or_invalid_deadline");
        // Do not evict terminal entries within a session: a delayed retry must not write twice.
        if (entries.size() >= 256) throw new IllegalStateException("executor_capacity");
        Permit permit = new Permit(key, generation);
        entries.put(key, new Entry(permit, digest, deadline));
        return new Accepted(Admission.NEW, permit, null);
    }

    synchronized boolean claim(Permit permit, long now) {
        Entry entry = current(permit);
        if (entry == null || entry.phase != Phase.QUEUED) return false;
        if (now >= entry.deadline) { entry.phase = Phase.FAILED; entry.error = "request_timeout"; return false; }
        entry.phase = Phase.RUNNING;
        return true;
    }

    synchronized boolean complete(Permit permit, String error) {
        Entry entry = current(permit);
        if (entry == null || entry.phase != Phase.RUNNING) return false;
        entry.phase = error == null ? Phase.APPLIED : Phase.FAILED;
        entry.error = error;
        return true;
    }

    synchronized boolean cancel(String expectedSession, String request) {
        Entry entry = entries.get(new Key(expectedSession, request));
        if (entry == null || entry.phase != Phase.QUEUED) return false;
        entry.phase = Phase.CANCELLED;
        entry.error = "cancelled";
        return true; // RUNNING/APPLIED is never reported as rolled back.
    }

    synchronized String error(Permit permit) {
        Entry entry = current(permit);
        return entry == null ? "stale_session" : entry.error;
    }

    private Entry current(Permit permit) {
        if (permit == null || permit.generation != generation || !Objects.equals(session, permit.key.session)) return null;
        Entry entry = entries.get(permit.key);
        return entry != null && entry.permit.equals(permit) ? entry : null;
    }
}
