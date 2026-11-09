package io.github.yuejie.connect;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** New still/peer contract checks only. Synthetic JPEG headers are never represented as actual images. */
public final class CameraShareNativeConsumerChecks {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Runnable runnable, String label) {
        try { runnable.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException expected) { checks++; }
    }
    private static CameraShareStillRequest request(String session, long stream, String id, int orientation) {
        return new CameraShareStillRequest(session, "peer", stream, id, "physical-front", 640, 480, orientation);
    }
    private static byte[] header(int width, int height) {
        return new byte[]{(byte)255,(byte)216, (byte)255,(byte)192,0,17,8,
                (byte)(height >> 8),(byte)height,(byte)(width >> 8),(byte)width,3,
                1,17,0,2,17,0,3,17,0, (byte)255,(byte)218,0,12,3,1,0,2,17,3,17,0,63,0,
                0,(byte)255,(byte)217};
    }
    public static void main(String[] args) throws Exception {
        CameraShareStillRequest req = request("session", 7, "exposure-1", 0);
        rejects(() -> request(" ", 7, "exposure", 0), "empty session");
        rejects(() -> request("session", 0, "exposure", 0), "invalid stream");
        rejects(() -> request("session", 7, "exposure", 45), "orientation must be native quadrant");
        rejects(() -> new CameraShareStillRequest("session", "peer", 7, "r", "cam", Integer.MAX_VALUE, 1, 0), "overflow");
        rejects(() -> new CameraShareStillRequest("session", "peer", 7, "r", "cam", 8192, 8192, 0), "pixel budget");
        byte[] jpeg = header(640, 480);
        CameraShareJpeg.validate(jpeg, req);
        check(CameraShareJpeg.dimensions(jpeg).equals(new CameraShareJpeg.Size(640,480)), "exact JPEG dimensions");
        rejects(() -> CameraShareJpeg.validate(header(800, 600), req), "unnegotiated resolution");
        rejects(() -> CameraShareJpeg.validate(header(480, 640), req), "swapped pixels without rotation");
        CameraShareJpeg.validate(header(480, 640), request("session", 7, "r", 90));
        CameraShareJpeg.validate(header(640, 480), request("session", 7, "r", 90));
        check(true, "rotated pixels or EXIF orientation both allowed");
        rejects(() -> CameraShareJpeg.dimensions(null), "missing JPEG");
        rejects(() -> CameraShareJpeg.dimensions(new byte[CameraShareStillRequest.MAX_JPEG_BYTES + 1]), "encoded byte limit");
        byte[] bad = jpeg.clone(); bad[5] = (byte)255;
        rejects(() -> CameraShareJpeg.dimensions(bad), "truncated segment");
        byte[] noEnd = jpeg.clone(); noEnd[noEnd.length - 1] = 0;
        rejects(() -> CameraShareJpeg.dimensions(noEnd), "incomplete exposure");
        byte[] zeroHeight = jpeg.clone(); zeroHeight[7] = zeroHeight[8] = 0;
        rejects(() -> CameraShareJpeg.dimensions(zeroHeight), "zero dimensions");
        byte[] unsupported = jpeg.clone(); unsupported[3] = (byte)0xc3;
        rejects(() -> CameraShareJpeg.dimensions(unsupported), "unsupported encoding");
        ByteArrayOutputStream duplicated = new ByteArrayOutputStream();
        duplicated.write(jpeg,0,21); duplicated.write(jpeg,2,jpeg.length-2);
        rejects(() -> CameraShareJpeg.dimensions(duplicated.toByteArray()), "ambiguous frame headers");
        // Every truncated prefix must fail, without an unchecked array-index exception.
        for (int length = 0; length < jpeg.length; length++) {
            byte[] prefix = java.util.Arrays.copyOf(jpeg, length);
            rejects(() -> CameraShareJpeg.dimensions(prefix), "truncated JPEG prefix " + length);
        }
        CameraShareStillRequest.History history = new CameraShareStillRequest.History();
        check(history.claim(req), "first exposure claim");
        check(!history.claim(req), "same exposure replay");
        check(!history.claim(request("session", 8, "exposure-1", 90)), "request id cannot replay on changed stream/config");
        check(history.claim(request("new-session", 7, "exposure-1", 0)), "fresh authenticated session has its own ids");
        for (int i = 0; i < 126; i++) check(history.claim(request("session", 7, "r" + i, 0)), "bounded history slot");
        rejects(() -> history.claim(request("session", 7, "overflow", 0)), "do not evict into replayability");
        check(!history.claim(req), "old request still rejected at capacity");
        CameraShareStillRequest.History raced = new CameraShareStillRequest.History();
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        Thread[] contenders = new Thread[8];
        for (int i = 0; i < contenders.length; i++) {
            contenders[i] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (raced.claim(req)) wins.incrementAndGet();
            });
            contenders[i].start();
        }
        go.countDown();
        for (Thread t : contenders) t.join();
        check(wins.get() == 1, "one exposure under concurrent dispatch");

        CameraShareConfig.Camera source = new CameraShareConfig.Camera("physical-front", 0, 90,
                List.of(new CameraShareConfig.Mode(640,480,15,30)));
        XiaomiCameraHardwareBinding.Selection selection = new XiaomiCameraHardwareBinding.Selection(
                "session", "peer", "real-native-device", "real-hardware-id", 101, "front", source);
        selection.validate("session", "peer", "real-native-device", "real-hardware-id", 101,
                (s,p,d,h,c,src) -> s.equals("session") && p.equals("peer") && d.equals("real-native-device")
                        && h.equals("real-hardware-id") && c == 101 && src.equals("physical-front"));
        check(selection.preview(640,480,30,1_000_000).cameraId.equals("physical-front"), "native int id is not Camera2 id");
        rejects(() -> selection.preview(1280,720,30,1_000_000), "source catalog limits native selection");
        rejects(() -> selection.validate("old-session", "peer", "real-native-device", "real-hardware-id", 101, (s,p,d,h,c,src)->true), "stale session");
        rejects(() -> selection.validate("session", "other-peer", "real-native-device", "real-hardware-id", 101, (s,p,d,h,c,src)->true), "wrong peer");
        rejects(() -> selection.validate("session", "peer", "other-device", "real-hardware-id", 101, (s,p,d,h,c,src)->true), "wrong native device");
        rejects(() -> selection.validate("session", "peer", "real-native-device", "other-hardware", 101, (s,p,d,h,c,src)->true), "wrong hardware");
        rejects(() -> selection.validate("session", "peer", "real-native-device", "real-hardware-id", 1, (s,p,d,h,c,src)->true), "wrong native camera");
        rejects(() -> selection.validate("session", "peer", "real-native-device", "real-hardware-id", 101, (s,p,d,h,c,src)->false), "revoked peer mapping");
        rejects(() -> selection.validate("session", "peer", "real-native-device", "real-hardware-id", 101,
                (s,p,d,h,c,src)->src.equals("101")), "do not conflate native id and remote Camera2 id");
        System.out.println("camera-native-consumer-checks: PASS (" + checks + " assertions)");
    }
}
