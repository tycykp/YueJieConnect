package io.github.qiangjian.connect;

import android.content.ClipboardManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Owns only a native clipboard listener for the explicitly authorized projection's lifetime.
 * It never installs a fake terminal or changes the OEM singleton's existing device/listener. */
final class XiaomiClipboardSourceLease implements AutoCloseable {
    private final Method mirror, clipboard, nativeManager;
    private final Field nativeListener;
    private final Constructor<?> listenerConstructor;
    private ClipboardManager registeredOn;
    private ClipboardManager.OnPrimaryClipChangedListener owned;

    XiaomiClipboardSourceLease(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> app = Class.forName("com.xiaomi.mirror.Mirror", false, loader);
        Class<?> manager = Class.forName("A3.b", false, loader);
        Class<?> listener = Class.forName("A3.b$c", false, loader);
        mirror = app.getMethod("C"); clipboard = app.getMethod("t");
        nativeManager = manager.getMethod("b"); nativeListener = manager.getDeclaredField("a");
        nativeListener.setAccessible(true);
        listenerConstructor = listener.getDeclaredConstructor(); listenerConstructor.setAccessible(true);
        if (!ClipboardManager.OnPrimaryClipChangedListener.class.isAssignableFrom(listener))
            throw new IllegalStateException("native_listener_unavailable");
    }

    void update(boolean authorized) throws ReflectiveOperationException {
        if (!authorized) { close(); return; }
        Object app = mirror.invoke(null), manager = nativeManager.invoke(null);
        ClipboardManager target = app == null ? null : (ClipboardManager) clipboard.invoke(app);
        // The native PC/cast lifecycle might already own a listener. Leave it and its peer identity
        // intact. S() also debounces callbacks, covering the transition until the next update.
        if (target == null || manager == null || nativeListener.get(manager) != null) { close(); return; }
        if (owned != null && registeredOn == target) return;
        close();
        ClipboardManager.OnPrimaryClipChangedListener listener =
                (ClipboardManager.OnPrimaryClipChangedListener) listenerConstructor.newInstance();
        target.addPrimaryClipChangedListener(listener);
        owned = listener; registeredOn = target;
        // No initial read or synthetic callback. Only a real subsequent platform copy reaches
        // A3.b$c -> V3.V.S/m0 -> MessageManagerImpl.broadcastAll -> the clipboard adapter.
    }

    @Override public void close() {
        if (owned != null) {
            registeredOn.removePrimaryClipChangedListener(owned);
            owned = null; registeredOn = null;
        }
    }
}
