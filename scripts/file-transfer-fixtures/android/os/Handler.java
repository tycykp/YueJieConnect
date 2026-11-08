package android.os;
/** Isolated synchronous call dispatcher; no Android scheduling claim. */
public final class Handler {
    public Handler(Looper looper) {}
    public boolean post(Runnable runnable) { runnable.run(); return true; }
}
