package android.os;
public final class SystemClock {
    static long now;
    public static long elapsedRealtime() { return now; }
}
