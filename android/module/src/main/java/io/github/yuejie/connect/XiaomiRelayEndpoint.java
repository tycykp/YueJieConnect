package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Mirrors the sample's y0.B/J/K extras without its success-on-queued-file fallback. */
final class XiaomiRelayEndpoint extends AppRelayEndpoint {
    private final java.lang.reflect.Method enabled;
    XiaomiRelayEndpoint(Context context, ClassLoader loader, Documents documents) throws ReflectiveOperationException {
        super(context, loader, documents);
        if (!"com.xiaomi.mirror".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        enabled = Class.forName("y3.p", false, loader).getMethod("e");
    }
    @Override void checkSupported(AppRelayRequest request, String target) throws Exception {
        if (!Boolean.TRUE.equals(enabled.invoke(null))) throw new IllegalStateException("native_relay_disabled");
    }
    @Override Intent intent(AppRelayRequest request, String target, Uri local) throws Exception {
        Intent intent = super.intent(request, target, local);
        intent.putExtra("start_package", "com.xiaomi.mirror");
        switch (request.kind()) {
            case APP -> intent.putExtra("relay_intent_uri", request.uri());
            case BROWSER -> { intent.putExtra("relay_browser_url_type", 0); intent.putExtra("relay_browser_url", request.uri()); }
            case DOCUMENT -> {
                intent.putExtra("relay_file_progress_type", request.progressType());
                if (request.progressType() == 3) intent.putExtra("relay_file_progress", request.progressText());
                else intent.putExtra("relay_file_progress", request.progress());
            }
            case HOME -> { }
        }
        return intent;
    }
}
