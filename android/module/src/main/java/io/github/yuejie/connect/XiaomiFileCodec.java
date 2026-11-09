package io.github.yuejie.connect;

import android.net.Uri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Mirror CrossFileShare metadata. Native FINISH is only a start acknowledgement, never a receipt. */
final class XiaomiFileCodec {
    static final int START = 0, FINISH = 1, CANCEL = 2;
    record Source(Uri uri, String name, long size) {}
    record Request(int action, long nativeSession, List<Source> files) {}
    private final Class<?> messageType, clipType, fileType;

    XiaomiFileCodec(ClassLoader loader) throws ClassNotFoundException {
        messageType = Class.forName("com.xiaomi.mirror.message.CrossFileShareMessage", false, loader);
        clipType = Class.forName("com.xiaomi.mirror.message.ClipDataMessage", false, loader);
        fileType = Class.forName("com.xiaomi.mirror.message.ClipDataMessage$FileInfo", false, loader);
    }

    Request sourceRequest(Object message) throws ReflectiveOperationException, IOException {
        if (!messageType.isInstance(message)) throw new IOException("invalid_xiaomi_file_message");
        int action = messageType.getField("action").getInt(message);
        long session = (long) messageType.getMethod("getSessionId").invoke(message);
        if (session <= 0 || action < START || action > CANCEL) throw new IOException("invalid_xiaomi_file_action");
        if (action != START) return new Request(action, session, List.of());
        Object clip = messageType.getField("clipData").get(message);
        if (!clipType.isInstance(clip)) throw new IOException("invalid_xiaomi_file_list");
        Object raw = clipType.getField("fileList").get(clip);
        if (!(raw instanceof List<?> files) || files.isEmpty() || files.size() > FileTransferEngine.MAX_TRANSFERS)
            throw new IOException("file_count_limit");
        List<Source> sources = new ArrayList<>();
        for (Object file : files) {
            if (!fileType.isInstance(file)) throw new IOException("invalid_xiaomi_file_info");
            String name = (String) fileType.getField("name").get(file);
            long size = fileType.getField("size").getLong(file);
            new FileTransferEngine.Offer(name, "application/octet-stream", size, "0".repeat(64));
            Uri uri = Uri.parse((String) fileType.getField("url").get(file));
            // Only the original local content URI may be read. Encoded URLs/native HTTP URLs need
            // their real OEM source binding; this codec must never turn them into arbitrary fetches.
            FileTransferUris.requireContent(uri);
            sources.add(new Source(uri, name, size));
        }
        return new Request(action, session, List.copyOf(sources));
    }
}
