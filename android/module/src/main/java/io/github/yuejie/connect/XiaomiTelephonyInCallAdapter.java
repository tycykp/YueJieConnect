package io.github.yuejie.connect;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Actual HyperOS InCallUI service lifecycle, confirmed in the tablet's original APK. */
final class XiaomiTelephonyInCallAdapter implements AutoCloseable {
    static final String PACKAGE = "com.android.incallui";
    static final String SERVICE = "com.android.incallui.InCallServiceImpl";
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Supplier<TelephonySyncCalls> endpoint;
    private InCallService current;
    XiaomiTelephonyInCallAdapter(Supplier<TelephonySyncCalls> endpoint) { this.endpoint = endpoint; }
    void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(SERVICE, false, loader);
        if (!InCallService.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_incall_service");
        try {
            hooks.add(module.hook(type.getDeclaredMethod("onBind", Intent.class)).setId("yj.telephony.xiaomi.bind")
                    .intercept(chain -> {
                        Object result = chain.proceed(); current = (InCallService) chain.getThisObject(); refresh(); return result;
                    }));
            hooks.add(module.hook(type.getDeclaredMethod("onCallAdded", Call.class)).setId("yj.telephony.xiaomi.add")
                    .intercept(chain -> {
                        Object result = chain.proceed(); current = (InCallService) chain.getThisObject();
                        TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.added(current, (Call) chain.getArg(0));
                        return result;
                    }));
            hooks.add(module.hook(type.getDeclaredMethod("onCallRemoved", Call.class)).setId("yj.telephony.xiaomi.remove")
                    .intercept(chain -> {
                        Object result = chain.proceed(); TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.removed((InCallService) chain.getThisObject(), (Call) chain.getArg(0));
                        return result;
                    }));
            hooks.add(module.hook(type.getDeclaredMethod("onUnbind", Intent.class)).setId("yj.telephony.xiaomi.unbind")
                    .intercept(chain -> {
                        InCallService source = (InCallService) chain.getThisObject(); TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.destroyed(source);
                        if (current == source) current = null;
                        return chain.proceed();
                    }));
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    /** Call after creating a newly authenticated session endpoint, on main. */
    void refresh() { TelephonySyncCalls target = endpoint.get(); if (target != null && current != null) target.attach(current); }
    @Override public void close() {
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear(); current = null;
        TelephonySyncCalls target = endpoint.get(); if (target != null) target.close();
    }
}
