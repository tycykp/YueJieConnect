package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Bridges only the authenticated projected peer at the native tablet CellularSynergyManager. */
final class XiaomiNetworkAdapter {
    private final XposedModule module;
    private final ClassLoader loader;
    private final NetworkShareController controller;
    private final Supplier<NetworkShareController.Target> target;
    private final Supplier<NetworkSharePolicy.Consent> consent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, NetworkShareController.Target> managers = new WeakHashMap<>();
    private Method callback;
    private boolean closed;

    XiaomiNetworkAdapter(XposedModule module, ClassLoader loader, NetworkShareController controller,
            Supplier<NetworkShareController.Target> target, Supplier<NetworkSharePolicy.Consent> consent) {
        this.module = module; this.loader = loader; this.controller = controller; this.target = target; this.consent = consent;
    }
    void install() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.miui.circulate.api.protocol.synergy.manager.CellularSynergyManager", false, loader);
        callback = manager.getMethod("onCellularStateChange", String.class, int.class);
        try {
            hooks.add(module.hook(manager.getMethod("getCellularTetherState", String.class)).setId("yj.xiaomi.network.state").intercept(chain -> {
                NetworkShareController.Target t = selected((String) chain.getArg(0));
                if (t == null) return chain.proceed();
                synchronized (managers) { managers.put(chain.getThisObject(), t); }
                NetworkShareController.Status s = controller.status();
                return NetworkSharePolicy.xiaomi(t.equals(s.target()) ? s.phase() : Phase.STOPPED,
                        s.upstream(), controller.ready(t));
            }));
            hooks.add(module.hook(manager.getMethod("connectCellularTether", String.class)).setId("yj.xiaomi.network.connect").intercept(chain -> {
                NetworkShareController.Target t = selected((String) chain.getArg(0));
                if (t == null) return chain.proceed();
                Object receiver = chain.getThisObject();
                synchronized (managers) { managers.put(receiver, t); }
                NetworkSharePolicy.Consent grant = consent.get();
                if (grant == null || !controller.ready(t)) { deliver(receiver, t, 5); return null; }
                controller.start(t, grant.upstream(), grant.meteredAllowed()).whenComplete((v, e) -> {
                    if (e != null) deliver(receiver, t, 5);
                });
                return null;
            }));
            hooks.add(module.hook(manager.getMethod("disconnectCellularTether", String.class)).setId("yj.xiaomi.network.disconnect").intercept(chain -> {
                NetworkShareController.Target t = selected((String) chain.getArg(0));
                if (t == null) return chain.proceed();
                if (t.equals(controller.status().target())) controller.stop();
                return null;
            }));
            hooks.add(module.hook(callback).setId("yj.xiaomi.network.callback").intercept(chain -> {
                NetworkShareController.Target t = selected((String) chain.getArg(0));
                if (t == null) return chain.proceed();
                synchronized (managers) { managers.put(chain.getThisObject(), t); }
                NetworkShareController.Status s = controller.status();
                int actual = NetworkSharePolicy.xiaomi(t.equals(s.target()) ? s.phase() : Phase.STOPPED,
                        s.upstream(), controller.ready(t));
                return chain.proceed(new Object[]{t.id(), actual});
            }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    void onStatus(NetworkShareController.Status status) {
        List<Map.Entry<Object, NetworkShareController.Target>> copy;
        synchronized (managers) { copy = new ArrayList<>(managers.entrySet()); }
        for (Map.Entry<Object, NetworkShareController.Target> entry : copy) {
            if (entry.getValue().equals(status.target())) deliver(entry.getKey(), entry.getValue(),
                    NetworkSharePolicy.xiaomi(status.phase(), status.upstream(), controller.ready(entry.getValue())));
        }
    }
    void close() {
        closed = true; for (HookHandle h : hooks) h.unhook(); hooks.clear();
        synchronized (managers) { managers.clear(); }
    }
    private NetworkShareController.Target selected(String id) {
        NetworkShareController.Target t = target.get();
        return !closed && t != null && t.id().equals(id) ? t : null;
    }
    private void deliver(Object manager, NetworkShareController.Target t, int state) {
        main.post(() -> {
            if (!t.equals(selected(t.id()))) return;
            try { callback.invoke(manager, t.id(), state); } catch (ReflectiveOperationException ignored) { /* no forged callback */ }
        });
    }
}
