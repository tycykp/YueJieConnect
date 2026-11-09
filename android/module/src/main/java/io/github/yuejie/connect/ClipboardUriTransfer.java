package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Local URI clipboard -> the real file-transfer API. No URI or Android Parcel is serialized.
 * This is an upload stage, not clipboard delivery: the recipient must still publish its own
 * authorized local URI through its OEM clipboard endpoint. No capability is registered here. */
final class ClipboardUriTransfer implements AutoCloseable {
    /** File bytes were acknowledged. This deliberately has no clipboard-applied flag or URI. */
    record Uploaded(FileTransferEngine.Key key, FileTransferEngine.Offer offer) {}

    private final FileTransferEngine.Key key;
    private final FileTransferEngine.Offer offer;
    private final FileTransferEngine.SessionGate sessions;
    private FileTransferSource source;
    private CompletableFuture<FileTransferEngine.Receipt> transport;
    private CompletableFuture<Uploaded> upload;
    private boolean closed, started;

    private ClipboardUriTransfer(FileTransferEngine.Key key, FileTransferEngine.SessionGate sessions,
            FileTransferSource source) {
        this.key = key; this.sessions = sessions; this.source = source; this.offer = source.offer();
    }

    /** Call off the main thread in the native process holding the source URI's actual read grant.
     * The returned object owns the immutable private snapshot, even if the session changes during
     * the provider read. Its send method rechecks the session, and close releases the snapshot. */
    static ClipboardUriTransfer prepare(Context context, ClipData clip, FileTransferEngine.Key key,
            FileTransferEngine.SessionGate sessions, String displayName, String mime, long declaredSize)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("clipboard_uri_requires_worker");
        Objects.requireNonNull(key); Objects.requireNonNull(sessions);
        if (context == null || !("com.xiaomi.mirror".equals(context.getPackageName())
                || "com.oplus.linker".equals(context.getPackageName()))) throw new SecurityException("invalid_executor_context");
        if (!sessions.current(key)) throw new IOException("stale_session");
        if (clip == null || clip.getItemCount() != 1) throw new IllegalArgumentException("unsupported_clipboard_format");
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                || item.getText() != null || item.getHtmlText() != null || item.getIntent() != null)
            throw new IllegalArgumentException("unsupported_clipboard_format");
        // Names/MIME/declared size are the native source metadata. FileTransferSource validates
        // them and measures the provider bytes; its offer contains the actual length and SHA256.
        try {
            return new ClipboardUriTransfer(key, sessions,
                    FileTransferUris.snapshot(context, uri, displayName, mime, declaredSize, sessions, key));
        } catch (SecurityException denied) { throw new IOException("permission_denied"); }
        catch (IOException | RuntimeException failure) { throw new IOException("clipboard_uri_snapshot_failed"); }
    }

    FileTransferEngine.Key key() { return key; }
    FileTransferEngine.Offer offer() { return offer; }

    /** Caller obtains real recipient consent for key+offer before calling this. FileTransferEngine
     * independently rejects offers without that authorization. Keep this object open until the
     * returned future settles; close during upload requests cancellation, never claims rollback. */
    synchronized CompletableFuture<Uploaded> sendAfterReceiverConsent(FileTransferClient client) {
        Objects.requireNonNull(client);
        if (closed || started || source == null)
            return CompletableFuture.failedFuture(new IOException("clipboard_uri_source_consumed"));
        if (!current()) {
            try { close(); }
            catch (IOException cleanup) { return CompletableFuture.failedFuture(new IOException("clipboard_uri_cleanup_failed")); }
            return CompletableFuture.failedFuture(new IOException("stale_session"));
        }
        started = true;
        upload = new CompletableFuture<>();
        FileTransferSource owned = source;
        source = null; // FileTransferClient takes ownership, including rejection/cancellation.
        try {
            transport = Objects.requireNonNull(client.send(key, owned));
        } catch (RuntimeException failure) {
            source = owned;
            closed = true;
            String code = "clipboard_uri_send_failed";
            try { owned.close(); source = null; }
            catch (IOException cleanup) { code = "clipboard_uri_cleanup_failed"; }
            upload.completeExceptionally(new IOException(code));
            return upload;
        }
        upload.whenComplete((result, failure) -> {
            if (upload.isCancelled()) transport.cancel(false);
        });
        transport.whenComplete((receipt, error) -> {
            synchronized (ClipboardUriTransfer.this) {
                if (upload.isDone()) return;
                if (closed || !current()) upload.completeExceptionally(new IOException("stale_session"));
                else if (error != null) upload.completeExceptionally(new IOException(failureCode(error)));
                else if (receipt == null || !key.equals(receipt.key()) || offer.size() != receipt.size()
                        || !offer.sha256().equals(receipt.sha256()))
                    upload.completeExceptionally(new IOException("invalid_file_receipt"));
                else upload.complete(new Uploaded(key, offer));
            }
        });
        return upload;
    }

    private boolean current() {
        try { return sessions.current(key); }
        catch (RuntimeException unavailable) { return false; }
    }
    private static String failureCode(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        String code = error.getMessage();
        return code != null && Set.of("receive_consent_required", "consent_metadata_mismatch", "stale_session",
                "permission_denied", "file_busy", "file_cancelled", "request_timeout", "file_hash_mismatch",
                "file_length_exceeded", "native_file_hash_mismatch", "destination_cleanup_failed").contains(code)
                ? code : "clipboard_uri_upload_failed";
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        if (upload != null && !upload.isDone()) upload.cancel(false);
        if (transport != null && !transport.isDone()) transport.cancel(false);
        if (source != null) {
            // Do not lose the only ownership reference if deletion fails: another close retries.
            source.close(); source = null;
        }
    }
}
