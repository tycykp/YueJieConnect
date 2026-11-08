package io.github.qiangjian.connect;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Uses the actual shared file allocator/negotiation and retains its one document receive owner. */
final class AppRelayFileAttachment implements AutoCloseable {
    private final FileTransferBridge files;
    private final Context context;
    private volatile FileTransferBridge.Registration registration;
    private volatile boolean closed;
    private AppRelayNativeBinding binding;
    AppRelayFileAttachment(Context context, FileTransferBridge files) { this.context = context; this.files = files; }
    AppRelayBridge.DocumentsFactory documents() {
        return (peer, session) -> new AppRelayDocuments(context, peer, session,
                key -> ready() && peer.equals(key.peer()) && session.equals(key.session()) && files.current(key));
    }
    XiaomiRelayFiles source() {
        return new XiaomiRelayFiles(context, files.fileClient(), files::current, new XiaomiRelayFiles.KeyAllocator() {
            public FileTransferEngine.Key allocate(String peer, String session) throws Exception {
                if (!ready()) throw new IOException("native_document_consent_required");
                CompletableFuture<FileTransferEngine.Key> allocation = files.allocateKey();
                try { return allocation.get(8, TimeUnit.SECONDS); }
                catch (Exception unavailable) { allocation.cancel(true); throw unavailable; }
            }
            public void release(FileTransferEngine.Key key) { files.release(key); }
        }, (key, offer, token) -> files.bindOutgoing(key, FileTransferNegotiation.Purpose.APP_RELAY, token)
                ? files.awaitReceiver(key, offer) : CompletableFuture.failedFuture(new IOException("native_file_route_unbound")));
    }
    synchronized void attach(AppRelayNativeBinding binding) {
        if (closed || this.binding != null) throw new IllegalStateException("relay_lifecycle");
        this.binding = binding;
        registration = files.registerReceiver(FileTransferNegotiation.Purpose.APP_RELAY, new FileTransferBridge.NativeReceiver() {
            public void prepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint endpoint, FileTransferNegotiation negotiation) {
                if (!closed) binding.nativeDocumentPrepared(prepared, endpoint, negotiation);
            }
            public void revoked(FileTransferNegotiation.Prepared prepared) { binding.nativeDocumentRevoked(prepared.key()); }
            public void sessionChanged() { binding.nativeStateChanged(); }
            public void close() { closed = true; binding.nativeDocumentsRevoked(); }
        });
        binding.whenRetired().thenRun(this::close);
    }
    boolean ready() { return !closed && registration != null && files.ready(); }
    @Override public synchronized void close() {
        closed = true;
        FileTransferBridge.Registration old = registration; registration = null;
        if (old != null) old.close();
        if (binding != null) binding.nativeDocumentsRevoked();
    }
}
