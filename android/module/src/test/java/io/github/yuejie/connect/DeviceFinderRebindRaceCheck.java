package io.github.yuejie.connect;

/** One projection change only: asynchronous cleanup and cancellation cannot require a later rebind. */
public final class DeviceFinderRebindRaceCheck {
    private static int checks;
    private static final class Backend implements DeviceFinderController.Backend {
        boolean playing, holdRelease, released = true;
        int starts;
        public void start(String name, String session) { starts++; playing = true; released = false; }
        public void stop() { playing = false; if (!holdRelease) released = true; }
        public boolean isRinging() { return playing; }
        public boolean isReleased() { return released; }
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (DeviceFinderController.Failure failure) { check(code.equals(failure.code), code + " versus " + failure.code); }
    }
    public static void main(String[] args) throws Exception {
        Backend backend = new Backend();
        DeviceFinderController controller = new DeviceFinderController(backend, () -> 100);
        controller.bind("session-a");
        controller.execute("session-a", "1", "start", 1000, "synthetic-a");
        backend.holdRelease = true;
        rejects("cleanup_pending", () -> controller.bind("session-b"));
        rejects("stale_session", () -> controller.execute("session-a", "late", "start", 1000, "synthetic-a"));
        rejects("cleanup_pending", () -> controller.execute("session-b", "1", "start", 1000, "synthetic-b"));

        // Wire request numbers are scoped to session and may restart at 1. Cancel B:1 while A:1
        // still owns resources, then let the old cleanup finish without another projection update.
        try { controller.cancel("session-b", "1"); }
        catch (DeviceFinderController.Failure pending) { check("cleanup_pending".equals(pending.code), "unexpected cancellation error"); }
        backend.holdRelease = false;
        controller.tick();
        rejects("cancelled", () -> controller.execute("session-b", "1", "start", 1000, "synthetic-b"));
        check(backend.starts == 1, "cancelled new-session request resurrected after old cleanup");
        check(controller.execute("session-b", "2", "start", 1000, "synthetic-b").ringing(), "new session needed a second bind");
        check(backend.starts == 2, "new session did not execute exactly once");
        controller.close();
        check("request_timeout".equals(DeviceFinderController.terminalFailureCode(new DeviceFinderController.Failure("cleanup_pending"), false)), "pending cleanup leaked at deadline");
        check("stale_session".equals(DeviceFinderController.terminalFailureCode(new DeviceFinderController.Failure("cleanup_pending"), true)), "pending cleanup leaked after close");
        check("vibration_permission_required".equals(DeviceFinderController.terminalFailureCode(new DeviceFinderController.Failure("vibration_permission_required"), false)), "permission cause collapsed");
        check("finder_execution_failed".equals(DeviceFinderController.terminalFailureCode(new Exception("synthetic internal error"), false)), "unexpected exception details leaked");
        System.out.println("PASS device-finder rebind/cancel race: " + checks + " assertions");
    }
}
