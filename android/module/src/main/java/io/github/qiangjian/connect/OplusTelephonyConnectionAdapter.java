package io.github.qiangjian.connect;

import android.telecom.Connection;
import android.telecom.ConnectionService;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Actual phone-process ConnectionService lifecycle; native methods always run unchanged. */
final class OplusTelephonyConnectionAdapter implements AutoCloseable {
    static final String SERVICE = "com.android.services.telephony.TelephonyConnectionService";
    private final Supplier<TelephonySyncConnections> endpoint;
    private final List<HookHandle> hooks = new ArrayList<>();
    private volatile ConnectionService current;
    OplusTelephonyConnectionAdapter(Supplier<TelephonySyncConnections> endpoint) { this.endpoint = java.util.Objects.requireNonNull(endpoint); }
    void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (!hooks.isEmpty()) throw new IllegalStateException("already_installed");
        Class<?> type = Class.forName(SERVICE, false, loader);
        if (!ConnectionService.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_connection_service");
        Method create = type.getDeclaredMethod("onCreate"), destroy = type.getDeclaredMethod("onDestroy");
        Method added = type.getDeclaredMethod("onConnectionAdded", Connection.class), removed = type.getDeclaredMethod("onConnectionRemoved", Connection.class);
        try {
            hooks.add(module.hook(create).setId("qj.telephony.oplus.connection.create").intercept(chain -> {
                Object value = chain.proceed(); current = (ConnectionService) chain.getThisObject(); refresh(); return value;
            }));
            hooks.add(module.hook(added).setId("qj.telephony.oplus.connection.add").intercept(chain -> {
                Object value = chain.proceed(); ConnectionService source = (ConnectionService) chain.getThisObject(); current = source;
                TelephonySyncConnections target = target(); if (target != null) target.added(source, (Connection) chain.getArg(0)); return value;
            }));
            hooks.add(module.hook(removed).setId("qj.telephony.oplus.connection.remove").intercept(chain -> {
                Object value = chain.proceed(); TelephonySyncConnections target = target();
                if (target != null) target.removed((ConnectionService) chain.getThisObject(), (Connection) chain.getArg(0)); return value;
            }));
            hooks.add(module.hook(destroy).setId("qj.telephony.oplus.connection.destroy").intercept(chain -> {
                Object value = chain.proceed(); ConnectionService source = (ConnectionService) chain.getThisObject();
                if (current == source) current = null; TelephonySyncConnections target = target(); if (target != null) target.destroyed(source); return value;
            }));
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    void refresh() { TelephonySyncConnections target = target(); ConnectionService source = current; if (target != null && source != null) target.attach(source); }
    private TelephonySyncConnections target() { try { return endpoint.get(); } catch (RuntimeException ignored) { return null; } }
    @Override public void close() { current = null; for (HookHandle hook : hooks) hook.unhook(); hooks.clear(); TelephonySyncConnections target = target(); if (target != null) target.close(); }
}
