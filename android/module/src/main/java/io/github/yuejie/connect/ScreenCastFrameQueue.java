package io.github.yuejie.connect;

import java.util.ArrayDeque;

/** Drops dependent pictures after loss/overflow until an IDR; never blocks a codec callback. */
public final class ScreenCastFrameQueue implements AutoCloseable {
    public enum Offer { ACCEPTED, NEED_KEY_FRAME, STALE, CLOSED }
    public static final int MAX_FRAMES = 8, MAX_BYTES = 4 * 1_048_576;
    private final long streamId;
    private final ArrayDeque<ScreenCastPacket> queue = new ArrayDeque<>();
    private int bytes;
    private long sequence, lastPts = -1;
    private boolean configured, needKey = true, closed;
    private ScreenCastPacket config;

    public ScreenCastFrameQueue(long streamId) {
        if (streamId <= 0) throw new IllegalArgumentException("invalid_stream");
        this.streamId = streamId;
    }
    public synchronized Offer offer(ScreenCastPacket p) {
        if (closed) return Offer.CLOSED;
        long videoSequence = p.videoSequence();
        if (p.isAudio() || p.streamId != streamId || videoSequence <= sequence) return Offer.STALE;
        boolean gap = sequence != 0 && videoSequence != sequence + 1;
        sequence = videoSequence;
        if (p.isEnd()) { clear(); add(p); closed = true; return Offer.ACCEPTED; }
        if (p.isConfig()) {
            clear(); config = p; configured = true; needKey = true; lastPts = -1; add(p);
            return Offer.ACCEPTED;
        }
        if (!configured) return Offer.NEED_KEY_FRAME;
        if (gap) recover();
        if (p.presentationTimeUs <= lastPts) { recover(); return Offer.STALE; }
        if (queue.size() >= MAX_FRAMES || bytes + p.size() > MAX_BYTES) recover();
        if (needKey && !p.isKey()) return Offer.NEED_KEY_FRAME;
        needKey = false; lastPts = p.presentationTimeUs; add(p); return Offer.ACCEPTED;
    }
    private void recover() { clear(); needKey = true; if (config != null) add(config); }
    private void add(ScreenCastPacket p) { queue.add(p); bytes += p.size(); }
    private void clear() { queue.clear(); bytes = 0; }
    public synchronized ScreenCastPacket poll() {
        ScreenCastPacket p = queue.poll(); if (p != null) bytes -= p.size(); return p;
    }
    public synchronized int bytes() { return bytes; }
    public synchronized int size() { return queue.size(); }
    public synchronized boolean needsKeyFrame() { return needKey; }
    @Override public synchronized void close() { closed = true; clear(); config = null; }
}
