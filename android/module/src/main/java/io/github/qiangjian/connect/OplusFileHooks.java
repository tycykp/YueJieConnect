package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Handler;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Intercepts only an explicitly selected live OAF pad route inside the original native file task. */
public final class OplusFileHooks implements AutoCloseable {
    public interface Routes extends OplusFileAdapter.Authority {
        /** A fresh transfer key, or null to leave this native route untouched.
         * Native-pairing owns the actual logical-open proof and native-object/session mapping. */
        FileTransferEngine.Key select(Object consumer, Object peerAgent, Object channel);
    }
    private static final class Attempt {
        final FileTransferEngine.Key key;
        final Object owner, consumer, peer, channel, stub, info;
        final Handler handler;
        Object response, token;
        boolean started;
        Attempt(FileTransferEngine.Key key, Object owner, Object consumer, Object peer, Object channel,
                Object stub, Object info, Handler handler) {
            this.key = key; this.owner = owner; this.consumer = consumer; this.peer = peer;
            this.channel = channel; this.stub = stub; this.info = info; this.handler = handler;
        }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Routes routes;
    private final FileTransferSendCoordinator coordinator;
    private final OplusFileAdapter adapter;
    private final Map<Handler, Attempt> active = new IdentityHashMap<>();
    private final ThreadLocal<Attempt> stack = new ThreadLocal<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private Method onError;
    private volatile boolean closed;

    public OplusFileHooks(XposedModule module, Context context, ClassLoader loader,
                         FileTransferSendCoordinator coordinator, Routes routes) throws ReflectiveOperationException {
        this.module = module; this.loader = loader; this.routes = routes; this.coordinator = coordinator;
        adapter = new OplusFileAdapter(context, loader, coordinator, routes);
    }

