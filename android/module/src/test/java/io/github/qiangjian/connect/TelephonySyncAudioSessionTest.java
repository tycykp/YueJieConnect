package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.TelephonySyncAudioSession.*;
import static io.github.qiangjian.connect.TelephonySyncController.Session;

public class TelephonySyncAudioSessionTest {
    private static final Session SESSION = new Session("synthetic-peer", "a".repeat(64));
    private static final String STREAM = "c".repeat(32);
    private static final class Fixture {
        boolean active = true;
        final TelephonySyncAudioSession phone = endpoint(Role.PHONE);
        final TelephonySyncAudioSession pad = endpoint(Role.PAD);
        TelephonySyncAudioSession endpoint(Role role) {
            return new TelephonySyncAudioSession(SESSION, "synthetic_call", STREAM, role, (session, call, actualRole) -> {
                assertEquals(SESSION, session); assertEquals("synthetic_call", call); assertEquals(role, actualRole); return active;
            });
        }
        byte[] frame() { byte[] frame = new byte[FRAME_BYTES]; Arrays.fill(frame, (byte) 0x2a); return frame; }
    }
    private static void rejected(Runnable operation) { assertThrows(IllegalArgumentException.class, operation::run); }
    @Test public void fixedPcmRoundTripBothDirections() {
        Fixture f = new Fixture(); byte[] source = f.frame();
        byte[] packet = f.phone.capture(source); assertEquals(PACKET_BYTES, packet.length);
        assertArrayEquals(source, f.pad.receive(packet)); f.pad.played();
        assertArrayEquals(source, f.phone.receive(f.pad.capture(source))); f.phone.played();
        assertEquals(1, f.phone.capturedFrames()); assertEquals(1, f.phone.playedFrames());
    }
    @Test public void packetHasExactVersionDirectionSessionStreamSequenceAndPts() {
        Fixture f = new Fixture(); ByteBuffer b = ByteBuffer.wrap(f.phone.capture(f.frame()));
        assertEquals(0x514a5441, b.getInt()); assertEquals(1, b.get()); assertEquals(0, b.get()); assertEquals(0, b.getShort());
        b.position(88); assertEquals(0, b.getLong()); assertEquals(0, b.getLong());
        b = ByteBuffer.wrap(f.phone.capture(f.frame())); b.position(88); assertEquals(1, b.getLong()); assertEquals(20_000, b.getLong());
    }
    @Test public void receiveAndCaptureOwnTheirBuffers() {
        Fixture f = new Fixture(); byte[] source = f.frame(), packet = f.phone.capture(source);
        source[0] = 9; byte[] pcm = f.pad.receive(packet); packet[104] = 3;
        assertEquals(42, pcm[0]);
    }
    @Test public void rejectsReplayAndSequenceGap() {
        Fixture f = new Fixture(); byte[] first = f.phone.capture(f.frame()), second = f.phone.capture(f.frame());
        rejected(() -> f.pad.receive(second)); assertArrayEquals(f.frame(), f.pad.receive(first));
        rejected(() -> f.pad.receive(first)); assertArrayEquals(f.frame(), f.pad.receive(second));
    }
    @Test public void wrongSessionAndStreamCannotFeedAnotherCall() {
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame());
        var otherSession = new TelephonySyncAudioSession(new Session("synthetic-peer", "b".repeat(64)), "synthetic_call", STREAM, Role.PAD, (s,c,r) -> true);
        var otherStream = new TelephonySyncAudioSession(SESSION, "synthetic_call", "d".repeat(32), Role.PAD, (s,c,r) -> true);
        rejected(() -> otherSession.receive(packet)); rejected(() -> otherStream.receive(packet));
    }
    @Test public void sameDirectionCannotReflectAudioBack() {
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame()); rejected(() -> f.phone.receive(packet));
    }
    @Test public void streamReuseCannotFeedAnotherNativeCall() {
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame());
        var otherCall = new TelephonySyncAudioSession(SESSION, "different_call", STREAM, Role.PAD, (s,c,r) -> true);
        rejected(() -> otherCall.receive(packet));
    }
    @Test public void rejectsLengthVersionReservedDirectionAndPtsWithoutConsumingSequence() {
        for (int offset : new int[] {0, 4, 5, 6, 7, 96, 103}) {
            Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame()), corrupt = packet.clone(); corrupt[offset] ^= 2;
            rejected(() -> f.pad.receive(corrupt)); assertArrayEquals(f.frame(), f.pad.receive(packet));
        }
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame());
        rejected(() -> f.pad.receive(Arrays.copyOf(packet, packet.length - 1)));
        rejected(() -> f.pad.receive(Arrays.copyOf(packet, packet.length + 1)));
        rejected(() -> f.pad.receive(null)); assertArrayEquals(f.frame(), f.pad.receive(packet));
    }
    @Test public void refusesUnalignedOrVariableSizePcm() {
        Fixture f = new Fixture(); rejected(() -> f.phone.capture(new byte[639])); rejected(() -> f.phone.capture(new byte[642]));
        rejected(() -> f.phone.capture(null)); assertEquals(0, f.phone.capturedFrames());
    }
    @Test public void localRevocationPermanentlyWithdrawsLease() {
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame()); f.active = false;
        assertFalse(f.pad.active()); f.active = true;
        assertThrows(IllegalStateException.class, () -> f.pad.receive(packet));
        assertThrows(IllegalStateException.class, () -> f.pad.capture(f.frame()));
    }
    @Test public void nativePolicyFailureNeverPermitsAudio() {
        var stream = new TelephonySyncAudioSession(SESSION, "synthetic_call", STREAM, Role.PAD, (s,c,r) -> { throw new IllegalStateException(); });
        assertFalse(stream.active()); assertThrows(IllegalStateException.class, () -> stream.capture(new byte[FRAME_BYTES]));
    }
    @Test public void nativePlaybackCannotBeClaimedBeforeAReceivedFrame() {
        Fixture f = new Fixture(); assertThrows(IllegalStateException.class, f.pad::played);
        f.pad.receive(f.phone.capture(f.frame())); assertEquals(0, f.pad.playedFrames());
        f.pad.played(); assertEquals(1, f.pad.playedFrames()); assertThrows(IllegalStateException.class, f.pad::played);
    }
    @Test public void closeStopsCaptureReceiveAndNativeResults() {
        Fixture f = new Fixture(); byte[] packet = f.phone.capture(f.frame()); f.pad.close();
        assertThrows(IllegalStateException.class, () -> f.pad.receive(packet));
        assertThrows(IllegalStateException.class, f.pad::played);
    }
    @Test public void bindingRequiresOpaqueCallAndStreamIds() {
        rejected(() -> new TelephonySyncAudioSession(SESSION, "555 1234", STREAM, Role.PAD, (s,c,r) -> true));
        rejected(() -> new TelephonySyncAudioSession(SESSION, "synthetic_call", "D".repeat(32), Role.PAD, (s,c,r) -> true));
    }
}
