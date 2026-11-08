package io.github.qiangjian.connect;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.lang.reflect.Method;

/** Uses the original phone/tablet data-relay Intent factory, not a mirage/PC window command. */
final class OplusRelayEndpoint extends AppRelayEndpoint {
    private final Method createIntent, localAbility, abilityFactory, switchStatus;
    OplusRelayEndpoint(Context context, ClassLoader loader, Documents documents) throws ReflectiveOperationException {
        super(context, loader, documents);
        if (!"com.oplus.linker".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        Class<?> utils = Class.forName("com.oplus.synergy.utils.RelayUtils", false, loader);
        createIntent = utils.getMethod("createDLIntent", String.class, String.class);
        Class<?> ability = Class.forName("com.oplus.pantaconnect.sdk.connectionservice.ability.Ability", false, loader);
        abilityFactory = ability.getMethod("create");
        localAbility = ability.getMethod("checkLocalAbility", int.class, String.class);
        switchStatus = utils.getMethod("getSwitchStatus", Context.class, String.class);
    }
    @Override void checkSupported(AppRelayRequest request, String target) throws Exception {
        if (!Boolean.TRUE.equals(switchStatus.invoke(null, context, "smart_connection_app_transfer")))
            throw new IllegalStateException("native_relay_disabled");
        if (request.kind() == AppRelayRequest.Kind.APP || request.kind() == AppRelayRequest.Kind.RICH_TEXT) {
            Object result = localAbility.invoke(abilityFactory.invoke(null), 256, target);
            if (!(result instanceof java.util.concurrent.CompletableFuture<?> future)) throw new IllegalStateException("ability_query_failed");
            try {
                if (!Boolean.TRUE.equals(future.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS)))
                    throw new IllegalStateException("target_relay_ability_missing");
            } catch (java.util.concurrent.TimeoutException timeout) {
                future.cancel(true); throw new IllegalStateException("ability_query_timeout");
            }
        }
    }
    @Override Intent intent(AppRelayRequest request, String target, Uri local) throws Exception {
        if (request.kind() == AppRelayRequest.Kind.APP || request.kind() == AppRelayRequest.Kind.BROWSER)
            return (Intent) createIntent.invoke(null, request.uri(), target);
        return super.intent(request, target, local);
    }
}
