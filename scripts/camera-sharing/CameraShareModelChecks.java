package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Focused host checks: no Android runtime, no images, and no rerun of screen codec tests. */
public final class CameraShareModelChecks {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void rejects(Runnable runnable, String label) {
        try { runnable.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        CameraShareConfig config = new CameraShareConfig("front", 1280, 720, 30, 2_000_000);
        List<CameraShareConfig.Mode> modes = new ArrayList<>(List.of(
                new CameraShareConfig.Mode(1280, 720, 15, 30), new CameraShareConfig.Mode(1280, 720, 30, 30)));
        CameraShareConfig.Camera camera = new CameraShareConfig.Camera("front", 0, 90, modes);
        modes.clear();
        check(config.negotiate(List.of(camera)).minFps() == 30, "exact FPS preferred; immutable capability snapshot");
        rejects(() -> config.negotiate(List.of(new CameraShareConfig.Camera("back", 1, 90, camera.modes()))), "camera selection must be real");
        rejects(() -> new CameraShareConfig("front", 1280, 720, 24, 2_000_000).negotiate(List.of(camera)), "no invented fps range");
        rejects(() -> new CameraShareConfig("front", 1280, 721, 30, 2_000_000), "odd dimensions");
        rejects(() -> new CameraShareConfig("front", Integer.MAX_VALUE, 720, 30, 2_000_000), "dimension overflow");
        rejects(() -> new CameraShareConfig("front", 1920, 1920, 30, 2_000_000), "pixel budget");
        rejects(() -> new CameraShareConfig("front", 1280, 720, 60, 2_000_000), "fps budget");
        rejects(() -> new CameraShareLease(" ", "s1"), "empty session");

        CameraShareLease lease = new CameraShareLease("session-a", "1");
        check(!lease.rendered("session-a", "1", 10), "opening is not rendered");
        check(lease.captured(100), "captured frame");
        check(lease.state() == CameraShareLease.State.CAPTURING, "capture alone not streaming");
        check(!lease.rendered("session-a", "1", 100), "capture without transmission is not remote success");
        check(lease.sent(100), "transmitted frame tracked");
        check(!lease.rendered("session-b", "1", 100), "old session rejected");
        check(!lease.rendered("session-a", "2", 100), "other stream rejected");
        check(!lease.rendered("session-a", "1", 101), "future timestamp rejected");
        check(lease.rendered("session-a", "1", 100), "matching render receipt");
        check(lease.state() == CameraShareLease.State.STREAMING, "streaming only after receipt");
        check(!lease.rendered("session-a", "1", 100), "replay rejected");
        check(!lease.captured(100), "capture timestamp replay");
        lease.captured(200); lease.sent(200); lease.dropped(200);
        check(!lease.rendered("session-a", "1", 200), "backpressure frame not acknowledged");
        for (long i = 201; i <= 270; i++) { lease.captured(i); lease.sent(i); }
        check(!lease.rendered("session-a", "1", 201), "receipt window bounded");
        check(lease.rendered("session-a", "1", 270), "latest receipt preserved");
        check(lease.close("session_invalidated"), "first close");
        check(!lease.close("camera_stopped"), "terminal reason is stable");
        check(lease.terminalReason().equals("session_invalidated"), "root cause retained");
        check(!lease.captured(300) && !lease.sent(300) && !lease.matches("session-a", "1"), "closed cannot resurrect");

        CameraShareLease raced = new CameraShareLease("session-c", "2");
        CountDownLatch close = new CountDownLatch(1);
        Thread late = new Thread(() -> {
            try { close.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 10000; i++) { raced.captured(i); raced.sent(i); raced.rendered("session-c", "2", i); }
        });
        late.start(); raced.close("permission_revoked"); close.countDown(); late.join();
        check(raced.state() == CameraShareLease.State.CLOSED, "delayed callbacks after cancellation");
        System.out.println("camera-model-checks: PASS (" + checks + " assertions)");
    }
}
