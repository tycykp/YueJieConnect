package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** One complete AVC access unit. Must travel inside an authenticated, session-bound channel. */
public final class ScreenCastPacket {
    public static final int KEY = 1, CONFIG = 2, END = 4;
    public static final int MAX_PAYLOAD = 1_048_576, HEADER_BYTES = 40;
    private static final int MAGIC = 0x514a5631;
    public final long streamId, sequence, presentationTimeUs;
    public final int flags;
    private final byte[] data;

    public ScreenCastPacket(long streamId, long sequence, long presentationTimeUs, int flags, byte[] data) {
        if (streamId <= 0 || sequence <= 0 || presentationTimeUs < 0 || data == null
                || data.length > MAX_PAYLOAD || (flags & ~7) != 0
                || ((flags & CONFIG) != 0 && flags != CONFIG)
                || ((flags & END) != 0 && (flags != END || data.length != 0))
                || (data.length == 0 && flags != END)) throw new IllegalArgumentException("invalid_video_packet");
        this.streamId = streamId; this.sequence = sequence; this.presentationTimeUs = presentationTimeUs;
        this.flags = flags; this.data = data.clone();
    }
    public int size() { return data.length; }
    public boolean isKey() { return (flags & KEY) != 0; }
    public boolean isConfig() { return flags == CONFIG; }
    public boolean isEnd() { return flags == END; }
    public byte[] payload() { return data.clone(); }
    void putPayload(ByteBuffer target) { target.put(data); }
    public byte[] encode() {
        return ByteBuffer.allocate(HEADER_BYTES + data.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putShort((short) 1).putShort((short) flags).putLong(streamId)
                .putLong(sequence).putLong(presentationTimeUs).putInt(data.length).putInt(0).put(data).array();
    }
    public static ScreenCastPacket decode(byte[] wire) {
        if (wire == null || wire.length < HEADER_BYTES || wire.length > HEADER_BYTES + MAX_PAYLOAD)
            throw new IllegalArgumentException("invalid_video_packet_size");
        ByteBuffer b = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.getShort() != 1) throw new IllegalArgumentException("invalid_video_packet_version");
        int flags = Short.toUnsignedInt(b.getShort());
        long stream = b.getLong(), sequence = b.getLong(), pts = b.getLong();
        int length = b.getInt();
        if (b.getInt() != 0 || length != b.remaining()) throw new IllegalArgumentException("invalid_video_packet_length");
        return new ScreenCastPacket(stream, sequence, pts, flags, Arrays.copyOfRange(wire, HEADER_BYTES, wire.length));
    }
}
