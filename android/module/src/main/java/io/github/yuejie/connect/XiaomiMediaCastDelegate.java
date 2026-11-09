package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Optional Mirror send delegate for VolumeValueMessage only. Never intercepts car media/Ctrl.
 * Shared owner installs this in verified Mirror, then binds only a real rendered phone/pad cast.
 * VolumeValueMessage is NOT SessionMessage and has no result protocol: never fabricate onReply. */
final class XiaomiMediaCastDelegate implements AutoCloseable {
    interface StateConsumer {
        /** Actual remote readback; integrator must route to the ORIGINAL owner's state consumer. */
        void observed(MediaControlNativeRoute.Scope scope, MediaControlSession.State state);
        void failed(MediaControlNativeRoute.Scope scope, String code, boolean attempted);
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final MediaControlNativeRoute route;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<WeakReference<Object>> owned = new ArrayList<>();
    private HookHandle hook;
    private Class<?> volumeType, peerType, callbackType;
    private Field value;
    private Method peerId, exception;
    private long generation;
    private boolean closed;
    private MediaControlNativeRoute.Scope scope;
    private BooleanSupplier active;
    private StateConsumer consumer;

    XiaomiMediaCastDelegate(XposedModule module, ClassLoader loader, MediaControlNativeRoute route) {
        this.module = java.util.Objects.requireNonNull(module); this.loader = java.util.Objects.requireNonNull(loader);
        this.route = java.util.Objects.requireNonNull(route);
    }
    synchronized void install() throws ReflectiveOperationException {
        if (closed || hook != null) throw new IllegalStateException("media_delegate_not_installable");
        Class<?> message = Class.forName("com.xiaomi.mirror.message.Message", false, loader);
        volumeType = Class.forName("com.xiaomi.mirror.message.VolumeValueMessage", false, loader);
        peerType = Class.forName("com.xiaomi.mirror.k0", false, loader);
        callbackType = Class.forName("com.xiaomi.mirror.message.MessageManager$SendCallback", false, loader);
        value = volumeType.getField("value");
        if (value.getType() != int.class) throw new NoSuchFieldException("native_volume_type_changed");
        peerId = peerType.getMethod("h");
        exception = callbackType.getMethod("onException", peerType, Throwable.class);
        Method send = Class.forName("com.xiaomi.mirror.message.MessageManagerImpl", false, loader)
                .getMethod("send", message, peerType, callbackType);
        hook = module.hook(send).setId("yj.media.xiaomi.cast.volume").intercept(chain -> {
            Object nativeMessage = chain.getArg(0), peer = chain.getArg(1);
            if (nativeMessage == null || nativeMessage.getClass() != volumeType || !known(peer)) return chain.proceed();
            sendVolume(peer, value.getInt(nativeMessage), chain.getArg(2));
            return null;
        });
    }
    /** active verifies the exact native owner + peer object, scope's screen/stream, streaming and
     * both grants. The caller supplies the original state consumer; no synthetic reply/UI is made. */
    synchronized void bind(MediaControlNativeRoute.Scope nextScope, Object nativePeer, BooleanSupplier active,
            StateConsumer originalConsumer) throws ReflectiveOperationException {
        if (closed || hook == null || !peerType.isInstance(nativePeer)
                || !nextScope.nativePeerId().equals(peerId.invoke(nativePeer))) throw new IllegalStateException("native_media_peer_mismatch");
        java.util.Objects.requireNonNull(originalConsumer);
        owned.removeIf(reference -> reference.get() == null);
        if (!known(nativePeer) && owned.size() >= 16) throw new IllegalStateException("media_peer_capacity");
        BooleanSupplier gate = () -> {
            try { return !closed && active.getAsBoolean() && nextScope.nativePeerId().equals(peerId.invoke(nativePeer)); }
            catch (ReflectiveOperationException | RuntimeException invalid) { return false; }
        };
        route.bind(nextScope, nativePeer, "", gate);
        if (!known(nativePeer)) owned.add(new WeakReference<>(nativePeer));
        generation++; scope = nextScope; this.active = gate; consumer = originalConsumer;
    }
    private synchronized boolean known(Object peer) {
        if (peer == null) return false;
        for (WeakReference<Object> reference : owned) if (reference.get() == peer) return true;
        return false;
    }
    private void sendVolume(Object peer, int percent, Object callback) {
        final long epoch;
        final MediaControlNativeRoute.Scope selected;
        final StateConsumer observer;
        synchronized (this) { epoch = generation; selected = scope; observer = consumer; }
        route.volume(peer, percent).thenAccept(result -> main.post(() -> {
            boolean current;
            synchronized (this) { current = !closed && epoch == generation && scope == selected && active != null && active.getAsBoolean(); }
            String code = current ? result.code() : "cast_inactive";
            if (current && result.confirmed() && result.state() != null) observer.observed(selected, result.state());
            else {
                // SendCallback has only exception/reply; a volume message has no reply/session id.
                if (callback != null && callbackType.isInstance(callback)) {
                    try { exception.invoke(callback, peer, new IllegalStateException(code)); }
                    catch (ReflectiveOperationException ignored) { }
                }
                if (current) observer.failed(selected, code, result.dispatched());
            }
        }));
    }
    synchronized void sessionChanged() { generation++; scope = null; active = null; consumer = null; route.revoke(); }
    void tick() { route.tick(); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; sessionChanged(); route.close();
        if (hook != null) { hook.unhook(); hook = null; }
        owned.clear();
    }
}
