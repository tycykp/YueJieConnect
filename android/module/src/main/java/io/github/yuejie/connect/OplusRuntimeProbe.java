package io.github.yuejie.connect;

import android.app.Application;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Debug-only native API stimulus, never supplies a profile, identity, confirmation or result. */
final class OplusRuntimeProbe {
    private static final String ACTION = "io.github.yuejie.connect.OPLUS_PROFILE_PROBE";
    private static final String CLIPBOARD_ACTION = "io.github.yuejie.connect.OPLUS_CLIPBOARD_PROBE";
    private final XposedModule module;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HookHandle hook;
    private Context context;
    private BroadcastReceiver receiver;
    private Object manager;
    private boolean closed;
    private Application application;
    private WeakReference<Activity> focused = new WeakReference<>(null);
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity activity) { focused = new WeakReference<>(activity); }
        @Override public void onActivityPaused(Activity activity) { if (focused.get() == activity) focused.clear(); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) { if (focused.get() == activity) focused.clear(); }
    };

    OplusRuntimeProbe(XposedModule module, ClassLoader loader, BooleanSupplier allowed, Supplier<JSONObject> projection) {
        this.module = module; this.loader = loader; this.allowed = allowed; this.projection = projection;
    }

    void install() throws ReflectiveOperationException {
        hook = module.hook(Application.class.getDeclaredMethod("attach", Context.class))
                .setId("yj.oplus.debug.probe").intercept(chain -> {
                    Object result = chain.proceed();
                    Context app = (Context) chain.getArg(0);
                    Application host = (Application) chain.getThisObject();
                    if (Looper.myLooper() == Looper.getMainLooper()) register(app, host);
                    else main.post(() -> register(app, host));
                    return result;
                });
    }

    private void register(Context app, Application host) {
        if (closed || receiver != null || !"com.oplus.linker:app".equals(Application.getProcessName())) return;
        try {
            ApplicationInfo info = app.getPackageManager().getApplicationInfo("io.github.yuejie.connect", 0);
            if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context receiving, Intent intent) {
                    if (ACTION.equals(intent.getAction())) query();
                    else if (CLIPBOARD_ACTION.equals(intent.getAction()) && allowed.getAsBoolean()) {
                        Activity activity = focused.get();
                        // Android can report both null/false for a denied background read; never
                        // mistake that for an empty original clipboard and erase it on restoration.
                        if (activity == null || !activity.hasWindowFocus()) log("clipboard_self_test requires_focused_native_activity");
                        else log("clipboard_self_test " + OplusClipboardSelfTest.run(app, loader));
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            filter.addAction(CLIPBOARD_ACTION);
            app.registerReceiver(receiver, filter, android.Manifest.permission.DUMP, main, Context.RECEIVER_EXPORTED);
            context = app;
            application = host;
            host.registerActivityLifecycleCallbacks(lifecycle);
            log("probe_registered");
        } catch (Exception error) { receiver = null; log("probe_unavailable " + error.getClass().getSimpleName()); }
    }

    private void query() {
        JSONObject p = projection.get();
        if (closed || !allowed.getAsBoolean() || p == null) { log("probe_peer_unavailable"); return; }
        try {
            Class<?> owner = Class.forName("com.oplus.synergy.devicekit.SynergyDeviceManager", false, loader);
            Class<?> callback = Class.forName("com.oplus.synergy.devicekit.callback.ISynergyDeviceInfoCallback", false, loader);
            if (manager == null) manager = owner.getConstructor(Context.class).newInstance(context);
            String expected = p.getString("id");
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "YjOplusProbeCallback";
                };
                if (method.getName().equals("onError")) log("native_query_error code=" + args[0] + " detail=" + args[1]);
                if (method.getName().equals("onResult")) {
                    boolean delivered = false;
                    for (Object item : (List<?>) args[0]) {
                        if (!expected.equals(item.getClass().getMethod("getDeviceId").invoke(item))) continue;
                        Object properties = item.getClass().getMethod("getPropertyList").invoke(item);
                        if (properties instanceof List<?> list) for (Object property : list) {
                            if ("batteryLevel_1280_1".equals(property.getClass().getMethod("getId").invoke(property))) {
                                log("native_battery_received level=" + property.getClass().getMethod("getValue").invoke(property));
                                delivered = true;
                            }
                        }
                    }
                    if (!delivered) log("native_query_no_battery");
                }
                return null;
            });
            log("native_query_started");
            owner.getMethod("queryOriginalDeviceList", List.class, callback).invoke(manager, List.of(expected), listener);
        } catch (Exception error) { log("probe_error " + error.getClass().getSimpleName()); }
    }

    private void log(String message) { Log.i("YueJieOplusTest", message); module.log(Log.INFO, "YueJieOplusTest", message); }

    void close() {
        closed = true;
        if (hook != null) hook.unhook();
        main.post(() -> {
            if (context != null && receiver != null) context.unregisterReceiver(receiver);
            if (application != null) application.unregisterActivityLifecycleCallbacks(lifecycle);
            if (manager != null) try { Method close = manager.getClass().getMethod("close"); close.invoke(manager); }
            catch (ReflectiveOperationException ignored) { /* OEM cleanup best effort. */ }
            receiver = null; manager = null; context = null; application = null; focused.clear();
        });
    }
}
