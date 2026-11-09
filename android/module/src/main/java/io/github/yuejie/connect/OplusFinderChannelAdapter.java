package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Original FindMyPhone ChannelProvider RING/STOP_RING -> actual bridge execution -> OEM callback. */
final class OplusFinderChannelAdapter implements AutoCloseable {
    private static final String BASE = "com.coloros.findmyphone.channel.api.bean.";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OplusFinderSender sender;
    private final BooleanSupplier allowed;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Method receiver, info, id, type, messageId, action, callback, success, failure;
    private final Constructor<?> result, failedReply;
    private final Object localChannel, nativeError;
    private BridgeClient.Subscription projection;
    private OplusFinderUiAdapter nativeUi;
    private volatile boolean closed;

    OplusFinderChannelAdapter(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("finder_main_thread_required");
        if (client.context() == null || !"com.coloros.findmyphone".equals(client.context().getPackageName()))
            throw new SecurityException("finder_native_process_required");
        this.allowed = allowed;
        Class<?> message = Class.forName(BASE + "ChannelMessage", false, loader);
        Class<?> receiverType = Class.forName(BASE + "MessageReceiver", false, loader);
        Class<?> infoType = Class.forName(BASE + "BaseChannelDeviceInfo", false, loader);
        Class<?> channel = Class.forName(BASE + "FindChannelType", false, loader);
        Class<?> callbackType = Class.forName("C1.d", false, loader);
        Class<?> reply = Class.forName("a1.a", false, loader);
        Class<?> error = Class.forName("com.coloros.findmyphone.basecommon.error.FindError", false, loader);
        Class<?> outcome = Class.forName(BASE + "ChannelSendResult", false, loader);
        Class<?> provider = Class.forName("com.coloros.findmyphone.channel.core.FindChannelProviderImpl", false, loader);
        receiver = message.getMethod("getReceiver");
        info = receiverType.getMethod("getDeviceInfo");
        id = infoType.getMethod("getDeviceId");
        type = infoType.getMethod("getDeviceType");
        messageId = message.getMethod("getMessageId");
        action = message.getMethod("getMsgType");
        callback = callbackType.getMethod("b", reply);
        success = reply.getMethod("b", Object.class);
        failure = reply.getMethod("a", error);
        result = outcome.getConstructor(channel, reply, message);
        failedReply = reply.getConstructor(error, Object.class);
        localChannel = channel.getField("LOCAL_AREA_NET").get(null);
        // Original generic connect exception. No successful cloud/send-queue result is fabricated.
        nativeError = error.getConstructor(int.class, String.class).newInstance(-518000004, "local finder execution failed");
        sender = new OplusFinderSender(new OplusFinderSender.Transport() {
            @Override public void request(String selected, String session, String operation, String request, JSONObject payload,
                                          java.util.function.Consumer<JSONObject> completion) {
                client.request(selected, session, operation, request, payload, completion);
            }
            @Override public void cancel(String session, String request) { client.cancel(session, request, ignored -> { }); }
        });
        try {
            hooks.add(module.hook(provider.getMethod("sendMessage", message, callbackType)).setId("yj.finder.oplus.send").intercept(chain -> {
                return route(chain.getArg(0), chain.getArg(1)) ? null : chain.proceed();
            }));
            hooks.add(module.hook(provider.getMethod("sendMessageByChannel", message, channel, callbackType, String.class))
                    .setId("yj.finder.oplus.send_channel").intercept(chain -> {
                        return route(chain.getArg(0), chain.getArg(2)) ? null : chain.proceed();
                    }));
            projection = client.addProjectionListener(sender::update);
            sender.update(client.projection());
            try { nativeUi = new OplusFinderUiAdapter(module, client, loader, allowed); }
            catch (Exception unavailable) { android.util.Log.w("YueJieFinder", "native_local_finder_ui_unavailable " + unavailable.getClass().getSimpleName()); }
        } catch (Exception failure) { close(); throw failure; }
    }

    private boolean route(Object message, Object completion) throws Exception {
        if (message == null) return false;
        Object recipient = receiver.invoke(message);
        if (recipient == null) return false;
        Object device = info.invoke(recipient);
        if (device == null) return false;
        String nativeId = (String) id.invoke(device);
        if (!OplusFinderSender.bridgeId(nativeId)) return false;
        // Once a bridge namespace is recognized it must never fall through to cloud, SMS, ERASE, etc.
        if (closed || !allowed.getAsBoolean()) { complete(message, completion, new OplusFinderSender.Outcome(false, false, "permission_denied")); return true; }
        Object nativeType = type.invoke(device), command = action.invoke(message);
        sender.send(nativeId, nativeType instanceof Enum<?> e ? e.name() : null, (String) messageId.invoke(message),
                command instanceof Enum<?> e ? e.name() : null, outcome -> complete(message, completion, outcome));
        return true;
    }

    private void complete(Object message, Object completion, OplusFinderSender.Outcome outcome) {
        if (completion == null) return;
        Runnable deliver = () -> {
            try {
                boolean verified = outcome.success() && !closed && allowed.getAsBoolean();
                Object one = verified ? success.invoke(null, new Object[]{null}) : failure.invoke(null, nativeError);
                Object nativeResult = result.newInstance(localChannel, one, message);
                Object response = verified ? success.invoke(null, nativeResult) :
                        // Preserve the per-channel failure map as well as the outer OEM error.
                        failedReply.newInstance(nativeError, nativeResult);
                callback.invoke(completion, response);
            } catch (Exception failure) { android.util.Log.w("YueJieFinder", "native_sender_callback_unavailable"); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run(); else main.post(deliver);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        boolean cleanupFailed = false;
        if (nativeUi != null) {
            try { nativeUi.close(); } catch (RuntimeException failure) { cleanupFailed = true; }
            nativeUi = null;
        }
        if (projection != null) {
            try { projection.close(); } catch (RuntimeException failure) { cleanupFailed = true; }
            projection = null;
        }
        for (HookHandle hook : hooks) try { hook.unhook(); }
        catch (RuntimeException failure) { cleanupFailed = true; }
        hooks.clear();
        sender.close();
        if (cleanupFailed) android.util.Log.w("YueJieFinder", "native_sender_hook_cleanup_unavailable");
    }
}