    public synchronized void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("file_hooks_already_installed");
        String base = "com.oplus.linker.synergy.wisetransfer.fileservice.";
        Class<?> impl = Class.forName(base + "icdf.consumer.FileServiceConsumerImpl", false, loader);
        Class<?> consumer = Class.forName(base + "icdf.consumer.FileServiceConsumer", false, loader);
        Class<?> handler = Class.forName(base + "icdf.consumer.FileServiceConsumerImpl$FileHandler", false, loader);
        Class<?> info = Class.forName("com.oplus.linker.synergy.Fileservice$FileInfo", false, loader);
        Class<?> stub = Class.forName("com.oplus.linker.synergy.FileServiceGrpc$FileServiceStub", false, loader);
        Class<?> observer = Class.forName("d83", false, loader);
        Class<?> utils = Class.forName(base + "FileServiceUtils", false, loader);
        Class<?> sendManager = Class.forName(base + "SendFileManager", false, loader);
        Field ownerField = field(handler, "this$0"), consumerField = field(impl, "mFileConsumer");
        Field peerField = field(consumer, "mPeerAgent"), stubField = field(handler, "mProxy");
        Method channelMethod = consumer.getMethod("getChannel");
        onError = observer.getMethod("onError", Throwable.class);
        try {
            hooks.add(module.hook(handler.getDeclaredMethod("writeSingleFile", info)).setId("qj.oplus.files.owner").intercept(chain -> {
                if (closed) return chain.proceed();
                Handler nativeHandler = (Handler) chain.getThisObject();
                Object owner = ownerField.get(nativeHandler), nativeConsumer = consumerField.get(owner);
                if (nativeConsumer == null) return chain.proceed();
                Object peer = peerField.get(nativeConsumer), channel = channelMethod.invoke(nativeConsumer);
                Object nativeStub = stubField.get(nativeHandler), file = chain.getArg(0);
                if (nativeStub == null) return chain.proceed();
                FileTransferEngine.Key key;
                try {
                    key = routes.select(nativeConsumer, peer, channel);
                    if (key == null || !adapter.canRoute(key, nativeConsumer, peer, channel)) return chain.proceed();
                    adapter.validateSource(file);
                } catch (Exception unhandled) { return chain.proceed(); }
                Attempt attempt = new Attempt(key, owner, nativeConsumer, peer, channel, nativeStub, file, nativeHandler);
                boolean busy;
                synchronized (active) {
                    busy = active.containsKey(nativeHandler) || active.size() >= FileTransferEngine.MAX_TRANSFERS;
                    if (!busy) active.put(nativeHandler, attempt);
                }
                if (busy) return chain.proceed();
                Attempt previous = stack.get(); stack.set(attempt);
                try { return chain.proceed(); }
                finally {
                    if (previous == null) stack.remove(); else stack.set(previous);
                    if (!attempt.started) {
                        if (attempt.response == null) remove(attempt);
                        else fail(attempt);
                    }
                }
            }));
            hooks.add(module.hook(stub.getMethod("writeFile", observer)).setId("qj.oplus.files.response").intercept(chain -> {
                Attempt attempt = stack.get();
                if (attempt == null || chain.getThisObject() != attempt.stub || !current(attempt)) return chain.proceed();
                attempt.response = chain.getArg(0);
                // A per-call token connects the original response observer to the original URI source.
                // This token must never consume file bytes or pretend to be a remote gRPC stream.
                attempt.token = Proxy.newProxyInstance(loader, new Class<?>[]{observer}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "QiangJianBoundFileSource";
                        default -> null;
                    };
                    throw new IOException("native_file_stream_not_bound");
                });
                return attempt.token;
            }));
            hooks.add(module.hook(utils.getMethod("writeFile", Context.class, info, observer, Handler.class))
                    .setId("qj.oplus.files.source").intercept(chain -> {
                Attempt attempt = stack.get();
                if (attempt == null || attempt.token == null || chain.getArg(2) != attempt.token) return chain.proceed();
                attempt.started = true;
                if (chain.getArg(1) != attempt.info || chain.getArg(3) != attempt.handler || !current(attempt)) {
                    fail(attempt); return null;
                }
                try {
                    adapter.send(attempt.key, attempt.consumer, attempt.peer, attempt.channel, attempt.info,
                            attempt.response, attempt.handler, () -> current(attempt), () -> remove(attempt));
                } catch (Exception invalid) { fail(attempt); }
                return null;
            }));
            hooks.add(module.hook(handler.getDeclaredMethod("cancelFile")).setId("qj.oplus.files.cancel").intercept(chain -> {
                Attempt attempt;
                synchronized (active) { attempt = active.remove((Handler) chain.getThisObject()); }
                if (attempt != null) coordinator.cancel(attempt.key);
                return chain.proceed();
            }));
            hooks.add(module.hook(sendManager.getMethod("setCancelState", boolean.class))
                    .setId("qj.oplus.files.cancel.flag").intercept(chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(chain.getArg(0))) {
                    for (Attempt attempt : attempts()) {
                        cancel(attempt);
                        // Exact native FileServiceUtils.checkCancelStatus behavior; the replaced
                        // read loop can no longer observe this flag and dispatch MSG_CANCEL_NEXT_FILE.
                        attempt.handler.sendEmptyMessage(6);
                    }
                }
                return result;
            }));
            hooks.add(module.hook(impl.getMethod("destroy")).setId("qj.oplus.files.destroy").intercept(chain -> {
                for (Attempt attempt : attempts()) if (attempt.owner == chain.getThisObject()) cancel(attempt);
                return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException failed) { close(); throw failed; }
    }

    private boolean current(Attempt attempt) {
        synchronized (active) { if (closed || active.get(attempt.handler) != attempt) return false; }
        return adapter.canRoute(attempt.key, attempt.consumer, attempt.peer, attempt.channel);
    }
    private void remove(Attempt attempt) { synchronized (active) { active.remove(attempt.handler, attempt); } }
    private void cancel(Attempt attempt) { remove(attempt); coordinator.cancel(attempt.key); }
    private List<Attempt> attempts() { synchronized (active) { return new ArrayList<>(active.values()); } }
    private void fail(Attempt attempt) {
        if (!attempt.handler.post(() -> {
            try { if (current(attempt)) onError.invoke(attempt.response, new IOException("file_transfer_failed")); }
            catch (ReflectiveOperationException ignored) { }
            finally { remove(attempt); }
        })) remove(attempt);
    }
    public void sessionChanged() { for (Attempt attempt : attempts()) if (!current(attempt)) cancel(attempt); }
    @Override public synchronized void close() {
        closed = true;
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        for (Attempt attempt : attempts()) cancel(attempt);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
