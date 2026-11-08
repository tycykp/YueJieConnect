package io.github.qiangjian.connect;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Tracks tombstones for the whole session, so a delayed post cannot resurrect a removed notification. */
final class NotificationSyncLedger {
    private record Entry(long revision, byte[] digest, boolean removed) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Entry> fences = new HashMap<>();
    enum Decision { APPLY, DUPLICATE }
    Decision check(NotificationSyncCodec.Frame f) {
        if (f.kind() != NotificationSyncCodec.POST && f.kind() != NotificationSyncCodec.REMOVE)
            throw new IllegalArgumentException("notification_invalid_operation");
        Entry old = fences.get(f.key());
        if (old == null) {
            if (fences.size() >= 256) throw new IllegalStateException("notification_capacity");
            return Decision.APPLY;
        }
        if (f.revision() < old.revision) throw new IllegalStateException("notification_stale_revision");
        if (f.revision() == old.revision) {
            if (!Arrays.equals(old.digest, digest(NotificationSyncCodec.encode(f))))
                throw new IllegalStateException("notification_revision_conflict");
            Entry committed = entries.get(f.key());
            return committed != null && committed.revision == f.revision() ? Decision.DUPLICATE : Decision.APPLY;
        }
        return Decision.APPLY;
    }
    void commit(NotificationSyncCodec.Frame f) {
        reserve(f);
        entries.put(f.key(), new Entry(f.revision(), digest(NotificationSyncCodec.encode(f)), f.kind() == NotificationSyncCodec.REMOVE));
    }
    /** Fence before the OS side effect; even an uncertain timeout must not admit an older post. */
    void reserve(NotificationSyncCodec.Frame f) {
        check(f);
        fences.put(f.key(), new Entry(f.revision(), digest(NotificationSyncCodec.encode(f)), f.kind() == NotificationSyncCodec.REMOVE));
    }
    void requireActive(String key, long revision) {
        Entry e = entries.get(key);
        Entry latest = fences.get(key);
        if (e == null || latest == null || e.removed || e.revision != revision || latest.revision != revision)
            throw new IllegalStateException("notification_stale_target");
    }
    void clear() { entries.clear(); fences.clear(); }
    static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
