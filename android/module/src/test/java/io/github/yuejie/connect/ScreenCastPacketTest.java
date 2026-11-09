package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenCastPacketTest {
    @Test public void roundTripIsImmutableAndBoundsChecked() {
        byte[] data = {0, 0, 0, 1, 0x65, 9};
        ScreenCastPacket p = new ScreenCastPacket(7, 3, 40000, ScreenCastPacket.KEY, data);
        data[5] = 8;
        ScreenCastPacket r = ScreenCastPacket.decode(p.encode());
        assertEquals(7, r.streamId); assertEquals(3, r.sequence); assertEquals(40000, r.presentationTimeUs);
        assertTrue(r.isKey()); assertEquals(9, r.payload()[5]);
        byte[] payload = r.payload(); payload[5] = 4; assertEquals(9, r.payload()[5]);
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastPacket(1, 1, 0, 0, new byte[1_048_577]));
    }
    @Test public void rejectsTruncationExtensionReservedFlagsAndNegativeTimestamp() {
        byte[] wire = new ScreenCastPacket(1, 1, 0, 2, new byte[]{1}).encode();
        assertThrows(IllegalArgumentException.class, () -> ScreenCastPacket.decode(java.util.Arrays.copyOf(wire, 40)));
        assertThrows(IllegalArgumentException.class, () -> ScreenCastPacket.decode(java.util.Arrays.copyOf(wire, 42)));
        wire[39] = 1; assertThrows(IllegalArgumentException.class, () -> ScreenCastPacket.decode(wire));
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastPacket(1, 1, -1, 0, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastPacket(1, 1, 0, 3, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastPacket(1, 1, 0, 8, new byte[]{1}));
    }
    @Test public void invalidFormatDoesNotAllocateCodec() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastFormat(4096, 4096, 30, 2_000_000));
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastFormat(721, 1280, 30, 2_000_000));
        assertEquals(30, new ScreenCastFormat(720, 1280, 30, 2_000_000).fps());
    }
    @Test public void audioHasOneCanonicalFrameSizeAndCannotCarryVideoFlags() {
        byte[] pcm = new byte[ScreenCastPacket.AUDIO_BYTES];
        pcm[17] = 42;
        var packet = ScreenCastPacket.decode(new ScreenCastPacket(7, 3, 40, ScreenCastPacket.AUDIO, pcm).encode());
        assertTrue(packet.isAudio());
        assertFalse(packet.isKey());
        assertArrayEquals(pcm, packet.payload());
        assertThrows(IllegalArgumentException.class, () -> new ScreenCastPacket(7, 3, 40,
                ScreenCastPacket.AUDIO | ScreenCastPacket.KEY, pcm));
    }
}
