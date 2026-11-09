package io.github.yuejie.connect;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone JVM regression checks; does not need Gradle, devices, or the Android stub runtime. */
public final class DeviceFinderLogicTest {
    private static int checks;
    private static final class Backend implements DeviceFinderController.Backend {
        boolean playing, failStart, failStop, silent, holdRelease, releasePending;
        int starts, stops;
        public void start(String peer, String session) throws Exception {
            starts++;
            playing = !silent;
            if (failStart) throw new Exception("partial_start");
        }
        public boolean isRinging() { return playing; }
        public void stop() throws Exception { stops++; if (failStop) throw new Exception("stop_failed"); playing = false; if (holdRelease) releasePending = true; }
        public boolean isReleased() { return !playing && !releasePending; }
    }
    interface Attempt { void run() throws Exception; }
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void fails(String code, Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (DeviceFinderController.Failure error) { check(code.equals(error.code), code); }
    }
    public static void main(String[] args) throws Exception {
        Backend b = new Backend();
        long[] now = {100};
        DeviceFinderController c = new DeviceFinderController(b, () -> now[0]);
        c.bind("session-a");
        b.playing = true;
        fails("finder_busy", () -> c.execute("session-a", "external-state", "state", 0, "test"));
        fails("finder_busy", () -> c.execute("session-a", "external-stop", "stop", 0, "test"));
        check(b.playing && b.stops == 0, "unowned OEM ring is never stopped or reported quiet");
        b.playing = false;
        c.cancel("session-a", "cancel-before-dispatch");
        fails("cancelled", () -> c.execute("session-a", "cancel-before-dispatch", "start", 1000, "test"));
        fails("stale_session", () -> c.execute("old", "1", "start", 1000, "test"));
        fails("invalid_duration", () -> c.execute("session-a", "1", "start", 30001, "test"));
        check(c.execute("session-a", "1", "start", 1000, "test").ringing(), "real start");
        now[0] += 200;
        check(c.execute("session-a", "1", "start", 1000, "test").replayed(), "duplicate request");
        check(c.execute("session-a", "2", "start", 30000, "test").remainingMs() == 800, "duplicate start cannot extend timer");
        check(b.starts == 1, "one playback");
        fails("request_conflict", () -> c.execute("session-a", "1", "stop", 1000, "test"));
        c.cancel("session-b", "1");
        check(b.playing, "wrong-session cancellation ignored");
        c.cancel("session-a", "query");
        check(b.playing, "unrelated cancellation ignored");
        now[0] = 1100;
        c.tick();
        check(!b.playing && b.stops == 1, "deadline releases playback");
        check(!c.execute("session-a", "1", "start", 1000, "test").ringing(), "old start cannot replay after timeout");
        c.execute("session-a", "3", "start", 1000, "test");
        c.bind("session-b");
        check(!b.playing, "session rotation stops");
        fails("stale_session", () -> c.execute("session-a", "4", "start", 1000, "test"));
        c.execute("session-b", "1", "start", 1000, "test");
        b.playing = false;
        c.tick();
        check("native_stopped".equals(c.execute("session-b", "s", "state", 0, "test").reason()), "native interruption observed");
        b.failStart = true;
        try { c.execute("session-b", "bad", "start", 1000, "test"); throw new AssertionError("partial start"); }
        catch (Exception expected) { check(!b.playing, "partial start cleanup"); }
        b.failStart = false;
        fails("finder_execution_failed", () -> c.execute("session-b", "bad", "start", 1000, "test"));
        b.silent = true;
        fails("playback_unverified", () -> c.execute("session-b", "silent", "start", 1000, "test"));
        b.silent = false;
        c.execute("session-b", "active", "start", 1000, "test");
        b.failStop = true;
        try { c.bind("session-c"); throw new AssertionError("stop must fail"); }
        catch (Exception expected) { check(b.playing, "failed cleanup keeps ownership"); }
        b.failStop = false;
        c.bind("session-c");
        check(!b.playing, "cleanup retried on bind");
        for (int i = 0; i < 255; i++) c.execute("session-c", "bounded-" + i, "start", 1000, "test");
        fails("request_capacity", () -> c.execute("session-c", "overflow", "start", 1000, "test"));
        check(!c.execute("session-c", "reserved-stop", "stop", 0, "test").ringing(), "capacity reserves stop");
        for (int i = 0; i < 300; i++) c.execute("session-c", "state-" + i, "state", 0, "test");
        check(!b.playing, "state queries do not consume mutation receipts");
        c.close();
        fails("stale_session", () -> c.execute("session-c", "after-close", "start", 1000, "test"));
        Backend delayed = new Backend();
        DeviceFinderController async = new DeviceFinderController(delayed, () -> now[0]);
        async.bind("async-a");
        async.execute("async-a", "begin", "start", 1000, "test");
        delayed.holdRelease = true;
        fails("cleanup_pending", () -> async.execute("async-a", "end", "stop", 0, "test"));
        fails("cleanup_pending", () -> async.execute("async-a", "query", "state", 0, "test"));
        fails("cleanup_pending", () -> async.bind("async-b"));
        fails("stale_session", () -> async.execute("async-a", "old", "start", 1000, "test"));
        fails("cleanup_pending", () -> async.execute("async-b", "new", "start", 1000, "test"));
        check(delayed.starts == 1, "new session waits for native resource release");
        delayed.holdRelease = false; delayed.releasePending = false;
        async.tick();
        check(async.execute("async-b", "new", "start", 1000, "test").ringing(), "new start after native cleanup");
        async.close();
        check(Arrays.equals(XiaomiFinderCodec.response(3, 100), new byte[]{1, 3, 100}), "state response");
        check(Arrays.equals(XiaomiFinderCodec.response(4, 304), new byte[]{1, 4, 1, 48}), "offline response endian");
        check(Arrays.equals(XiaomiFinderCodec.response(1, 0), new byte[]{1, 1, 0}), "command success response");
        check(XiaomiFinderCodec.decode(new byte[]{0, 3}).method() == 3, "state request");
        byte[] text = "测试用户/测试设备/1234".getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[text.length + 2]; packet[1] = 1; System.arraycopy(text, 0, packet, 2, text.length);
        check(XiaomiFinderCodec.decode(packet).timestamp() == 1234, "native unicode start request");
        for (byte[] invalid : new byte[][]{null, {}, {0}, {1, 1, 0}, {0, 4}, {0, 3, 0}, {0, 1, (byte) 0xff}, {0, 1, 65}}) {
            try { XiaomiFinderCodec.decode(invalid); throw new AssertionError("malformed packet accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check(XiaomiFinderCodec.nativeStatus(1, false) != 0, "unverified start never success");
        check(XiaomiFinderCodec.nativeStatus(2, true) != 0, "unverified stop never success");
        System.out.println("PASS device-finder: " + checks + " lifecycle/protocol assertions");
    }
}
