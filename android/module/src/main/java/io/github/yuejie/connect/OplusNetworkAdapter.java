package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Selects the actual native card peer. No global DataBus message without an explicit peer is intercepted. */
final class OplusNetworkAdapter {
    private static final String BASE = "com.oplus.linker.synergy.ui.widgets.";
    private final XposedModule module;
    private final ClassLoader loader;
    private final NetworkShareController controller;
    private final Supplier<NetworkShareController.Target> target;
    private final Supplier<NetworkSharePolicy.Consent> consent;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, NetworkShareController.Target> cards = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;

    OplusNetworkAdapter(XposedModule module, ClassLoader loader, NetworkShareController controller,
            Supplier<NetworkShareController.Target> target, Supplier<NetworkSharePolicy.Consent> consent) {
        this.module = module; this.loader = loader; this.controller = controller; this.target = target; this.consent = consent;
    }
    void install() throws ReflectiveOperationException {
        try {
            installCard("MyPadDeviceOnPhonePreference", "toggleShareNetwork", true);
            installCard("MyPhoneDeviceOnTabletPreference", "onShareNetworkClick", false,
                    Class.forName(BASE + "QuickActionItemView", false, loader));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    private void installCard(String name, String operation, boolean providing, Class<?>... args) throws ReflectiveOperationException {
        Class<?> card = Class.forName(BASE + name, false, loader);
        Field message = card.getDeclaredField("mMyDeviceMessage"); message.setAccessible(true);
        Method selectedId = Class.forName("com.oplus.linker.synergy.ui.base.MyDeviceMessage", false, loader).getMethod("getDeviceIdStr");
        Class<?> action = Class.forName(BASE + name + "$QuickActionType", false, loader);
        Class<?> nativeState = Class.forName(BASE + "QuickActionItemView$State", false, loader);
        hooks.add(module.hook(card.getMethod("setActionState", action, nativeState)).setId("yj.oplus.network.state." + name).intercept(chain -> {
            Object instance = chain.getThisObject(), selected = message.get(instance);
            NetworkShareController.Target t = target.get();
            if (closed || selected == null || t == null || !t.id().equals(selectedId.invoke(selected))
                    || !(chain.getArg(0) instanceof Enum<?> type) || !"SHARE_NETWORK".equals(type.name())) return chain.proceed();
            synchronized (cards) { cards.put(instance, t); }
            NetworkShareController.Status status = controller.status();
            Phase phase = t.equals(status.target()) ? status.phase() : Phase.STOPPED;
            if (phase == Phase.RECOVERY_REQUIRED) return null; // Retain the last value, never forge OFF.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object state = Enum.valueOf((Class<? extends Enum>) nativeState, stateName(phase));
            return chain.proceed(new Object[]{chain.getArg(0), state});
        }));
        hooks.add(module.hook(card.getDeclaredMethod(operation, args)).setId("yj.oplus.network." + name).intercept(chain -> {
            Object instance = chain.getThisObject(); Object selected = message.get(instance);
            NetworkShareController.Target t = target.get();
            if (closed || selected == null || t == null || !t.id().equals(selectedId.invoke(selected))) return chain.proceed();
            synchronized (cards) { cards.put(instance, t); }
            NetworkShareController.Status s = controller.status();
            if (t.equals(s.target()) && s.phase() != Phase.STOPPED && s.phase() != Phase.FAILED) { controller.stop(); return null; }
            NetworkSharePolicy.Consent grant = consent.get();
            if (grant == null || !controller.ready(t)) { render(instance, t, Phase.FAILED); return null; }
            (providing ? controller.startProviding(t, grant.upstream(), grant.meteredAllowed())
                    : controller.start(t, grant.upstream(), grant.meteredAllowed())).whenComplete((v, e) -> {
                if (e != null) render(instance, t, Phase.FAILED);
            });
            return null;
        }));
    }
    void onStatus(NetworkShareController.Status status) {
        List<Map.Entry<Object, NetworkShareController.Target>> copy;
        synchronized (cards) { copy = new ArrayList<>(cards.entrySet()); }
        for (Map.Entry<Object, NetworkShareController.Target> entry : copy)
            if (entry.getValue().equals(status.target())) render(entry.getKey(), entry.getValue(), status.phase());
    }
    void close() { closed = true; for (HookHandle h : hooks) h.unhook(); hooks.clear(); synchronized (cards) { cards.clear(); } }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void render(Object card, NetworkShareController.Target t, Phase phase) {
        main.post(() -> {
            if (closed || !t.equals(target.get())) return;
            try {
                Class<? extends Enum> action = (Class<? extends Enum>) Class.forName(card.getClass().getName() + "$QuickActionType", false, loader);
                Field views = card.getClass().getDeclaredField("mQuickActionViewMap"); views.setAccessible(true);
                Object mapped = views.get(card);
                Object view = mapped instanceof Map<?, ?> map ? map.get(Enum.valueOf(action, "SHARE_NETWORK")) : null;
                if (phase == Phase.RECOVERY_REQUIRED) {
                    // The native widget has no failure state. Disable it without falsely rendering OFF;
                    // the shared property publisher removes availability and retains the failure receipt.
                    if (view instanceof android.view.View nativeView) nativeView.setEnabled(false);
                    return;
                }
                if (view instanceof android.view.View nativeView) nativeView.setEnabled(controller.ready(t));
                Class<? extends Enum> state = (Class<? extends Enum>) Class.forName(BASE + "QuickActionItemView$State", false, loader);
                String value = stateName(phase);
                card.getClass().getMethod("setActionState", action, state).invoke(card,
                        Enum.valueOf(action, "SHARE_NETWORK"), Enum.valueOf(state, value));
            } catch (ReflectiveOperationException ignored) { /* Retain actual result; never replace it with UI success. */ }
        });
    }
    private static String stateName(Phase phase) {
        return phase == Phase.ACTIVE ? "ON" : phase == Phase.STARTING || phase == Phase.REQUESTED
                || phase == Phase.READY || phase == Phase.STOPPING ? "LOADING" : "IDLE";
    }
}
