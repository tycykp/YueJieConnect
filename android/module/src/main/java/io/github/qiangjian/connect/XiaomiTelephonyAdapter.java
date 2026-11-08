package io.github.qiangjian.connect;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Mirrors the sample's actual CallStateListener, without inventing a per-call ID from aggregate state. */
final class XiaomiTelephonyAdapter implements AutoCloseable {
    record AggregateState(int nativeState, String state) {}
    private final Context context;
    private final BooleanSupplier authorized;
    private final Consumer<AggregateState> events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Listener listener = new Listener();
    private TelephonyManager manager;
    private boolean registered;
    private final class Listener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) {
            if (closed.get() || !authorized.getAsBoolean()) return;
            String value = switch (state) {
                case TelephonyManager.CALL_STATE_IDLE -> "idle";
                case TelephonyManager.CALL_STATE_RINGING -> "ringing";
                case TelephonyManager.CALL_STATE_OFFHOOK -> "in_use";
                default -> null;
            };
            if (value != null) try { events.accept(new AggregateState(state, value)); } catch (RuntimeException ignored) { }
        }
    }
    XiaomiTelephonyAdapter(Context context, BooleanSupplier authorized, Consumer<AggregateState> events) {
        this.context = context; this.authorized = authorized; this.events = events;
    }
    /** Call on main. The sample's ICallRelayService send/listener methods are empty, so expose no controls here. */
    void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main_thread_required");
        if (closed.get() || registered) return;
        if (!authorized.getAsBoolean() || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("phone_state_permission_missing");
        manager = context.getSystemService(TelephonyManager.class);
        if (manager == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
            throw new IllegalStateException("telephony_not_available");
        manager.registerTelephonyCallback(main::post, listener);
        registered = true;
    }
    @Override public void close() {
        closed.set(true);
        main.post(() -> { if (registered) { manager.unregisterTelephonyCallback(listener); registered = false; } });
    }
}
