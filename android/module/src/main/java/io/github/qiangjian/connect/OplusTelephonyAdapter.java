package io.github.qiangjian.connect;

import android.telecom.Call;
import android.telecom.InCallService;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** OEM lifecycle adapter only. OAF's iPhone/watch provider is not advertised as an Android pad route. */
final class OplusTelephonyAdapter implements AutoCloseable {
    static final String PACKAGE = "com.oplus.nearcomm";
    static final String SERVICE = "com.oplus.nearcomm.call.NearInCallService";
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Supplier<TelephonySyncCalls> endpoint;
    private Class<?> serviceType;
    OplusTelephonyAdapter(Supplier<TelephonySyncCalls> endpoint) { this.endpoint = endpoint; }
    void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(SERVICE, false, loader);
        if (!InCallService.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_incall_service");
        if (!hooks.isEmpty()) throw new IllegalStateException("already_installed");
        try {
            hooks.add(module.hook(type.getMethod("onCallAdded", Call.class)).setId("qj.telephony.oplus.add")
                    .intercept(chain -> {
                        Object value = chain.proceed();
                        TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.added((InCallService) chain.getThisObject(), (Call) chain.getArg(0));
                        return value;
                    }));
            hooks.add(module.hook(type.getMethod("onCallRemoved", Call.class)).setId("qj.telephony.oplus.remove")
                    .intercept(chain -> {
                        Object value = chain.proceed();
                        TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.removed((InCallService) chain.getThisObject(), (Call) chain.getArg(0));
                        return value;
                    }));
            hooks.add(module.hook(type.getMethod("onDestroy")).setId("qj.telephony.oplus.destroy")
                    .intercept(chain -> {
                        TelephonySyncCalls target = endpoint.get();
                        if (target != null) target.destroyed((InCallService) chain.getThisObject());
                        return chain.proceed();
                    }));
            serviceType = type;
            refresh();
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    /** New authenticated session endpoint can attach to an already running native InCallService. */
    void refresh() throws ReflectiveOperationException {
        if (serviceType == null || hooks.isEmpty()) throw new IllegalStateException("adapter_not_installed");
        Object current = serviceType.getMethod("getInstance").invoke(null);
        TelephonySyncCalls target = endpoint.get();
        if (current instanceof InCallService service && target != null) target.attach(service);
    }
    @Override public void close() {
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        serviceType = null;
        TelephonySyncCalls target = endpoint.get();
        if (target != null) target.close();
    }
}
