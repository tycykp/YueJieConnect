package io.github.yuejie.connect;

import android.app.KeyguardManager;
import android.content.Context;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Original consent and local 256 ability, queried on a worker with a finite deadline. */
final class OplusRelayPermission {
    static void check(Context context, ClassLoader loader, String pkg) throws Exception {
        checkConsent(context, loader);
        checkAbility(loader, pkg);
    }
    static void checkConsent(Context context, ClassLoader loader) throws Exception {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isDeviceLocked()) throw new IllegalStateException("device_locked");
        Class<?> utils = Class.forName("com.oplus.synergy.utils.RelayUtils", false, loader);
        if (!Boolean.TRUE.equals(utils.getMethod("getSwitchStatus", Context.class, String.class).invoke(null, context, "smart_connection_app_transfer"))
                || !Boolean.TRUE.equals(utils.getMethod("getPeerRelaySwitchStatus", Context.class).invoke(null, context)))
            throw new IllegalStateException("native_relay_disabled");
        Class<?> callbackType = Class.forName("com.oplus.synergy.callback.UserAgreementStatusCallback", false, loader);
        CompletableFuture<Boolean> consent = new CompletableFuture<>();
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "RelayConsent"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null; };
            if ("onResult".equals(method.getName())) consent.complete(args != null && args.length == 1 && Boolean.TRUE.equals(args[0]));
            return null;
        });
        Class.forName("com.oplus.synergy.utils.StartEngineUtils", false, loader)
                .getMethod("queryUserAgreement", Context.class, callbackType).invoke(null, context, callback);
        try {
            if (!consent.get(2000, TimeUnit.MILLISECONDS)) throw new SecurityException("native_user_agreement_required");
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new SecurityException("native_user_agreement_unavailable");
        }
    }
    static void checkAbility(ClassLoader loader, String pkg) throws Exception {
        Class<?> ability = Class.forName("com.oplus.pantaconnect.sdk.connectionservice.ability.Ability", false, loader);
        Object query = ability.getMethod("checkLocalAbility", int.class, String.class).invoke(ability.getMethod("create").invoke(null), 256, pkg);
        if (!(query instanceof CompletableFuture<?> future)) throw new IllegalStateException("ability_query_failed");
        try { if (!Boolean.TRUE.equals(future.get(2000, TimeUnit.MILLISECONDS))) throw new IllegalStateException("target_relay_ability_missing"); }
        catch (java.util.concurrent.TimeoutException timeout) { future.cancel(true); throw new IllegalStateException("ability_query_timeout"); }
    }
    private OplusRelayPermission() {}
}
