package io.github.yuejie.connect;

public final class NativeCore {
    static { System.loadLibrary("yj_jni"); }
    private NativeCore() {}
    private static volatile java.util.function.Supplier<String> batteryReader;
    static void setBatteryReader(java.util.function.Supplier<String> reader) { batteryReader=reader; }
    // Called from a JNI-attached bridge worker. No vendor object crosses this boundary.
    public static boolean hasBatteryReader() { return batteryReader!=null; }
    public static String readBatteryJson() {
        var reader=batteryReader;
        return reader==null?"{}":reader.get();
    }
    public static native String initialize(String identityPath, String localProfile);
    public static native String dispatch(int uid, int pid, boolean owner, int source,
                                         long elapsedMs, String command);
    /** Consumes fd on every return path. The service has checked native caller/stream consent. */
    static native boolean attachBluetooth(int fd,boolean initiator);
    static native boolean attachMedia(int uid,int pid,int source,long elapsedMs,String command,int fd);
}
