package io.github.qiangjian.connect;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Native phone/pad MouseMessage/KeyMessage send entrance. Only the public resolver's exact,
 * currently streaming peer/screen route is replaced. Original input is one-way; no native reply
 * message is invented from a successful enqueue or even a typed remote injection result. */
public final class XiaomiScreenInputAdapter implements AutoCloseable {
    public record Route(XiaomiScreenInputCodec codec, RemoteInputSender sender) {
        public Route {
            Objects.requireNonNull(codec); Objects.requireNonNull(sender);
            if (!codec.scope().equals(sender.desktopScope())) throw new IllegalArgumentException("native_input_route_scope_mismatch");
        }
        @Override public String toString() { return "NativeInputRoute"; }
    }
    public interface Delegate {
        /** null only for unrelated OEM peers. Match actualPeer by object identity plus current
         * session/stream/screen and separate input permission; a display alias/IP is not a route. */
        Route resolve(Object actualPeer, int nativeScreenId);
        /** Actual result or stable unconfirmed error, no private text/coordinates. */
        void completed(Route route, RemoteInputSession.Result actualNativeResult, String error);
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Delegate delegate;
    private HookHandle hook;
    private volatile boolean closed;
    public XiaomiScreenInputAdapter(XposedModule module, ClassLoader loader, Delegate delegate) {
        this.module = Objects.requireNonNull(module); this.loader = Objects.requireNonNull(loader);
        this.delegate = Objects.requireNonNull(delegate);
    }
    public void install() throws ReflectiveOperationException {
        if (closed || hook != null) throw new IllegalStateException("native_input_adapter_not_installable");
        Class<?> message = type("com.xiaomi.mirror.message.Message"), peer = type("com.xiaomi.mirror.k0");
        Class<?> mouse = type("com.xiaomi.mirror.message.MouseMessage"), key = type("com.xiaomi.mirror.message.KeyMessage");
        Class<?> callback = type("com.xiaomi.mirror.message.MessageManager$SendCallback");
        Class<?> recyclable = type("com.xiaomi.mirror.message.Recyclable");
        Field mouseScreen = mouse.getField("screenId"), keyScreen = key.getField("screenId");
        Method recycle = type("com.xiaomi.mirror.message.MessageFactory").getMethod("recycle", recyclable);
        Method exception = callback.getMethod("onException", peer, Throwable.class);
        hook = module.hook(type("com.xiaomi.mirror.message.MessageManagerImpl").getMethod("send", message, peer, callback))
                .setId("qj.screen.xiaomi.input.send").intercept(chain -> {
                    Object original = chain.getArg(0), actualPeer = chain.getArg(1), nativeCallback = chain.getArg(2);
                    if (closed || original == null || (original.getClass() != mouse && original.getClass() != key)) return chain.proceed();
                    int screenId = (original.getClass() == mouse ? mouseScreen : keyScreen).getInt(original);
                    Route route = delegate.resolve(actualPeer, screenId);
                    if (route == null) return chain.proceed();
                    try {
                        // The observed phone/pad input callers use null callbacks. There is no OEM
                        // reply schema for injecting our typed result into a non-null native callback.
                        if (nativeCallback != null) {
                            route.sender.close(); route.codec.close();
                            exception.invoke(nativeCallback, actualPeer, new IOException("native_input_reply_unavailable"));
                            delegate.completed(route, null, "native_input_reply_unavailable"); return null;
                        }
                        if (!route.sender.current()) throw new IllegalStateException("native_input_route_changed");
                        CompletionStage<RemoteInputSession.Result> result;
                        var text = route.codec.snapshotText(actualPeer, original);
                        if (text != null) result = route.sender.text(text);
                        else {
                            var event = route.codec.snapshot(actualPeer, original);
                            if (event == null) throw new IllegalStateException("native_input_payload_unavailable");
                            result = route.sender.desktop(event);
                        }
                        result.whenComplete((value, error) -> {
                            try { delegate.completed(route, value, error == null ? null : "native_input_result_unconfirmed"); }
                            catch (RuntimeException ignored) { }
                        });
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        route.codec.close(); route.sender.close();
                        delegate.completed(route, null, "native_input_dispatch_failed");
                    } finally {
                        // Original send/MessageConvert no longer owns this managed message. Snapshot
                        // first, then return it once to the original pool. Unrelated messages stay OEM-owned.
                        recycle.invoke(null, original);
                    }
                    return null;
                });
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    /** Route/sender ownership belongs to the public lifecycle; revoke them before unhooking. */
    @Override public void close() { closed = true; if (hook != null) { hook.unhook(); hook = null; } }
}
