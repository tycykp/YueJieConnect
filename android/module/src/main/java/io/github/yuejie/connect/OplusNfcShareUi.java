package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

/** Calls the pinned Linker APK's own NFC renderer, resources, gestures and card builder. */
final class OplusNfcShareUi {
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object view, resources;
    private final Class<?> cardType, wrapperType, callbackType, continuationType, animationType;
    private final Method build, enter;
    private Object callback;
    private long generation;
    private int percent = -1;
    private boolean swiped;
    private Object displayedCard;
    private final Object shareCache;
    private final Method setPeerName, cardTitle;

    OplusNfcShareUi(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        Object repo = type("nfc.NfcEventRepository").getField("INSTANCE").get(null);
        view = OplusNfcShare.call(repo, "getNfcAnimationView");
        Class<?> resourceType = type("nfc.resource.ShareResourceManagerImpl");
        resources = OplusNfcShare.call(singleton(Class.forName(resourceType.getName() + "$Companion", false, loader), resourceType), "getShareResourceManager");
        cardType = type("nfc.ui.card.CardViewData");
        Class<?> cacheType = type("nfc.share.NfcShareCache");
        shareCache = cacheType.getField("INSTANCE").get(null);
        setPeerName = cacheType.getMethod("setPeerUserName", String.class);
        cardTitle = view.getClass().getDeclaredMethod("getCardTitle", boolean.class, cardType);
        cardTitle.setAccessible(true);
        wrapperType = type("nfc.ui.card.ViewDataWrapper");
        callbackType = type("nfc.ui.callback.NfcCardOperateCallback");
        animationType = type("nfc.data.NfcAnimationType");
        continuationType = Class.forName("kotlin.coroutines.Continuation", false, loader);
        build = resourceType.getMethod("buildCardViewData-gIAlu-s", type("nfc.share.operation.ShareOperationEntity"), continuationType);
        enter = view.getClass().getMethod("startCardEnterAnimation-BWLJW6A", boolean.class, wrapperType, boolean.class, boolean.class);
        // Resolve the complete native contract before taking ownership of any tap.
        view.getClass().getMethod("startAnimation", boolean.class);
        view.getClass().getMethod("cancelCard", boolean.class, animationType, float.class);
        view.getClass().getMethod("registerCardOperateCallback", callbackType);
        view.getClass().getMethod("unRgisterCardOperateCallback", callbackType);
    }

    boolean running() throws ReflectiveOperationException { return (Boolean) OplusNfcShare.call(view, "isRunning"); }

