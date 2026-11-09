package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/** OEM NoiseMaker proxy: native popup, alarm, interruption callbacks and audio restoration. */
final class XiaomiFinderEndpoint implements DeviceFinderController.Backend, AutoCloseable {
    private static final String BASE = "com.miui.circulate.ringfind.runtime.";
    private final Context context;
    private final Object singleton, callback;
    private final Method start, stop, playing, release;
    private final Constructor<?> arguments;
    private boolean registered, initialized;
    private final BroadcastReceiver receiver;

    XiaomiFinderEndpoint(Context context, ClassLoader loader, Runnable interrupted) throws Exception {
        if (!"com.milink.service".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context;
        Class<?> owner = Class.forName(BASE + "impl.b", false, loader);
        Class<?> callbackType = Class.forName(BASE + "impl.a$b", false, loader);
        Class<?> args = Class.forName(BASE + "ui.StartRingArgs", false, loader);
        Object instance = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == owner) {
                field.setAccessible(true);
                if (instance != null) throw new IllegalStateException("ambiguous_finder_singleton");
                instance = field.get(null);
            }
        }
        if (instance == null) throw new IllegalStateException("finder_singleton_missing");
        singleton = instance;
        start = owner.getMethod("k", args);
        stop = owner.getMethod("m");
        playing = owner.getMethod("d");
        release = owner.getMethod("f", callbackType);
        arguments = args.getConstructor(String.class, String.class, String.class);
        callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, values) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == values[0];
                default -> "YueJieFinderCallback";
            };
            interrupted.run();
            return null;
        });
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) { interrupted.run(); }
        };
        // Native popup broadcasts close_by_user. USER_PRESENT closes a lock-screen notification.
        IntentFilter filter = new IntentFilter("com.miui.circulate.ringfind.close_by_user");
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            owner.getMethod("e", Context.class, callbackType).invoke(singleton, context, callback);
            initialized = true;
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (Exception failure) { close(); throw failure; }
    }

    @Override public void start(String peerName, String session) throws Exception {
        // This is the stock phone/pad NoiseMaker, not the Bluetooth earbud finder.
        start.invoke(singleton, arguments.newInstance("已配对设备", peerName, session));
    }
    @Override public boolean isRinging() throws Exception { return Boolean.TRUE.equals(playing.invoke(singleton)); }
    @Override public void stop() throws Exception { stop.invoke(singleton); }
    @Override public void close() throws Exception {
        try { if (registered) { context.unregisterReceiver(receiver); registered = false; } }
        finally { if (initialized) { release.invoke(singleton, callback); initialized = false; } }
    }
}
