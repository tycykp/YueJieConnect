package io.github.yuejie.connect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** One AVC access unit or 20 ms of stereo, 48 kHz, little-endian PCM16. */
public final class ScreenCastPacket {
    public static final int KEY = 1, CONFIG = 2, END = 4, AUDIO = 8;
    static final int AUDIO_BYTES = 3840;
    public static final int MAX_PAYLOAD = 1_048_576, HEADER_BYTES = 40;
    private static final int MAGIC = 0x594a5631;
    public final long streamId, sequence, presentationTimeUs;
    public final int flags;
    private final int videoSequence;
    private final byte[] data;

    public ScreenCastPacket(long streamId, long sequence, long presentationTimeUs, int flags, byte[] data) {
        this(streamId, sequence, presentationTimeUs, flags, 0, data);
    }
    private ScreenCastPacket(long streamId, long sequence, long presentationTimeUs, int flags, int videoSequence, byte[] data) {
        if (streamId <= 0 || sequence <= 0 || presentationTimeUs < 0 || data == null
                || data.length > MAX_PAYLOAD
                || videoSequence < 0 || videoSequence != 0 && (flags == AUDIO || videoSequence >= sequence)
                || !switch (flags) {
                    case 0, KEY, CONFIG -> data.length > 0;
                    case END -> data.length == 0;
                    case AUDIO -> data.length == AUDIO_BYTES;
                    default -> false;
                }) throw new IllegalArgumentException("invalid_screen_packet");
        this.streamId = streamId; this.sequence = sequence; this.presentationTimeUs = presentationTimeUs;
        this.flags = flags; this.videoSequence = videoSequence; this.data = data.clone();
    }
    private ScreenCastPacket(ScreenCastPacket packet, long sequence, int videoSequence) {
        streamId = packet.streamId; this.sequence = sequence; presentationTimeUs = packet.presentationTimeUs;
        flags = packet.flags; this.videoSequence = videoSequence; data = packet.data;
    }
    ScreenCastPacket withWireSequence(long wireSequence) {
        if (wireSequence == sequence) return this;
        long original = videoSequence();
        if (isAudio() || original >= wireSequence || original > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid_screen_video_sequence");
        return new ScreenCastPacket(this, wireSequence, (int) original);
    }
    // When audio shares the wire sequence, preserve the encoder sequence for loss detection.
    long videoSequence() { return videoSequence == 0 ? sequence : videoSequence; }
    public int size() { return data.length; }
    public boolean isKey() { return (flags & KEY) != 0; }
    public boolean isConfig() { return flags == CONFIG; }
    public boolean isEnd() { return flags == END; }
    public boolean isAudio() { return flags == AUDIO; }
    public byte[] payload() { return data.clone(); }
    void putPayload(ByteBuffer target) { target.put(data); }
    public byte[] encode() {
        return ByteBuffer.allocate(HEADER_BYTES + data.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putShort((short) 1).putShort((short) flags).putLong(streamId)
                .putLong(sequence).putLong(presentationTimeUs).putInt(data.length).putInt(videoSequence).put(data).array();
    }
    public static ScreenCastPacket decode(byte[] wire) {
        if (wire == null || wire.length < HEADER_BYTES || wire.length > HEADER_BYTES + MAX_PAYLOAD)
            throw new IllegalArgumentException("invalid_video_packet_size");
        ByteBuffer b = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.getShort() != 1) throw new IllegalArgumentException("invalid_video_packet_version");
        int flags = Short.toUnsignedInt(b.getShort());
        long stream = b.getLong(), sequence = b.getLong(), pts = b.getLong();
        int length = b.getInt();
        int videoSequence = b.getInt();
        if (length != b.remaining()) throw new IllegalArgumentException("invalid_video_packet_length");
        return new ScreenCastPacket(stream, sequence, pts, flags, videoSequence, Arrays.copyOfRange(wire, HEADER_BYTES, wire.length));
    }
}
