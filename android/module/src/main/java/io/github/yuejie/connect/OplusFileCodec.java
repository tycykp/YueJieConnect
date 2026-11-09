package io.github.yuejie.connect;

import android.net.Uri;
import java.io.IOException;
import java.lang.reflect.Method;

/** Exact Fileservice generated DTOs, reflected in the com.oplus.linker class loader. */
final class OplusFileCodec {
    private static final String BASE = "com.oplus.linker.synergy.Fileservice$";
    record Source(Uri uri, String name, long size, long flags) {}
    final Class<?> infoType, dataType, statusType;
    private final Class<?> bytesType;

    OplusFileCodec(ClassLoader loader) throws ReflectiveOperationException {
        infoType = Class.forName(BASE + "FileInfo", false, loader);
        dataType = Class.forName(BASE + "FileData", false, loader);
        statusType = Class.forName(BASE + "FileStatus", false, loader);
        bytesType = Class.forName("com.google.protobuf.ByteString", false, loader);
    }

    Source source(Object info) throws ReflectiveOperationException, IOException {
        if (!infoType.isInstance(info)) throw new IOException("invalid_oplus_file_info");
        long flags = (long) infoType.getMethod("getFlags").invoke(info);
        if ((flags & 1) != 0) throw new IOException("directory_requires_explicit_listing");
        String name = (String) infoType.getMethod("getFilepath").invoke(info);
        long length = (long) infoType.getMethod("getLength").invoke(info);
        Uri uri = Uri.parse((String) infoType.getMethod("getUri").invoke(info));
        FileTransferUris.requireContent(uri);
        new FileTransferEngine.Offer(name, "application/octet-stream", length, "0".repeat(64));
        return new Source(uri, name, length, flags);
    }

    Object data(Uri destination, long length, byte[] bytes) throws ReflectiveOperationException {
        Object builder = dataType.getMethod("newBuilder").invoke(null);
        call(builder, "setInfo", String.class, destination.toString());
        call(builder, "setLength", long.class, length);
        call(builder, "setBuffer", bytesType, bytesType.getMethod("copyFrom", byte[].class).invoke(null, (Object) bytes));
        // No OPEN flag and no remote URI/path: publishing never launches an app implicitly.
        call(builder, "setFlag", long.class, 4L);
        call(builder, "setUri", String.class, "");
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static void call(Object receiver, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        Method method = receiver.getClass().getMethod(name, type);
        method.invoke(receiver, value);
    }
}
