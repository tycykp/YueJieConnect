package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import static io.github.qiangjian.connect.TelephonySyncController.Session;

/** Fixed call-audio PCM frames. Must run over a separately authenticated AUDIO binary channel. */
final class TelephonySyncAudioSession implements AutoCloseable {
    enum Role { PHONE, PAD }
    interface Policy {
        /** Revalidate the exact live native Call/Connection and this call's separate audio consent. */
        boolean active(Session session, String call, Role role);
    }
    static final int SAMPLE_RATE = 16_000;
    static final int FRAME_BYTES = 640; // 320 PCM16 mono samples, 20 ms.
    static final int PACKET_BYTES = 104 + FRAME_BYTES;
    private static final int MAGIC = 0x514a5441; // QJTA
    private final Session session;
    private final String call;
    private final byte[] sessionBytes, streamBytes, callHash;
    private final Role role;
    private final Policy policy;
    private long sent, received, played;
    private boolean closed;
    TelephonySyncAudioSession(Session session, String call, String stream, Role role, Policy policy) {
        this.session = Objects.requireNonNull(session); this.role = Objects.requireNonNull(role);
        this.policy = Objects.requireNonNull(policy);
        if (call == null || !call.matches("[A-Za-z0-9_-]{1,96}") || stream == null || !stream.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("invalid_audio_binding");
        this.call = call; sessionBytes = hex(session.id()); streamBytes = hex(stream);
        try { callHash = MessageDigest.getInstance("SHA-256").digest(call.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    synchronized byte[] capture(byte[] pcm) {
        requireActive();
        if (pcm == null || pcm.length != FRAME_BYTES || sent == Long.MAX_VALUE / 20_000)
            throw new IllegalArgumentException("invalid_audio_frame");
        ByteBuffer b = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).put((byte) 1).put((byte) role.ordinal()).putShort((short) 0);
        b.put(sessionBytes).put(streamBytes).put(callHash).putLong(sent).putLong(sent * 20_000).put(pcm); sent++;
        return b.array();
    }
    synchronized byte[] receive(byte[] packet) {
        requireActive();
        if (packet == null || packet.length != PACKET_BYTES) throw new IllegalArgumentException("invalid_audio_packet");
        ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.get() != 1 || b.get() != (byte) (1 - role.ordinal()) || b.getShort() != 0)
            throw new IllegalArgumentException("invalid_audio_header");
        byte[] sid = new byte[32], stream = new byte[16], call = new byte[32]; b.get(sid).get(stream).get(call);
        if (!Arrays.equals(sid, sessionBytes) || !Arrays.equals(stream, streamBytes) || !Arrays.equals(call, callHash))
            throw new IllegalArgumentException("audio_binding_mismatch");
        long sequence = b.getLong(), pts = b.getLong();
        if (received == Long.MAX_VALUE / 20_000 || sequence != received || pts != received * 20_000)
            throw new IllegalArgumentException("audio_sequence_mismatch");
        byte[] pcm = new byte[FRAME_BYTES]; b.get(pcm); received++; return pcm;
    }
    synchronized void played() { requireActive(); if (played >= received) throw new IllegalStateException("audio_not_received"); played++; }
    synchronized boolean active() {
        if (closed) return false;
        try { if (policy.active(session, call, role)) return true; }
        catch (RuntimeException ignored) { /* A failed native lease check never authorizes audio. */ }
        closed = true;
        return false;
    }
    synchronized long capturedFrames() { return sent; }
    synchronized long playedFrames() { return played; }
    private void requireActive() { if (!active()) throw new IllegalStateException("audio_lease_expired"); }
    @Override public synchronized void close() { closed = true; }
    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