    void start(Runnable send, Runnable cancel) throws ReflectiveOperationException {
        long ticket = ++generation;
        percent = -1;
        swiped = false;
        displayedCard = null;
        peerName("小米平板");
        callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if ("onFlingUp".equals(method.getName())) main.post(() -> {
                if (generation == ticket && !swiped) { swiped = true; send.run(); }
            });
            if (java.util.Set.of("onBack", "onCancel", "onCardTimeOut", "onFlingDown", "onInterrupt", "onReject", "onSystemCancel")
                    .contains(method.getName())) main.post(() -> { if (generation == ticket) cancel.run(); });
            return null;
        });
        view.getClass().getMethod("registerCardOperateCallback", callbackType).invoke(view, callback);
        view.getClass().getMethod("startAnimation", boolean.class).invoke(view, false);
    }

    CompletableFuture<Void> card(Object entity) {
        long ticket = generation;
        CompletableFuture<Void> shown = new CompletableFuture<>();
        CompletableFuture<Object> built = new CompletableFuture<>();
        try {
            Object prebuilt = OplusNfcShare.call(entity, "getPrebuiltCardViewData");
            if (prebuilt != null) built.complete(prebuilt);
            else {
                Class<?> empty = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, loader);
                Object coroutineContext = singleton(empty, empty);
                Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{continuationType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if ("getContext".equals(method.getName())) return coroutineContext;
                    if ("resumeWith".equals(method.getName())) { built.complete(args[0]); return null; }
                    throw new UnsupportedOperationException(method.getName());
                });
                Object result = build.invoke(resources, entity, continuation);
                if (!(result instanceof Enum<?> value && "COROUTINE_SUSPENDED".equals(value.name()))) built.complete(result);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { built.completeExceptionally(failure); }
        built.whenComplete((result, failure) -> main.post(() -> {
            if (generation != ticket) { shown.cancel(false); return; }
            try {
                if (failure != null) throw new IllegalStateException(failure);
                Object data = result;
                // A resumed suspend function boxes its Kotlin Result; an immediate return does not.
                if (data != null && "kotlin.Result".equals(data.getClass().getName())) {
                    Field value = data.getClass().getDeclaredField("value"); value.setAccessible(true); data = value.get(data);
                }
                if (!cardType.isInstance(data)) throw new IllegalStateException("native_card_unavailable");
                displayedCard = data;
                Object wrapper = wrapperType.getConstructor(cardType).newInstance(data);
                Object entered = enter.invoke(view, true, wrapper, false, false);
                if (!Boolean.TRUE.equals(entered)) throw new IllegalStateException("native_card_rejected");
                refreshProgress(ticket);
                shown.complete(null);
            } catch (ReflectiveOperationException | RuntimeException error) { shown.completeExceptionally(error); }
        }));
        return shown;
    }

    void progress(int value) { percent = Math.max(0, Math.min(99, value)); }

    void peerName(String name) {
        if (name == null || name.isBlank()) return;
        try {
            setPeerName.invoke(shareCache, name);
            Object card = OplusNfcShare.call(view, "getCardOperateView");
            if (card instanceof android.view.View nativeView && displayedCard != null) {
                int id = nativeView.getResources().getIdentifier("card_title", "id", CallerPolicy.OPLUS);
                android.view.View title = nativeView.findViewById(id);
                if (title instanceof android.widget.TextView text)
                    text.setText((CharSequence) cardTitle.invoke(view, true, displayedCard));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { Log.w(NfcTapActivity.LOG, "native_peer_name_unavailable", failure); }
    }

    private void refreshProgress(long ticket) {
        if (generation != ticket) return;
        try {
            OplusNfcShare.call(view, "cancelWaitUserTouchTimeout");
            Object progress = field("progressBar");
            if (progress != null && swiped) {
                // These are the OEM card's existing widgets, with only acknowledged bytes shown.
                ((android.view.View) field("card_progress_view")).setVisibility(android.view.View.VISIBLE);
                ((android.view.View) field("loadingview")).setVisibility(percent < 0 ? android.view.View.VISIBLE : android.view.View.GONE);
                ((android.view.View) progress).setVisibility(percent < 0 ? android.view.View.GONE : android.view.View.VISIBLE);
                if (percent >= 0) progress.getClass().getMethod("setProgress", int.class).invoke(progress, percent);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(NfcTapActivity.LOG, "native_progress_unavailable", failure);
            return;
        }
        main.postDelayed(() -> refreshProgress(ticket), 250);
    }

    private Object field(String name) throws ReflectiveOperationException {
        Field field = view.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(view);
    }

    void finish(boolean success) {
        ++generation;
        try {
            if (callback != null) view.getClass().getMethod("unRgisterCardOperateCallback", callbackType).invoke(view, callback);
            callback = null;
            // The upward gesture already ran CardSend. cancelCard alone is a no-op
            // once that card is gone; retire the whole native overlay on every outcome.
            OplusNfcShare.call(view, "allAnimationCancel");
            Log.i(NfcTapActivity.LOG, "native_share_ui_closed success=" + success);
        } catch (ReflectiveOperationException | RuntimeException failure) { Log.w(NfcTapActivity.LOG, "native_animation_finish_failed", failure); }
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.oplus.interconnect." + name, false, loader); }
    private static Object singleton(Class<?> type, Class<?> owner) throws ReflectiveOperationException {
        for (Field field : owner.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
            field.setAccessible(true); return field.get(null);
        }
        throw new NoSuchFieldException(type.getName());
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "YueJieNativeNfcCallback";
            default -> null;
        };
    }
}
