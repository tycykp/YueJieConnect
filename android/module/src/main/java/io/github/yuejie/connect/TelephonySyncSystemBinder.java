package io.github.yuejie.connect;

import android.os.IBinder;

/** Bridge app only. No arbitrary service name, caller-supplied Binder or hidden API bypass. */
final class TelephonySyncSystemBinder {
    private TelephonySyncSystemBinder() {}
    static IBinder phoneBinder() {
        try {
            Class.forName("io.github.yuejie.connect.NativeCore", true, TelephonySyncSystemBinder.class.getClassLoader());
            return phoneBinderNative();
        } catch (ClassNotFoundException | LinkageError unavailable) { return null; }
    }
    private static native IBinder phoneBinderNative();
}
