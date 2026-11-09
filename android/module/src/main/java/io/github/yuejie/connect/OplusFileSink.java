package io.github.yuejie.connect;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Uses the OEM FileServiceHelper writer with a locally consented new content URI, then reads it back. */
final class OplusFileSink implements FileTransferEngine.Sink {
    private final Context context;
    private final Uri destination;
    private final OplusFileCodec codec;
    private final Class<?> helperType, invokerType, progressType;

    OplusFileSink(Context context, ClassLoader loader, Uri newlyCreatedDestination) throws ReflectiveOperationException {
        if (!"com.oplus.linker".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        FileTransferUris.requireContent(newlyCreatedDestination);
        this.context = context; destination = newlyCreatedDestination;
        codec = new OplusFileCodec(loader);
        helperType = Class.forName("com.oplus.linker.synergy.wisetransfer.fileservice.FileServiceHelper", false, loader);
        invokerType = Class.forName("com.oplus.linker.synergy.wisetransfer.fileservice.icdf.FileServiceImpl$InvokerStatus", false, loader);
        progressType = Class.forName("com.oplus.linker.synergy.wisetransfer.fileservice.FileServiceProgressCallBack", false, loader);
    }

    @Override public void publishVerified(File staged, FileTransferEngine.Offer offer,
                                          FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
        Object helper = null;
        Method end = null;
        boolean ended = false;
        AtomicLong status = new AtomicLong(-1), length = new AtomicLong(-1);
        try {
            helper = helperType.getConstructor().newInstance();
            end = helperType.getMethod("writeFileEnd", int.class);
            Object observer = Proxy.newProxyInstance(invokerType.getClassLoader(), new Class<?>[]{invokerType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "YjFileStatus";
                };
                if (method.getName().equals("notifyStatus")) {
                    status.set(((Number) codec.statusType.getMethod("getStatus").invoke(args[0])).longValue());
                    length.set(((Number) codec.statusType.getMethod("getLength").invoke(args[0])).longValue());
                }
                return true;
            });
            helperType.getMethod("writeFileBegin", String.class, invokerType).invoke(helper, null, observer);
            Method write = helperType.getMethod("writeFile", Context.class, codec.dataType, progressType, long.class);
            try (InputStream input = Files.newInputStream(staged.toPath())) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                boolean any = false;
                while ((count = input.read(buffer)) != -1) {
                    FileTransferUris.requireCurrent(gate, key);
                    write.invoke(helper, context, codec.data(destination, offer.size(), Arrays.copyOf(buffer, count)), null, offer.size());
                    any = true;
                }
                if (!any) write.invoke(helper, context, codec.data(destination, 0, new byte[0]), null, 0L);
            }
            FileTransferUris.requireCurrent(gate, key);
            end.invoke(helper, 0); ended = true;
            if (status.get() != 0 || length.get() != offer.size()) throw new IOException("native_file_status_failed");
            // Helper may swallow write/close exceptions. Its status alone is insufficient.
            FileTransferUris.verify(context, destination, offer, gate, key);
        } catch (ReflectiveOperationException | RuntimeException error) { throw new IOException("native_file_write_failed", error); }
        finally {
            if (!ended && helper != null && end != null) {
                try { end.invoke(helper, 1); } catch (ReflectiveOperationException ignored) { }
            }
        }
    }

    @Override public void rollback() throws IOException { FileTransferUris.deleteNew(context, destination); }
}
