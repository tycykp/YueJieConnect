package io.github.qiangjian.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import static io.github.qiangjian.connect.NetworkShareSession.*;

/** Private write-ahead ledger. Loaded resources are cleaned up, never authenticated or resumed. */
final class NetworkShareRecovery {
    interface Store extends AutoCloseable {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
        void close() throws IOException;
    }
    record Entry(String ownerPackage, int ownerUid, Ticket ticket, Role role, Upstream upstream,
            String ssid, String passphrase, int addedNetwork, int previousNetwork,
            String previousFingerprint, boolean originalWifiEnabled, boolean consumerChanged,
            boolean providerSettled) {
        Entry {
            if (!("com.milink.service".equals(ownerPackage) || "com.oplus.linker".equals(ownerPackage))
                    || ownerUid < 0 || ticket == null || ticket.session() == null
                    || !ticket.session().matches("[a-f0-9]{64}") || ticket.request() == null
                    || !ticket.request().matches("[A-Za-z0-9_:-]{1,128}") || ticket.generation() < 1
                    || role == null || upstream == null || ssid == null || !ssid.matches("QJ-[a-f0-9]{16}")
                    || passphrase == null || !passphrase.matches("[a-f0-9]{32}")
                    || addedNetwork < -1 || previousNetwork < -1 || addedNetwork >= 0 && addedNetwork == previousNetwork
                    || previousFingerprint != null && !previousFingerprint.matches("[a-f0-9]{64}")
                    || role == Role.CONSUMER && (previousNetwork >= 0) != (previousFingerprint != null)
                    || role == Role.PROVIDER && (addedNetwork != -1 || previousNetwork != -1 || consumerChanged))
                throw new IllegalArgumentException("invalid_network_recovery_entry");
        }
        @Override public String toString() { return "NetworkRecoveryEntry[private resource redacted]"; }
    }
    private static final int MAGIC = 0x514A4E52, VERSION = 1, MAX_BYTES = 4096;
    private final Store store;
    private Entry entry;

    NetworkShareRecovery(Store store, String ownerPackage, int ownerUid) throws IOException {
        this.store = Objects.requireNonNull(store);
        byte[] bytes = store.read();
        if (bytes != null) entry = decode(bytes);
        if (entry != null && (!entry.ownerPackage().equals(ownerPackage) || entry.ownerUid() != ownerUid))
            throw new IOException("network_recovery_owner_mismatch");
    }
    Entry entry() { return entry; }
    void prepare(Entry next) throws IOException {
        if (entry != null) throw new IllegalStateException("network_recovery_pending");
        write(next);
    }
    void checkpoint(Entry next) throws IOException {
        Entry old = entry;
        if (old == null || !old.ticket().equals(next.ticket()) || !old.ownerPackage().equals(next.ownerPackage())
                || old.ownerUid() != next.ownerUid() || old.role() != next.role() || old.upstream() != next.upstream()
                || !old.ssid().equals(next.ssid()) || !old.passphrase().equals(next.passphrase())
                || old.previousNetwork() != next.previousNetwork()
                || !Objects.equals(old.previousFingerprint(), next.previousFingerprint())
                || old.originalWifiEnabled() != next.originalWifiEnabled()
                || old.addedNetwork() >= 0 && old.addedNetwork() != next.addedNetwork()
                || old.consumerChanged() && !next.consumerChanged() || old.providerSettled() && !next.providerSettled())
            throw new IllegalStateException("network_recovery_identity_changed");
        write(next);
    }
    void clearVerified(boolean verified) throws IOException {
        if (!verified) throw new IllegalStateException("network_restore_unverified");
        // An atomic tombstone avoids resurrecting an old backup after an interrupted delete.
        write(null);
    }
    void close() throws IOException { store.close(); }
    private void write(Entry next) throws IOException { store.write(encode(next)); entry = next; }

    private static byte[] encode(Entry entry) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeBoolean(entry != null);
            if (entry != null) {
                out.writeUTF(entry.ownerPackage()); out.writeInt(entry.ownerUid());
                out.writeUTF(entry.ticket().session()); out.writeUTF(entry.ticket().request()); out.writeLong(entry.ticket().generation());
                out.writeUTF(entry.role().name()); out.writeUTF(entry.upstream().name());
                out.writeUTF(entry.ssid()); out.writeUTF(entry.passphrase());
                out.writeInt(entry.addedNetwork()); out.writeInt(entry.previousNetwork());
                out.writeUTF(entry.previousFingerprint() == null ? "" : entry.previousFingerprint());
                out.writeBoolean(entry.originalWifiEnabled()); out.writeBoolean(entry.consumerChanged()); out.writeBoolean(entry.providerSettled());
            }
        }
        byte[] bytes = body.toByteArray(); body.write(digest(bytes)); return body.toByteArray();
    }
    private static Entry decode(byte[] bytes) throws IOException {
        if (bytes.length < 41 || bytes.length > MAX_BYTES) throw new IOException("network_recovery_corrupt");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException("network_recovery_corrupt");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("network_recovery_version");
            Entry result = null;
            if (in.readBoolean()) {
                String owner = in.readUTF(); int uid = in.readInt();
                Ticket ticket = new Ticket(in.readUTF(), in.readUTF(), in.readLong());
                Role role = Role.valueOf(in.readUTF()); Upstream upstream = Upstream.valueOf(in.readUTF());
                String ssid = in.readUTF(), passphrase = in.readUTF();
                int added = in.readInt(), previous = in.readInt(); String fingerprint = in.readUTF();
                result = new Entry(owner, uid, ticket, role, upstream, ssid, passphrase, added, previous,
                        fingerprint.isEmpty() ? null : fingerprint, in.readBoolean(), in.readBoolean(), in.readBoolean());
            }
            if (in.available() != 0) throw new IOException("network_recovery_corrupt");
            return result;
        } catch (IllegalArgumentException error) { throw new IOException("network_recovery_corrupt"); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
