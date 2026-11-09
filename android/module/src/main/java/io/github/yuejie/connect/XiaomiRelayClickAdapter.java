package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.util.ArrayList;
import java.util.List;

/** Replaces only a proven gateway DATA slot's real Mirror icon click. */
final class XiaomiRelayClickAdapter implements AutoCloseable {
    private final XposedModule module;
    private final ClassLoader loader;
    private final AppRelayNativeDelegate delegate;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    XiaomiRelayClickAdapter(XposedModule module, ClassLoader loader, AppRelayNativeDelegate delegate) {
        this.module = module; this.loader = loader; this.delegate = delegate;
    }
    void install() throws Exception {
        Class<?> remote = Class.forName("com.xiaomi.mirror.relay.y0", false, loader);
        var slot = remote.getField("k");
        var manager = remote.getMethod("m");
        try {
            // scheduleRelayApp precedes the stock terminal lookup. A gateway ID has no
            // native P2P terminal, so the later k() hook would never see the click.
            hooks.add(module.hook(remote.getDeclaredMethod("a", remote, String.class, String.class, String.class)).setId("yj.xiaomi.relay.native-sync").intercept(chain -> {
                Object owner = chain.getArg(0);
                String nativeId = (String)chain.getArg(3);
                AppRelayPeer peer = delegate.selected(nativeId);
                if (closed || peer == null) return chain.proceed();
                Object selected = slot.get(owner);
                String pkg = (String)chain.getArg(1);
                if (selected == null || !nativeId.equals(selected.getClass().getField("a").get(selected))
                        || !pkg.equals(XiaomiRelayCatalog.packageOfProcess((String)selected.getClass().getField("c").get(selected)))
                        || delegate.candidate(peer, pkg) == null) return null;
                AppRelayEvents.emit(AppRelayEvents.Event.NATIVE_CLICK, "xiaomi-click", AppRelayEvents.Outcome.PENDING);
                delegate.requestSync(peer, pkg).whenComplete((result, error) -> main.post(() -> {
                    if (closed || !peer.equals(delegate.selected(nativeId))) return;
                    try {
                        Object now = slot.get(owner);
                        if (now == null || !nativeId.equals(now.getClass().getField("a").get(now))
                                || !pkg.equals(XiaomiRelayCatalog.packageOfProcess((String)now.getClass().getField("c").get(now)))) return;
                        Object ui = manager.invoke(owner);
                        boolean complete = error == null && result != null && result.state() == AppRelayTasks.State.COMPLETED;
                        if (complete) ui.getClass().getMethod("f").invoke(ui);
                        ui.getClass().getMethod("d", int.class).invoke(ui, complete ? 1 : 0);
                        if (result != null) AppRelayEvents.emit(AppRelayEvents.Event.NATIVE_CLICK_RESULT, result.requestId(), AppRelayEvents.outcome(result));
                    } catch (Exception invalid) { android.util.Log.w("YueJieRelay", "native_sync_receipt_failed"); }
                }));
                return null;
            }));
        } catch (Exception failure) { close(); throw failure; }
    }
    @Override public void close() { closed = true; for (HookHandle hook : hooks) hook.unhook(); hooks.clear(); }
}
