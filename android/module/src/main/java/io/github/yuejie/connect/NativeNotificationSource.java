package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import io.github.libxposed.api.XposedModule;
import java.util.HashSet;
import java.util.Set;

/** Observes SystemUI's already authorized listener. Never registers a new listener or grants access. */
final class NativeNotificationSource {
    private final BridgeClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NotificationSyncRuntime runtime;
    private final NotificationSyncEndpoint endpoint;
    private volatile NotificationListenerService listener;
    private volatile boolean connected;
    private volatile Set<String> packages = Set.of();
    private volatile long policyUntil;
    private final NotificationSyncRuntime.Grants grants = new NotificationSyncRuntime.Grants() {
        @Override public boolean allows(String peer, String session, String operation) {
            var projection = client.projection();
            return SystemClock.elapsedRealtime() < policyUntil && projection != null
                    && peer.equals(projection.optString("id")) && session.equals(projection.optString("session_id"))
                    && NotificationSyncBridgeTransport.supports(projection, operation);
        }
        @Override public boolean allowsPackage(String pkg) {
            return SystemClock.elapsedRealtime() < policyUntil && packages.contains(pkg);
        }
    };

    NativeNotificationSource(XposedModule module, Context context, ClassLoader loader, BridgeClient client)
            throws ReflectiveOperationException {
        this.client = client;
        Class<?> type = Class.forName(CallerVerifier.phoneSource(context.getPackageManager())==1
                ? "com.android.systemui.statusbar.notification.MiuiNotificationListener"
                : "com.android.systemui.statusbar.NotificationListener", false, loader);
        runtime = new NotificationSyncRuntime(context, new NotificationSyncBridgeTransport(client, () -> connected), grants,
                new NotificationSyncRuntime.SourceAccess() {
                    @Override public NotificationListenerService listener() { return listener; }
                    @Override public boolean granted() { return connected && SystemClock.elapsedRealtime() < policyUntil; }
                });
        endpoint = new NotificationSyncEndpoint(runtime, grants);
        module.hook(type.getMethod("onListenerConnected")).setId("yj.notification.native.connected").intercept(chain -> {
            Object result = chain.proceed();
            if (type.isInstance(chain.getThisObject())) { listener = (NotificationListenerService) chain.getThisObject(); connected = true; }
            return result;
        });
        module.hook(type.getMethod("onListenerDisconnected")).setId("yj.notification.native.disconnected").intercept(chain -> {
            try { return chain.proceed(); }
            finally { if (listener == chain.getThisObject()) { connected = false; listener = null; runtime.accessLost(); } }
        });
        module.hook(type.getMethod("onNotificationPosted", StatusBarNotification.class,
                NotificationListenerService.RankingMap.class)).setId("yj.notification.native.posted").intercept(chain -> {
            Object result = chain.proceed();
            if (connected && listener == chain.getThisObject()) runtime.posted((StatusBarNotification) chain.getArg(0));
            return result;
        });
        module.hook(type.getMethod("onNotificationRemoved", StatusBarNotification.class,
                NotificationListenerService.RankingMap.class, int.class)).setId("yj.notification.native.removed").intercept(chain -> {
            Object result = chain.proceed();
            if (connected && listener == chain.getThisObject()) runtime.removed((StatusBarNotification) chain.getArg(0));
            return result;
        });
        module.hook(Class.forName(NotificationSystemUiProof.SERVICE, false, loader).getMethod("onBind", Intent.class))
                .setId("yj.notification.native.proof").intercept(chain -> {
                    Intent intent = (Intent) chain.getArg(0);
                    return intent != null && NotificationSystemUiProof.ACTION.equals(intent.getAction())
                            ? NotificationSystemUiProof.endpoint(() -> context, () -> true) : chain.proceed();
                });
        client.registerExecutor("notification_action", endpoint);
        client.addProjectionListener(value -> {
            if (value == null) { policyUntil = 0; packages = Set.of(); }
            endpoint.onProjection(value == null ? "" : value.optString("id"), value == null ? "" : value.optString("session_id"));
        });
        main.post(new Runnable() {
            @Override public void run() {
                client.notificationPolicy(value -> {
                    Set<String> selected = new HashSet<>();
                    boolean enabled = value != null && "ok".equals(value.optString("status")) && value.optBoolean("native_enabled");
                    var values = value == null ? null : value.optJSONArray("packages");
                    if (enabled && values != null) for (int i = 0; i < values.length(); i++) selected.add(values.optString(i));
                    if (!packages.equals(selected) || !enabled) runtime.authorizationChanged();
                    packages = Set.copyOf(selected); policyUntil = enabled ? SystemClock.elapsedRealtime() + 6000 : 0;
                });
                main.postDelayed(this, 2000);
            }
        });
    }
}
