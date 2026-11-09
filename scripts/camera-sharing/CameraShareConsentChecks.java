package io.github.yuejie.connect;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** New per-native-request consent checks; no platform grant, Activity or Camera2 is simulated as real. */
public final class CameraShareConsentChecks {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void rejects(Runnable run, String label) {
        try { run.run(); throw new AssertionError(label); }
        catch (IllegalStateException | IllegalArgumentException | SecurityException expected) { checks++; }
    }
    private static CameraShareConsentRegistry.Scope scope(String session, long stream, String camera) {
        return new CameraShareConsentRegistry.Scope(session, "actual-pad", stream, camera);
    }
    public static void main(String[] args) throws Exception {
        CameraShareConsentRegistry registry = new CameraShareConsentRegistry();
        CameraShareConsentRegistry.Scope scope = scope("session", 1, "physical-rear");
        AtomicBoolean current = new AtomicBoolean(true);
        String handle = registry.arm(scope, 100, 120_100, current::get);
        check(handle.matches("[0-9a-f]{64}"), "opaque 256-bit handle format");
        check(!handle.contains("physical") && !handle.contains("session"), "handle contains no request identity");
        check(!registry.active(handle, scope, 101), "arming never grants capture");
        check(registry.activate(handle, 102) == null, "CAMERA/platform readiness cannot replace per-use approval");
        check(registry.pending(handle, 102) != null, "early activate does not consume original UI request");
        check(registry.approve(handle, 103), "one explicit current approval accepted");
        check(!registry.approve(handle, 104), "duplicate UI approval cannot refresh deadline");
        check(!registry.active(handle, scope, 104), "approval itself is not actual FGS activation");
        check(registry.activate(handle, 105) != null, "approved handle activates once");
        check(registry.activate(handle, 106) == null, "activation cannot replay");
        check(registry.active(handle, scope, 130_000), "active native stream uses current gate, not old permission-prompt deadline");
        check(!registry.active(handle, scope("other-session", 1, "physical-rear"), 107), "session isolation");
        check(!registry.active(handle, scope("session", 2, "physical-rear"), 107), "stream isolation");
        check(!registry.active(handle, scope("session", 1, "physical-front"), 107), "physical camera isolation");
        current.set(false);
        check(!registry.active(handle, scope, 108), "native request revocation immediately blocks source");
        current.set(true);
        check(!registry.active(handle, scope, 109), "same alias/grant recovery never revives old handle");
        rejects(() -> registry.arm(scope, 110, 1000, () -> true), "same session stream cannot re-arm to evade consent replay");
        rejects(() -> registry.arm(scope("s", 1, "c"), 1, 120_002, () -> true), "permission deadline bound");
        rejects(() -> registry.arm(scope("s", 1, "c"), 2, 2, () -> true), "expired native request");
        rejects(() -> registry.arm(scope("s", 1, "c"), 1, 2, () -> false), "missing native request/grants");
        String expired = registry.arm(scope("session", 2, "physical-rear"), 200, 300, () -> true);
        check(!registry.approve(expired, 300), "prompt deadline is exclusive");
        String late = registry.arm(scope("session", 3, "physical-rear"), 400, 10_000, () -> true);
        check(registry.approve(late, 401), "late activation fixture approval");
        check(registry.activate(late, 5401) == null, "approval expires in five seconds");
        check(!registry.approve(late, 5402), "expired approval cannot be refreshed by stale UI");
        String cancelled = registry.arm(scope("session", 4, "physical-rear"), 6000, 10_000, () -> true);
        registry.cancel(cancelled);
        check(!registry.approve(cancelled, 6001) && registry.activate(cancelled, 6002) == null, "cancel before approval is terminal");
        String invalidated = registry.arm(scope("session", 5, "physical-rear"), 6000, 10_000, () -> true);
        registry.sessionInvalidated("session");
        check(!registry.approve(invalidated, 6001), "session invalidation revokes pending UI handles");

        CameraShareConsentRegistry raced = new CameraShareConsentRegistry();
        String once = raced.arm(scope, 0, 1000, () -> true);
        AtomicInteger approvals = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (raced.approve(once, 1)) approvals.incrementAndGet(); }); threads[i].start();
        }
        start.countDown(); for (Thread thread : threads) thread.join();
        check(approvals.get() == 1, "concurrent UI completion has one approval");
        raced.close(); check(raced.activate(once, 2) == null, "controller death/close revokes approved request");

        CameraShareConsentRegistry capacity = new CameraShareConsentRegistry(); Set<String> handles = new HashSet<>();
        for (int i = 1; i <= 4; i++) handles.add(capacity.arm(scope("s", i, "c"), 0, 1000, () -> true));
        check(handles.size() == 4, "independent native requests have distinct opaque handles");
        rejects(() -> capacity.arm(scope("s", 5, "c"), 0, 1000, () -> true), "bounded live permission prompts");
        for (String token : handles) capacity.cancel(token);
        for (int i = 5; i <= 128; i++) { String token = capacity.arm(scope("s", i, "c"), 0, 1000, () -> true); capacity.cancel(token); }
        rejects(() -> capacity.arm(scope("s", 129, "c"), 0, 1000, () -> true), "bounded replay history does not evict old requests");
        rejects(() -> capacity.arm(scope("s", 1, "c"), 0, 1000, () -> true), "old session stream remains a tombstone at capacity");
        System.out.println("camera-per-use-consent-checks: PASS " + checks + " assertions");
    }
}
