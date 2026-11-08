package io.github.qiangjian.connect;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** ColorOS's actual phone-UID InCallUI source; retains its real Telecom Binder and native behavior. */
final class OplusTelephonyInCallAdapter implements AutoCloseable {
    static final String PACKAGE = "com.android.incallui";
    static final String SERVICE = "com.android.incallui.OplusInCallServiceImpl";
    private final Supplier<TelephonySyncCalls> endpoint;
    private final List<HookHandle> hooks = new ArrayList<>();
    private volatile InCallService nativeService;
    OplusTelephonyInCallAdapter(Supplier<TelephonySyncCalls> endpoint) { this.endpoint = Objects.requireNonNull(endpoint); }
    void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        if (!hooks.isEmpty()) throw new IllegalStateException("already_installed");
        Class<?> type = Class.forName(SERVICE, false, loader);
        if (!InCallService.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_incall_service");
        // Validate exact declarations before installing. Never intercept an inherited framework Service method.
        Method bind = type.getDeclaredMethod("onBind", Intent.class), unbind = type.getDeclaredMethod("onUnbind", Intent.class);
        Method added = type.getDeclaredMethod("onCallAdded", Call.class), removed = type.getDeclaredMethod("onCallRemoved", Call.class);
        Method destroy = type.getDeclaredMethod("onDestroy");
        try {
            hooks.add(module.hook(bind).setId("qj.telephony.oplus.incall.bind").intercept(chain -> {
                Object value = chain.proceed();
                nativeService = (InCallService) chain.getThisObject(); refresh(); return value;
            }));
            hooks.add(module.hook(added).setId("qj.telephony.oplus.incall.add").intercept(chain -> {
                Object value = chain.proceed();
                InCallService service = (InCallService) chain.getThisObject(); nativeService = service;
                TelephonySyncCalls target = target(); if (target != null) target.added(service, (Call) chain.getArg(0));
                return value;
            }));
            hooks.add(module.hook(removed).setId("qj.telephony.oplus.incall.remove").intercept(chain -> {
                Object value = chain.proceed(); TelephonySyncCalls target = target();
                if (target != null) target.removed((InCallService) chain.getThisObject(), (Call) chain.getArg(0));
                return value;
            }));
            for (Method end : new Method[] {unbind, destroy}) {
                hooks.add(module.hook(end).setId("qj.telephony.oplus.incall." + end.getName()).intercept(chain -> {
                    Object value = chain.proceed();
                    InCallService service = (InCallService) chain.getThisObject();
                    if (nativeService == service) nativeService = null;
                    TelephonySyncCalls target = target(); if (target != null) target.destroyed(service);
                    return value;
                }));
            }
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    /** Call after replacing the authenticated session endpoint; no new call or role request. */
    void refresh() {
        InCallService source = nativeService; TelephonySyncCalls target = target();
        if (source != null && target != null) target.attach(source);
    }
    private TelephonySyncCalls target() {
        try { return endpoint.get(); } catch (RuntimeException ignored) { return null; }
    }
    @Override public void close() {
        nativeService = null; for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        TelephonySyncCalls target = target(); if (target != null) target.close();
    }
}
