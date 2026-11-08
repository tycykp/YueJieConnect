package android.os;
public final class Handler {
    private record Task(Runnable work, long when) {}
    private static final java.util.List<Task> QUEUE = new java.util.ArrayList<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable work) { return postDelayed(work, 0); }
    public boolean postDelayed(Runnable work, long delay) { QUEUE.add(new Task(work, SystemClock.now + delay)); return true; }
    public void removeCallbacks(Runnable work) { QUEUE.removeIf(task -> task.work == work); }
    public static void reset() { QUEUE.clear(); SystemClock.now = 0; }
    public static void advance(long ms) { SystemClock.now += ms; runReady(); }
    public static void runReady() {
        for (int i = 0; i < 1000; i++) {
            Task next = QUEUE.stream().filter(t -> t.when <= SystemClock.now).findFirst().orElse(null);
            if (next == null) return; QUEUE.remove(next); next.work.run();
        }
        throw new AssertionError("unbounded native dispatch");
    }
}
