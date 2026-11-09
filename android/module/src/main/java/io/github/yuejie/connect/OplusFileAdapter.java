package io.github.yuejie.connect;

import android.content.Context;
import android.os.Handler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Explicitly bound OAF file sender. No global writeFile(List) hook or inferred current peer. */
public final class OplusFileAdapter {
    public interface Authority {
        /** Native-pairing must bind these exact live objects to this peer/session after real open. */
        boolean current(Object consumer, Object peerAgent, Object channel, FileTransferEngine.Key key);
    }
    private final Context context;
    private final FileTransferSendCoordinator coordinator;
    private final Authority authority;
    private final OplusFileCodec codec;
    private final Class<?> consumerType, peerType, observerType;
    private final Field nativePeer;
    private final Method getChannel, getAppName, onNext, onCompleted, onError;

    public OplusFileAdapter(Context context, ClassLoader loader, FileTransferSendCoordinator coordinator,
                            Authority authority) throws ReflectiveOperationException {
        if (!"com.oplus.linker".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context; this.coordinator = coordinator; this.authority = authority;
        codec = new OplusFileCodec(loader);
        consumerType = Class.forName("com.oplus.linker.synergy.wisetransfer.fileservice.icdf.consumer.FileServiceConsumer", false, loader);
        peerType = Class.forName("com.heytap.accessory.bean.PeerAgent", false, loader);
        observerType = Class.forName("d83", false, loader);
        nativePeer = consumerType.getDeclaredField("mPeerAgent"); nativePeer.setAccessible(true);
        getChannel = consumerType.getMethod("getChannel");
        getAppName = peerType.getMethod("getAppName");
        onNext = observerType.getMethod("onNext", Object.class);
        onCompleted = observerType.getMethod("onCompleted");
        onError = observerType.getMethod("onError", Throwable.class);
    }

    /** Called with the original native response observer and its owning handler.
     * Caller may replace an OEM send only after canRoute() and source validation succeed. */
    public CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key,
            Object consumer, Object peerAgent, Object channel, Object nativeFileInfo,
            Object nativeResponseObserver, Handler nativeHandler) throws ReflectiveOperationException, IOException {
        return send(key, consumer, peerAgent, channel, nativeFileInfo, nativeResponseObserver, nativeHandler,
                () -> true, () -> {});
    }

    CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key,
            Object consumer, Object peerAgent, Object channel, Object nativeFileInfo,
            Object nativeResponseObserver, Handler nativeHandler, BooleanSupplier requestCurrent,
            Runnable requestFinished) throws ReflectiveOperationException, IOException {
        if (!canRoute(key, consumer, peerAgent, channel)) throw new IOException("native_file_route_unbound");
        if (!observerType.isInstance(nativeResponseObserver) || nativeHandler == null)
            throw new IOException("native_file_callback_missing");
        OplusFileCodec.Source source = validateSource(nativeFileInfo);
        FileTransferEngine.SessionGate route = ignored -> requestCurrent.getAsBoolean() && canRoute(key, consumer, peerAgent, channel);
        CompletableFuture<FileTransferEngine.Receipt> result = coordinator.send(key, route,
                (gate, id) -> FileTransferUris.snapshot(context, source.uri(), source.name(),
                        "application/octet-stream", source.size(), gate, id));
        result.whenComplete((receipt, error) -> {
          if (!nativeHandler.post(() -> {
            // Do not deliver an old completion into a replacement channel/peer or native request.
            try {
                if (!requestCurrent.getAsBoolean() || !canRoute(key, consumer, peerAgent, channel)) return;
                if (error == null && receipt != null && key.equals(receipt.key()) && receipt.size() == source.size()) {
                    Object builder = codec.statusType.getMethod("newBuilder").invoke(null);
                    builder.getClass().getMethod("setStatus", int.class).invoke(builder, 0);
                    builder.getClass().getMethod("setLength", long.class).invoke(builder, receipt.size());
                    onNext.invoke(nativeResponseObserver, builder.getClass().getMethod("build").invoke(builder));
                    onCompleted.invoke(nativeResponseObserver);
                } else {
                    onError.invoke(nativeResponseObserver, new IOException("file_transfer_failed"));
                }
            } catch (ReflectiveOperationException ignored) { /* A dead callback is never a success fallback. */ }
            finally { requestFinished.run(); }
          })) requestFinished.run();
        });
        return result;
    }

    OplusFileCodec.Source validateSource(Object nativeFileInfo) throws ReflectiveOperationException, IOException {
        OplusFileCodec.Source source = codec.source(nativeFileInfo);
        // Directory/open-after-receive semantics require a separate verified business contract.
        if ((source.flags() & ~6L) != 0) throw new IOException("native_file_flags_not_supported");
        return source;
    }

    public boolean canRoute(FileTransferEngine.Key key, Object consumer, Object peerAgent, Object channel) {
        try {
            return consumerType.isInstance(consumer) && peerType.isInstance(peerAgent) && channel != null
                    && nativePeer.get(consumer) == peerAgent && getChannel.invoke(consumer) == channel
                    && "com.oplus.padconnect".equals(getAppName.invoke(peerAgent))
                    && authority.current(consumer, peerAgent, channel, key);
        } catch (ReflectiveOperationException | RuntimeException invalid) { return false; }
    }
}
