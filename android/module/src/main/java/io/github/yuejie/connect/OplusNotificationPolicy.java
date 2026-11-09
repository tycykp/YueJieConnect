package io.github.yuejie.connect;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;

/** Reads the OEM phone/pad notification switch through its actual app-platform provider.
 * This is policy evidence only, never a notification-content source or an NLS access grant. */
final class OplusNotificationPolicy implements AutoCloseable {
    enum State { UNSET, ENABLED, DISABLED, UNAVAILABLE }
    private final Context context;
    private final Method read;
    private final ContentObserver observer;
    private volatile boolean closed;
    OplusNotificationPolicy(Context context, ClassLoader loader, Runnable changed) throws ReflectiveOperationException {
        if (context == null || changed == null || !"com.oplus.linker".equals(context.getPackageName())
                || !"com.oplus.linker:app".equals(Application.getProcessName())) throw new SecurityException("notification_invalid_executor_context");
        this.context = context;
        Class<?> settings = Class.forName("com.oplus.linker.synergy.common.settings.DependSettingValueProxy", false, loader);
        read = settings.getMethod("getPadNoticeToggleState", Context.class);
        if (read.getReturnType() != int.class) throw new NoSuchMethodException("notification_native_policy_changed");
        // zd.a is the URI actually used by that getter, not android.provider.Settings.Secure.
        Object provider = Class.forName("zd", true, loader).getField("a").get(null);
        if (!(provider instanceof Uri uri) || !"content".equals(uri.getScheme()) || !"/secure".equals(uri.getPath())
                || !"com.oplus.appplatform.settings".equals(uri.getAuthority())) throw new SecurityException("notification_native_policy_provider_unavailable");
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri changedUri) { if (!closed) changed.run(); }
        };
        context.getContentResolver().registerContentObserver(uri, true, observer);
    }
    State state() {
        if (closed) return State.UNAVAILABLE;
        try {
            return switch ((Integer) read.invoke(null, context)) {
                case -1 -> State.UNSET; case 0 -> State.DISABLED; case 1 -> State.ENABLED; default -> State.UNAVAILABLE;
            };
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return State.UNAVAILABLE; }
    }
    /** OEM's default(-1) is preserved as UNSET, never promoted to explicit notification consent. */
    boolean explicitlyEnabled() { return state() == State.ENABLED; }
    @Override public void close() {
        if (closed) return; closed = true;
        context.getContentResolver().unregisterContentObserver(observer);
    }
}
