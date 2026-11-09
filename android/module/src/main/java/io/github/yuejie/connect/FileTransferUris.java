package io.github.yuejie.connect;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Local-only URI API shared by native file and clipboard adapters. No URI crosses the wire. */
public final class FileTransferUris {
    private FileTransferUris() {}

    /** Call in the process that holds the original source URI read permission. */
    public static FileTransferSource snapshot(Context context, Uri locallyGrantedUri, String displayName,
                                               String mime, long size) throws IOException {
        requireContent(locallyGrantedUri);
        return FileTransferSource.snapshot(new File(context.getCacheDir(), "yj-file-out"),
                context.getContentResolver().openInputStream(locallyGrantedUri), displayName, mime, size);
    }

    /** Rechecks the native owner/session while copying a potentially large granted URI. */
    public static FileTransferSource snapshot(Context context, Uri locallyGrantedUri, String displayName,
            String mime, long size, FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
        requireContent(locallyGrantedUri);
        requireCurrent(gate, key);
        InputStream input = context.getContentResolver().openInputStream(locallyGrantedUri);
        if (input == null) throw new IOException("source_unavailable");
        return FileTransferSource.snapshot(new File(context.getCacheDir(), "yj-file-out"),
                new FilterInputStream(input) {
                    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                        requireCurrent(gate, key);
                        int result = in.read(bytes, offset, count);
                        requireCurrent(gate, key);
                        return result;
                    }
                    @Override public int read() throws IOException {
                        requireCurrent(gate, key);
                        int result = in.read();
                        requireCurrent(gate, key);
                        return result;
                    }
                }, displayName, mime, size);
    }

    /** Only an explicitly created, locally authorized new URI may be used: rollback deletes it. */
    public static FileTransferEngine.Sink newDocumentSink(Context context, Uri newDestination) {
        requireContent(newDestination);
        return new FileTransferEngine.Sink() {
            @Override public void publishVerified(File staged, FileTransferEngine.Offer offer,
                                                  FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
                try (InputStream input = java.nio.file.Files.newInputStream(staged.toPath());
                     OutputStream output = context.getContentResolver().openOutputStream(newDestination, "wt")) {
                    if (output == null) throw new IOException("destination_unavailable");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        requireCurrent(gate, key);
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                verify(context, newDestination, offer, gate, key);
            }
            @Override public void rollback() throws IOException { deleteNew(context, newDestination); }
        };
    }

    static void verify(Context context, Uri destination, FileTransferEngine.Offer offer,
                       FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
        MessageDigest digest = FileTransferEngine.sha256();
        long size = 0;
        try (InputStream input = context.getContentResolver().openInputStream(destination)) {
            if (input == null) throw new IOException("destination_unreadable");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                requireCurrent(gate, key);
                if (count == 0) throw new IOException("destination_stalled");
                size += count;
                if (size > offer.size()) throw new IOException("native_file_length_mismatch");
                digest.update(buffer, 0, count);
            }
        }
        if (size != offer.size() || !offer.sha256().equals(FileTransferEngine.hex(digest.digest())))
            throw new IOException("native_file_hash_mismatch");
        requireCurrent(gate, key);
    }

    static void deleteNew(Context context, Uri destination) throws IOException {
        try {
            if (android.provider.DocumentsContract.isDocumentUri(context, destination)) {
                if (!android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), destination))
                    throw new IOException("destination_cleanup_failed");
            } else if (context.getContentResolver().delete(destination, null, null) < 1) {
                throw new IOException("destination_cleanup_failed");
            }
        } catch (RuntimeException error) { throw new IOException("destination_cleanup_failed", error); }
    }

    static void requireCurrent(FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
        if (!gate.current(key)) throw new IOException("stale_session");
    }
    static void requireContent(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null)
            throw new IllegalArgumentException("local_content_uri_required");
    }
}
