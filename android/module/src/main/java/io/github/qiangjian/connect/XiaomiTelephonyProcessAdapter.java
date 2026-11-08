package io.github.qiangjian.connect;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.Objects;
import java.util.function.Function;

/** Exact OEM service entry for the shared layer's challenge Binder; this hook is not authentication. */
final class XiaomiTelephonyProcessAdapter {
    static final String SERVICE = "com.android.phone.ModemSmartEngineService";
    static final String ACTION = "io.github.qiangjian.connect.PROVE_XIAOMI_PHONE_PROCESS_V1";
    private XiaomiTelephonyProcessAdapter() {}

    /** proofFactory must return the common module-UID-checked, nonce-bound challenge endpoint. */
    static HookHandle install(XposedModule module, ClassLoader loader, Function<Context, IBinder> proofFactory)
            throws ReflectiveOperationException {
        Objects.requireNonNull(proofFactory);
        Class<?> type = Class.forName(SERVICE, false, loader);
        if (!Service.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_phone_proof_service");
        return module.hook(type.getDeclaredMethod("onBind", Intent.class)).setId("qj.telephony.xiaomi.process.proof")
                .intercept(chain -> {
                    Intent intent = (Intent) chain.getArg(0);
                    if (intent == null || !ACTION.equals(intent.getAction()) || chain.getThisObject().getClass() != type)
                        return chain.proceed();
                    Service service = (Service) chain.getThisObject();
                    if (!"com.android.phone".equals(service.getPackageName())) return null;
                    // Original modem actions/binders are untouched. Only this private bind uses the proof endpoint.
                    return proofFactory.apply(service);
                });
    }
}
