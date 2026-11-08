package io.github.qiangjian.connect;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Observes Mirror's PC notification export. The sampled G0/i0 path is not an Android phone/pad source. */
final class XiaomiNotificationAdapter implements AutoCloseable {
    /** Must bind the actual native terminal ID to this authenticated session, never by type/name/IP. */
    interface NativeRoute { boolean matches(String nativeDeviceId, String peer, String session); }
    private static final String LISTENER = "com.xiaomi.mirror.notification.MirrorNotificationListenerService";
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final NotificationSyncRuntime.Grants grants;
    private final NativeRoute route;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ThreadLocal<Capture> capture = new ThreadLocal<>();
    private record Capture(NotificationListenerService listener, StatusBarNotification notification) {}
    private volatile NotificationListenerService listener;
    private volatile boolean connected, closed;
    private Context context;
    private NotificationSyncRuntime runtime;
    private NotificationSyncEndpoint endpoint;
    private BridgeClient.Subscription projection;
    private Method connectionGet, notificationTerminal, nativeDeviceId;

    XiaomiNotificationAdapter(XposedModule module, BridgeClient client, ClassLoader loader,
            BooleanSupplier allowed, NotificationSyncRuntime.Grants grants) {
        this(module, client, loader, allowed, grants, (nativeId, peer, session) -> false);
    }
    XiaomiNotificationAdapter(XposedModule module, BridgeClient client, ClassLoader loader,
            BooleanSupplier allowed, NotificationSyncRuntime.Grants grants, NativeRoute route) {
        this.module = module; this.client = client; this.loader = loader; this.allowed = allowed; this.grants = grants;
        this.route = route;
    }
    void install(Context host) throws ReflectiveOperationException {
        if (runtime != null || closed || host == null || !"com.xiaomi.mirror".equals(host.getPackageName())
                || !"com.xiaomi.mirror".equals(Application.getProcessName()) || !allowed.getAsBoolean())
            throw new SecurityException("notification_invalid_executor_context");
        context = host;
        Class<?> nativeListener = Class.forName(LISTENER, false, loader);
        Class<?> nativePost = Class.forName("com.xiaomi.mirror.message.NotificationPostMessage", false, loader);
        Class<?> manager = Class.forName("com.xiaomi.mirror.message.MessageManagerImpl", false, loader);
        Class<?> connection = Class.forName("com.xiaomi.mirror.connection.G", false, loader);
        connectionGet = connection.getMethod("J"); notificationTerminal = connection.getMethod("G0");
        nativeDeviceId = Class.forName("com.xiaomi.mirror.k0", false, loader).getMethod("h");
        runtime = new NotificationSyncRuntime(context, new NotificationSyncBridgeTransport(client, allowed), grants,
                new NotificationSyncRuntime.SourceAccess() {
                    @Override public NotificationListenerService listener() { return listener; }
                    @Override public boolean granted() { return available(); }
                });
        endpoint = new NotificationSyncEndpoint(runtime, grants);
        try {
            hooks.add(module.hook(NotificationListenerService.class.getMethod("onListenerConnected"))
                    .setId("qj.xiaomi.notification.connected").intercept(chain -> {
                        Object result = chain.proceed();
                        if (!closed && allowed.getAsBoolean() && nativeListener.isInstance(chain.getThisObject())) {
                            listener = (NotificationListenerService) chain.getThisObject(); connected = true;
                        }
                        return result;
                    }));
            hooks.add(module.hook(NotificationListenerService.class.getMethod("onListenerDisconnected"))
                    .setId("qj.xiaomi.notification.disconnected").intercept(chain -> {
                        try { return chain.proceed(); }
                        finally { if (listener == chain.getThisObject()) { connected = false; listener = null; endpoint.authorizationChanged(); } }
                    }));
            hooks.add(module.hook(nativeListener.getMethod("onNotificationPosted", StatusBarNotification.class))
                    .setId("qj.xiaomi.notification.source").intercept(chain -> {
                        StatusBarNotification notification = (StatusBarNotification) chain.getArg(0);
                        if (!eligible(notification)) return chain.proceed();
                        Capture previous = capture.get();
                        capture.set(new Capture((NotificationListenerService) chain.getThisObject(), notification));
                        try { return chain.proceed(); }
                        finally { if (previous == null) capture.remove(); else capture.set(previous); }
                    }));
            hooks.add(module.hook(manager.getMethod("sendNotificationPostMessage", nativePost))
                    .setId("qj.xiaomi.notification.send").intercept(chain -> {
                        Capture current = capture.get();
                        if (current != null && eligible(current.notification)) {
                            try {
                                Object nativeMessage = chain.getArg(0);
                                String expected = current.notification.getPackageName() + "~" + current.notification.getId();
                                if (!expected.equals(XiaomiNotificationCodec.nativeId(nativeMessage))) throw new IllegalArgumentException("notification_native_target_mismatch");
                                NotificationSyncCodec.Post post = XiaomiNotificationCodec.post(nativeMessage, "native", 1, NotificationSyncRuntime::nativeImage).post();
                                listener = current.listener; connected = true;
                                runtime.posted(current.notification, post);
                            } catch (ReflectiveOperationException | RuntimeException invalid) { log("notification_native_payload_rejected"); }
                        }
                        return chain.proceed(); // Never fabricate Mirror's send callback or suppress existing native peers.
                    }));
            hooks.add(module.hook(nativeListener.getMethod("onNotificationRemoved", StatusBarNotification.class))
                    .setId("qj.xiaomi.notification.remove").intercept(chain -> {
                        Object result = chain.proceed();
                        StatusBarNotification notification = (StatusBarNotification) chain.getArg(0);
                        if (!closed && allowed.getAsBoolean() && notification != null && grants.allowsPackage(notification.getPackageName())
                                && notification.getUser().equals(android.os.Process.myUserHandle())) runtime.removed(notification);
                        return result;
                    }));
            hooks.add(module.hook(nativeListener.getMethod("onUnbind", Intent.class))
                    .setId("qj.xiaomi.notification.unbind").intercept(chain -> {
                        try { return chain.proceed(); }
                        finally { if (listener == chain.getThisObject()) { connected = false; listener = null; endpoint.authorizationChanged(); } }
                    }));
            projection = client.addProjectionListener(p -> endpoint.onProjection(p == null ? "" : p.optString("id"), p == null ? "" : p.optString("session_id")));
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    private boolean eligible(StatusBarNotification n) {
        JSONObject p = client.projection();
        return !closed && allowed.getAsBoolean() && n != null && n.getNotification() != null
                && grants.allowsPackage(n.getPackageName()) && n.getUser().equals(android.os.Process.myUserHandle())
                && NotificationSyncBridgeTransport.supports(p, NotificationSyncCodec.OP_POST)
                && nativeRoute(p)
                && grants.allows(p.optString("id"), p.optString("session_id"), NotificationSyncCodec.OP_POST);
    }
    private boolean nativeRoute(JSONObject projection) {
        if (projection == null || route == null || connectionGet == null) return false;
        try {
            Object terminal = notificationTerminal.invoke(connectionGet.invoke(null));
            if (terminal == null) return false;
            Object id = nativeDeviceId.invoke(terminal);
            return id instanceof String value && !value.isEmpty()
                    && route.matches(value, projection.optString("id"), projection.optString("session_id"));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
    boolean available() {
        return !closed && allowed.getAsBoolean() && connected && listener != null
                && nativeRoute(client.projection())
                && context.checkSelfPermission("android.permission.STATUS_BAR_SERVICE") == PackageManager.PERMISSION_GRANTED;
    }
    /** Shared owner registers ONLY notification_action in the already authenticated Mirror process when available(). */
    BridgeClient.VendorExecutor actionExecutor() {
        if (runtime == null) throw new IllegalStateException("notification_not_installed");
        return new BridgeClient.VendorExecutor() {
            @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
                try {
                    if (!available() || !NotificationSyncCodec.OP_ACTION.equals(task.optString("operation"))) throw new IllegalStateException("notification_source_unavailable");
                    endpoint.execute(task, completion);
                } catch (RuntimeException failure) { completion.fail("notification_request_rejected"); }
            }
            @Override public void cancel(String session, String request) { endpoint.cancel(session, request); }
            @Override public void sessionChanged() { endpoint.authorizationChanged(); }
        };
    }
    void authorizationChanged() { if (endpoint != null) endpoint.authorizationChanged(); }
    long verifiedTransfers() { return runtime == null ? 0 : runtime.verifiedTransfers(); }
    long rejectedTransfers() { return runtime == null ? 0 : runtime.rejectedTransfers(); }
    private void log(String code) { module.log(android.util.Log.WARN, "QiangJian", code); }
    @Override public void close() {
        if (closed) return; closed = true; connected = false; listener = null;
        if (projection != null) { projection.close(); projection = null; }
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        if (endpoint != null) endpoint.close();
        if (runtime != null) runtime.close();
    }
}
