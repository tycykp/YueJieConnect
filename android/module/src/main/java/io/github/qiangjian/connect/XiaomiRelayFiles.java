package io.github.qiangjian.connect;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exports only the content URI observed inside this exact native e.J -> V3.V.I invocation. */
final class XiaomiRelayFiles implements XiaomiRelayCodec.Files {
    interface Consent {
        // Completes only after the receiver's native, metadata-bound temporary-document approval.
        CompletableFuture<Void> authorize(FileTransferEngine.Key key, FileTransferEngine.Offer offer);
    }
    interface KeyAllocator {
        FileTransferEngine.Key allocate(String peer, String session) throws Exception;
        default void release(FileTransferEngine.Key key) {}
    }
    interface NativeConsent { CompletableFuture<Void> authorize(FileTransferEngine.Key key, FileTransferEngine.Offer offer, String nativeOwnerToken); }
    private final Context context;
    private final FileTransferClient client;
    private final FileTransferEngine.SessionGate sessions;
    private final Consent consent;
    private final KeyAllocator keys;
    private final NativeConsent nativeConsent;
    XiaomiRelayFiles(Context context, FileTransferClient client, FileTransferEngine.SessionGate sessions, Consent consent) {
        this.context = context; this.client = client; this.sessions = sessions; this.consent = consent;
        this.keys = null; this.nativeConsent = null;
    }
    XiaomiRelayFiles(Context context, FileTransferClient client, FileTransferEngine.SessionGate sessions,
                     KeyAllocator keys, NativeConsent consent) {
        this.context = context; this.client = client; this.sessions = sessions; this.keys = keys; this.nativeConsent = consent; this.consent = null;
    }
    @Override public String committedToken(Object message, Uri uri, String peer, String session,
                                           java.util.function.BooleanSupplier currentRequest) throws Exception {
        return committedToken(message, uri, peer, session, currentRequest, "");
    }
    @Override public String committedToken(Object message, Uri uri, String peer, String session,
                                           java.util.function.BooleanSupplier currentRequest, String nativeOwnerToken) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("relay_worker_required");
        AppRelayCatalog.requireClass(message, "com.xiaomi.mirror.message.RelayOfficeMessage");
        // The source adapter verified native deviceId before resolving it to this authenticated projection.
        // Native Mirror IDs and gateway projection IDs are distinct namespaces.
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalStateException("native_document_grant_required");
        AppRelaySync.validateOwnerToken(nativeOwnerToken);
        if (keys == null || nativeConsent == null || nativeOwnerToken.isEmpty()) throw new IllegalStateException("native_document_consent_required");
        FileTransferEngine.Key key = keys.allocate(peer, session);
        try {
        if (key == null || !peer.equals(key.peer()) || !session.equals(key.session())) throw new IllegalStateException("native_file_route_unbound");
        if (!sessions.current(key) || !currentRequest.getAsBoolean()) throw new IllegalStateException("stale_session");
        String path = (String) message.getClass().getField("downloadPATH").get(message);
        if (path == null) throw new IllegalArgumentException("invalid_file_name");
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        long size = message.getClass().getField("downloadSize").getLong(message);
        FileTransferSource source = FileTransferUris.snapshot(context, uri, name, XiaomiRelayCodec.officeMime(message), size,
                candidate -> key.equals(candidate) && sessions.current(candidate) && currentRequest.getAsBoolean(), key);
        CompletableFuture<?> pending = null;
        boolean transferred = false;
        try {
            FileTransferEngine.Offer offer = source.offer();
            CompletableFuture<Void> approval = nativeConsent.authorize(key, offer, nativeOwnerToken); pending = approval;
            await(approval, () -> sessions.current(key) && currentRequest.getAsBoolean(), 30000);
            CompletableFuture<FileTransferEngine.Receipt> send = client.send(key, source, candidate -> sessions.current(candidate) && currentRequest.getAsBoolean());
            transferred = true; pending = send;
            FileTransferEngine.Receipt receipt = await(send, () -> sessions.current(key) && currentRequest.getAsBoolean(), 30 * 60 * 1000);
            if (!sessions.current(key) || !currentRequest.getAsBoolean() || !key.equals(receipt.key()) || receipt.size() != offer.size() || !receipt.sha256().equals(offer.sha256()))
                throw new IllegalStateException("invalid_document_receipt");
            return key.transfer();
        } catch (Exception failure) {
            if (pending != null) pending.cancel(true);
            throw failure;
        } finally { if (!transferred) source.close(); }
        } finally { if (key != null) keys.release(key); }
    }
    private static <T> T await(CompletableFuture<T> future, java.util.function.BooleanSupplier current, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            if (!current.getAsBoolean()) throw new IllegalStateException("relay_cancelled");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("request_timeout");
            try { return future.get(100, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waiting) { /* Allow native cancel/session revocation to stop export. */ }
        }
    }
}
