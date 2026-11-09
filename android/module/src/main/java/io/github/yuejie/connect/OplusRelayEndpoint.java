package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.lang.reflect.Method;

/** Uses the original phone/tablet data-relay Intent factory, not a mirage/PC window command. */
final class OplusRelayEndpoint extends AppRelayEndpoint {
    private final Method createIntent;
    OplusRelayEndpoint(Context context, ClassLoader loader, Documents documents) throws ReflectiveOperationException {
        super(context, loader, documents);
        if (!"com.oplus.linker".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        Class<?> utils = Class.forName("com.oplus.synergy.utils.RelayUtils", false, loader);
        createIntent = utils.getMethod("createDLIntent", String.class, String.class);
    }
    @Override void checkSupported(AppRelayRequest request, String target) throws Exception {
        OplusRelayPermission.checkConsent(context, loader);
        if (request.kind() == AppRelayRequest.Kind.APP || request.kind() == AppRelayRequest.Kind.RICH_TEXT)
            OplusRelayPermission.checkAbility(loader, target);
    }
    @Override Intent intent(AppRelayRequest request, String target, Uri local) throws Exception {
        if (request.kind() == AppRelayRequest.Kind.BROWSER && !BrowserRelayOffer.ownsPackage(request.sourcePackage()))
            return ((Intent) createIntent.invoke(null, request.uri(), target)).addCategory(Intent.CATEGORY_BROWSABLE);
        if (request.kind() == AppRelayRequest.Kind.APP)
            return (Intent) createIntent.invoke(null, request.uri(), target);
        return super.intent(request, target, local);
    }
}
