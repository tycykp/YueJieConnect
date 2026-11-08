package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests result provenance with synthetic headers only; no claim of Camera2/native acquisition. */
public final class CameraShareStillReceiptChecks {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static CameraShareStillRequest request(String session, String id, long stream) {
        return new CameraShareStillRequest(session, "physical-phone", stream, id, "rear-0", 640, 480, 0);
    }
    private static byte[] jpeg() {
        return new byte[]{(byte)255,(byte)216, (byte)255,(byte)192,0,17,8,1,(byte)224,2,(byte)128,3,
                1,17,0,2,17,0,3,17,0, (byte)255,(byte)218,0,12,3,1,0,2,17,3,17,0,63,0,
                0,(byte)255,(byte)217};
    }
    private static CameraShareStillReceipt pending(CameraShareStillRequest request, byte[] jpeg) {
        return new CameraShareStillReceipt(request, 34, 700, jpeg);
    }
    public static void main(String[] args) throws Exception {
        CameraShareStillRequest request = request("session", "capture-1", 91);
        byte[] jpeg = jpeg();
        CameraShareStillReceipt receipt = pending(request, jpeg);
        check(receipt.acquired(request, 34, 800, 900, ByteBuffer.wrap(jpeg)) == null, "acquisition before enqueue cannot succeed");
        receipt.queued(750);
        check(receipt.acquired(request("other-session", "capture-1", 91), 34, 800, 900, ByteBuffer.wrap(jpeg)) == null, "session isolation");
        check(receipt.acquired(request("session", "capture-2", 91), 34, 800, 900, ByteBuffer.wrap(jpeg)) == null, "exposure isolation");
        check(receipt.acquired(request("session", "capture-1", 92), 34, 800, 900, ByteBuffer.wrap(jpeg)) == null, "stream isolation");
        check(receipt.acquired(request, 35, 800, 900, ByteBuffer.wrap(jpeg)) == null, "surface generation isolation");
        check(receipt.acquired(request, 34, 0, 900, ByteBuffer.wrap(jpeg)) == null, "missing native timestamp");
        check(receipt.acquired(request, 34, 749, 900, ByteBuffer.wrap(jpeg)) == null,
                "identical buffered JPEG from before this enqueue cannot acknowledge this request");
        check(receipt.acquired(request, 34, 901, 900, ByteBuffer.wrap(jpeg)) == null, "future/foreign clock rejected");
        receipt.queued(1);
        check(receipt.acquired(request, 34, 700, 900, ByteBuffer.wrap(jpeg)) == null, "repeat enqueue cannot relax the original time boundary");
        check(receipt.acquired(request, 34, 800, 900, null) == null, "no plane");
        check(receipt.acquired(request, 34, 800, 900, ByteBuffer.wrap(new byte[jpeg.length - 1])) == null, "truncated original plane");
        check(receipt.acquired(request, 34, 800, 900, ByteBuffer.wrap(new byte[jpeg.length + 1])) == null, "extra plane bytes");
        byte[] corrupt = jpeg.clone(); corrupt[corrupt.length - 3] ^= 1;
        check(receipt.acquired(request, 34, 800, 900, ByteBuffer.wrap(corrupt)) == null, "equal dimensions and length do not prove same frame");
        ByteBuffer original = ByteBuffer.wrap(jpeg).asReadOnlyBuffer();
        original.mark();
        CameraShareStillReceipt.Evidence evidence = receipt.acquired(request, 34, 800, 900, original);
        check(evidence != null, "exact original plane completes");
        check(evidence.request().equals(request) && evidence.nativeSurfaceId() == 34 && evidence.jpegBytes() == jpeg.length,
                "result retains exact request and native target");
        check(evidence.sourceTimestampNs() == 700 && evidence.acquiredTimestampNs() == 800,
                "source and native clocks are distinct facts");
        check(original.position() == 0 && original.limit() == jpeg.length, "OEM buffer position and limit preserved");
        original.reset(); check(original.position() == 0, "OEM buffer mark preserved");
        check(receipt.acquired(request, 34, 801, 900, original) == null, "duplicate native acquisition cannot complete twice");

        CameraShareStillReceipt cancelled = pending(request, jpeg);
        cancelled.queued(750); cancelled.close(); cancelled.queued(750);
        check(cancelled.acquired(request, 34, 800, 900, original) == null, "cancelled result cannot revive on enqueue");
        CameraShareStillReceipt closedBeforeQueue = pending(request, jpeg);
        closedBeforeQueue.close(); closedBeforeQueue.queued(750);
        check(closedBeforeQueue.acquired(request, 34, 800, 900, original) == null, "close-before-queue is terminal");

        ByteBuffer direct = ByteBuffer.allocateDirect(jpeg.length + 12);
        direct.position(7); direct.put(jpeg); direct.limit(7 + jpeg.length); direct.position(7);
        direct.mark();
        CameraShareStillReceipt sliced = pending(request, jpeg); sliced.queued(750);
        check(sliced.acquired(request, 34, 800, 900, direct.slice().asReadOnlyBuffer()) != null, "sliced direct plane with nonzero offset");
        direct.reset(); check(direct.position() == 7 && direct.limit() == 7 + jpeg.length, "direct parent unchanged");

        byte[] mutable = jpeg.clone();
        CameraShareStillReceipt snapshot = pending(request, mutable); snapshot.queued(750);
        mutable[mutable.length - 3] ^= 1;
        check(snapshot.acquired(request, 34, 800, 900, ByteBuffer.wrap(mutable)) == null, "source buffer later mutation cannot alter expectation");
        check(snapshot.acquired(request, 34, 800, 900, original) != null, "expectation is original authenticated content");

        CameraShareStillReceipt raced = pending(request, jpeg); raced.queued(750);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        Thread[] threads = new Thread[12];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (raced.acquired(request, 34, 800, 900, ByteBuffer.wrap(jpeg)) != null) wins.incrementAndGet();
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(wins.get() == 1, "concurrent native callbacks have one completion");
        System.out.println("camera-still-receipt-checks: PASS " + checks + " assertions");
    }
}
