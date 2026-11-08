package io.github.qiangjian.connect;

import android.content.ClipData;
import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Local outgoing Mirror resource conversion. Never fabricates a Packet or a FINISH receipt. */
public final class XiaomiFileAdapter {
    public interface Authority {
        /** Must bind the actual drag/share owner and native session to the selected peer/session. */
        boolean current(Object nativeOwner, long nativeSession, FileTransferEngine.Key key);
    }
    record LocalFile(Uri uri, String name, long size) {}
    private final Context context;
    private final FileTransferSendCoordinator coordinator;
    private final Authority authority;
    private final Class<?> clipType, fileType, resourceType;
    private final Method resourceInstance, localUri;

    public XiaomiFileAdapter(Context context, ClassLoader loader, FileTransferSendCoordinator coordinator,
                              Authority authority) throws ReflectiveOperationException {
        if (!"com.xiaomi.mirror".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context; this.coordinator = coordinator; this.authority = authority;
        clipType = Class.forName("com.xiaomi.mirror.message.ClipDataMessage", false, loader);
        fileType = Class.forName("com.xiaomi.mirror.message.ClipDataMessage$FileInfo", false, loader);
        resourceType = Class.forName("V3.V", false, loader);
        resourceInstance = resourceType.getMethod("G");
        localUri = resourceType.getMethod("M", String.class);
    }

    /** Call after the original V3.V.x0/y0 returns, retaining the original ClipData URI grants.
     * Copies the selected descriptor synchronously before recyclable native messages can change.
     * The future is a file receipt only: drag/drop acceptance is owned by the native drag flow. */
    public CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key,
            Object nativeOwner, long nativeSession, ClipData originalLocalClip, Object translatedClip,
            int fileIndex) throws ReflectiveOperationException, IOException {
        if (nativeOwner == null || nativeSession <= 0 || !authority.current(nativeOwner, nativeSession, key))
            throw new IOException("native_file_route_unbound");
        List<LocalFile> files = localFiles(originalLocalClip, translatedClip);
        if (fileIndex < 0 || fileIndex >= files.size()) throw new IOException("invalid_file_index");
        LocalFile source = files.get(fileIndex);
        return coordinator.send(key, ignored -> authority.current(nativeOwner, nativeSession, key),
                (gate, id) -> FileTransferUris.snapshot(context, source.uri(), source.name(),
                        "application/octet-stream", source.size(), gate, id));
    }

    List<LocalFile> localFiles(ClipData original, Object translated) throws ReflectiveOperationException, IOException {
        if (original == null || !clipType.isInstance(translated)) throw new IOException("invalid_xiaomi_file_list");
        if (original.getItemCount() > FileTransferEngine.MAX_TRANSFERS) throw new IOException("file_count_limit");
        Set<Uri> granted = new HashSet<>();
        for (int i = 0; i < original.getItemCount(); i++) {
            Uri uri = original.getItemAt(i).getUri();
            if (uri == null) continue;
            FileTransferUris.requireContent(uri);
            granted.add(uri);
        }
        Object raw = clipType.getField("fileList").get(translated);
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > FileTransferEngine.MAX_TRANSFERS)
            throw new IOException("file_count_limit");
        Object resources = resourceInstance.invoke(null);
        if (!resourceType.isInstance(resources)) throw new IOException("native_resources_unavailable");
        List<LocalFile> result = new ArrayList<>();
        Set<Uri> consumed = new HashSet<>();
        for (Object item : items) {
            if (!fileType.isInstance(item)) throw new IOException("invalid_xiaomi_file_info");
            String url = (String) fileType.getField("url").get(item);
            if (url == null || url.length() > 8192) throw new IOException("invalid_xiaomi_file_url");
            Uri encoded = Uri.parse(url);
            Uri resolved;
            if ("content".equals(encoded.getScheme())) resolved = encoded;
            else {
                // V3.V.y0 maps local URIs to localhost resource URLs before MessageConvert.
                // Never rewrite a remote host to localhost or fetch an HTTP(S) resource.
                if (!("http".equals(encoded.getScheme()) || "https".equals(encoded.getScheme()))
                        || !"localhost".equals(encoded.getAuthority()) || !"/api/1.0/file".equals(encoded.getPath()))
                    throw new IOException("nonlocal_xiaomi_file_url");
                resolved = (Uri) localUri.invoke(resources, url);
            }
            FileTransferUris.requireContent(resolved);
            if (!granted.contains(resolved) || !consumed.add(resolved)) throw new IOException("source_uri_not_in_native_clip");
            String name = (String) fileType.getField("name").get(item);
            long size = fileType.getField("size").getLong(item);
            new FileTransferEngine.Offer(name, "application/octet-stream", size, "0".repeat(64));
            result.add(new LocalFile(resolved, name, size));
        }
        return List.copyOf(result);
    }
}
