package io.github.yuejie.connect;

/** The LSPosed-provided APK is an installed asset, not a running companion process. */
final class NativeLibrary {
    private static String apk;
    static void configure(String path) { apk = path; }
    static void load() {
        if (apk == null) System.loadLibrary("yj_jni");
        else System.load(apk + "!/lib/arm64-v8a/libyj_jni.so");
    }
}
