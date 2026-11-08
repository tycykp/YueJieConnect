package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Additional adversarial checks, separate from the already recorded baseline behavior suite. */
public final class DeviceFinderAdversarialCheck {
    private static int assertions;
    private static final List<String> failures = new ArrayList<>();
    @FunctionalInterface interface Attempt { void run() throws Exception; }
    private static final class Backend implements DeviceFinderController.Backend {
        boolean playing, released = true, delayRelease, retainSound, failStart, failStop;
        int starts, stops;
        @Override public void start(String peer, String session) throws Exception {
            starts++; playing = true; released = false;
            if (failStart) throw new Exception("test_start_failure");
        }
        @Override public boolean isRinging() { return playing; }
        @Override public boolean isReleased() { return released; }
        @Override public void stop() throws Exception {
            stops++;
            if (failStop) throw new Exception("test_stop_failure");
            if (!retainSound) playing = false;
            if (!delayRelease && !retainSound) released = true;
        }
    }
    private static final class Fixture {
        final Backend backend = new Backend();
        final long[] clock = {100};
        final DeviceFinderController controller = new DeviceFinderController(backend, () -> clock[0]);
        Fixture() throws Exception { controller.bind("test-session"); }
        DeviceFinderController.Result request(String id, String action) throws Exception {
            return controller.execute("test-session", id, action, "start".equals(action) ? 1000 : 0, "synthetic-peer");
        }
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void rejects(String code, Attempt attempt) throws Exception {
        try { attempt.run(); throw new AssertionError("Expected " + code); }
        catch (DeviceFinderController.Failure failure) { check(code.equals(failure.code), "Expected " + code + ", got " + failure.code); }
    }
    private static void scenario(String name, Attempt attempt) {
        try { attempt.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures.add(name + ": " + failure); System.out.println("FAIL " + name + ": " + failure.getMessage()); }
    }
    private static byte[] packet(int method, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[body.length + 2]; result[1] = (byte) method;
        System.arraycopy(body, 0, result, 2, body.length); return result;
    }
    private static void invalidPacket(byte[] bytes) {
        byte[] before = bytes.clone();
        try { XiaomiFinderCodec.decode(bytes); throw new AssertionError("Malformed packet accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(Arrays.equals(bytes, before), "decoder mutated its caller's payload");
    }
    public static void main(String[] args) throws Exception {
        scenario("stale_stop_and_cancel_do_not_stop_new_ring", () -> {
            Fixture f = new Fixture(); f.request("first-start", "start"); f.request("first-stop", "stop");
            f.request("second-start", "start"); int stops = f.backend.stops;
            check(f.request("first-stop", "stop").ringing(), "replayed stop must report current playback without stopping it");
            f.controller.cancel("test-session", "first-start");
            check(f.backend.playing && f.backend.stops == stops, "late cancel touched newer playback");
            f.controller.close();
        });
        scenario("duplicate_start_cancel_preserves_original_owner", () -> {
            Fixture f = new Fixture(); f.request("owner", "start"); f.request("duplicate", "start");
            f.controller.cancel("test-session", "duplicate");
            check(f.backend.playing && f.backend.starts == 1, "cancelled duplicate stopped original request");
            rejects("cancelled", () -> f.request("duplicate", "start"));
            f.controller.cancel("test-session", "owner"); check(!f.backend.playing, "owner cancellation did not stop");
        });
        scenario("cancel_flood_preserves_native_stop_capacity", () -> {
            Fixture f = new Fixture(); f.request("owner", "start");
            for (int i = 0; i < 300; i++) f.controller.cancel("test-session", "cancelled-" + i);
            check(f.backend.playing, "unrelated cancellations stopped owner");
            check(!f.request("required-stop", "stop").ringing(), "cancellation tombstones trapped native stop");
            rejects("cancelled", () -> f.request("cancelled-0", "start"));
        });
        scenario("partial_start_and_failed_cleanup_preserve_failure", () -> {
            Fixture f = new Fixture(); f.backend.failStart = true; f.backend.failStop = true;
            try { f.request("broken", "start"); throw new AssertionError("partial start accepted"); }
            catch (Exception failure) { check(failure.getSuppressed().length == 1, "cleanup failure was lost"); }
            f.backend.failStart = false; f.backend.failStop = false; f.controller.tick();
            rejects("finder_execution_failed", () -> f.request("broken", "start"));
            check(f.backend.starts == 1 && !f.backend.playing, "failed start replay caused a second playback");
        });
        scenario("stop_waits_for_sound_and_resource_release", () -> {
            Fixture f = new Fixture(); f.request("owner", "start");
            f.backend.retainSound = true; f.backend.delayRelease = true;
            rejects("cleanup_pending", () -> f.request("stop", "stop"));
            check(f.backend.playing, "fake retained sound fixture");
            f.backend.retainSound = false;
            rejects("cleanup_pending", () -> f.request("stop", "stop"));
            check(!f.backend.playing && !f.backend.released, "stop must also await non-audio resources");
            f.backend.delayRelease = false;
            check(!f.request("stop", "stop").ringing() && f.backend.released, "cleanup completion not observed");
        });
        scenario("close_during_cleanup_prevents_resurrection", () -> {
            Fixture f = new Fixture(); f.request("owner", "start"); f.backend.delayRelease = true;
            rejects("cleanup_pending", f.controller::close);
            rejects("stale_session", () -> f.request("late", "start"));
            f.backend.delayRelease = false; f.controller.tick(); f.controller.bind("test-session");
            rejects("stale_session", () -> f.request("late", "start"));
            check(f.backend.starts == 1 && f.backend.released, "closed controller resurrected");
        });
        scenario("validation_rejections_have_no_playback_effects", () -> {
            Fixture f = new Fixture();
            for (long duration : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
                rejects("invalid_duration", () -> f.controller.execute("test-session", "invalid", "start", duration, "synthetic"));
            for (String id : new String[]{null, "", "x".repeat(129)})
                rejects("invalid_request_id", () -> f.controller.execute("test-session", id, "start", 1, "synthetic"));
            for (String action : new String[]{null, "START", "erase", "notify"})
                rejects("invalid_action", () -> f.controller.execute("test-session", "invalid", action, 1, "synthetic"));
            check(f.backend.starts == 0 && f.backend.stops == 0, "invalid request touched backend");
        });
        scenario("wire_direction_and_method_matrix", () -> {
            byte[] valid = packet(1, "synthetic/peer/1");
            for (int direction = 1; direction < 256; direction++) { byte[] invalid = valid.clone(); invalid[0] = (byte) direction; invalidPacket(invalid); }
            for (int method = 0; method < 256; method++) if (method != 1 && method != 2) {
                byte[] invalid = valid.clone(); invalid[1] = (byte) method; invalidPacket(invalid);
            }
        });
        scenario("wire_utf8_timestamp_and_length_boundaries", () -> {
            for (byte[] malformed : new byte[][]{{(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xe2, (byte) 0x82}}) {
                byte[] wire = new byte[malformed.length + 2]; wire[1] = 1; System.arraycopy(malformed, 0, wire, 2, malformed.length); invalidPacket(wire);
            }
            for (String suffix : new String[]{"", "-1", "9223372036854775808", "1.5", "NaN", " 1", "1\n"})
                invalidPacket(packet(1, "synthetic/peer/" + suffix));
            byte[] max = packet(1, "u".repeat(1018) + "/d/0");
            check(max.length == 1024 && XiaomiFinderCodec.decode(max).timestamp() == 0, "maximum wire boundary");
            invalidPacket(packet(1, "u".repeat(1019) + "/d/0"));
            byte[] response = XiaomiFinderCodec.response(4, 303); invalidPacket(response);
        });
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        System.out.println("PASS device-finder additional negative suite: 9 scenarios, " + assertions + " assertions");
    }
}
