package io.github.yuejie.connect;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.util.function.Function;

/** Private proof transaction on the OS ServiceManager "phone" Binder; no added exported service. */
final class OplusTelephonyProcessAdapter {
    static final String SERVICE_NAME = "phone";
    static final String OWNER = "com.android.phone.PhoneInterfaceManager";
    static final int PROOF_TRANSACTION = 0x00514a50;
    private OplusTelephonyProcessAdapter() {}
    static HookHandle install(XposedModule module, ClassLoader loader, Function<Context, IBinder> proofFactory)
            throws ReflectiveOperationException {
        java.util.Objects.requireNonNull(proofFactory);
        Class<?> type = Class.forName(OWNER, false, loader);
        if (!Binder.class.isAssignableFrom(type)) throw new IllegalStateException("invalid_phone_binder_owner");
        Field app = type.getDeclaredField("mApp"); app.setAccessible(true);
        return module.hook(type.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class))
                .setId("yj.telephony.oplus.phone.process.proof").intercept(chain -> {
                    if ((Integer) chain.getArg(0) != PROOF_TRANSACTION || chain.getThisObject().getClass() != type)
                        return chain.proceed();
                    Object actual = app.get(chain.getThisObject());
                    if (!(actual instanceof Context context) || !"com.android.phone".equals(context.getPackageName()))
                        throw new SecurityException("invalid_phone_proof_host");
                    // The factory must check the actual module UID, private descriptor/nonce and callback.
                    // Dispatch into the same process so the reverse Binder challenge carries its kernel PID.
                    IBinder endpoint = proofFactory.apply(context);
                    if (endpoint == null) throw new SecurityException("phone_proof_not_ready");
                    return endpoint.transact(IBinder.FIRST_CALL_TRANSACTION, (Parcel) chain.getArg(1), (Parcel) chain.getArg(2), (Integer) chain.getArg(3));
                });
    }
}
