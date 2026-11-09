package io.github.yuejie.connect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Immutable private snapshot: provider bytes cannot change between hashing and transmission. */
public final class FileTransferSource implements AutoCloseable {
    private final File file;
    private final FileTransferEngine.Offer offer;
    private boolean closed;

    private FileTransferSource(File file, FileTransferEngine.Offer offer) { this.file = file; this.offer = offer; }

    /** Takes ownership of input, including on validation/read failure. Declared size -1 means unknown. */
    public static FileTransferSource snapshot(File privateDirectory, InputStream input, String name,
                                               String mime, long declaredSize) throws IOException {
        File temp = null;
        try (InputStream source = input) {
            if (source == null) throw new IOException("source_unavailable");
            if (declaredSize < -1 || declaredSize > FileTransferEngine.MAX_BYTES) throw new IOException("file_size_limit");
            new FileTransferEngine.Offer(name, mime, 0, "0".repeat(64));
            Files.createDirectories(privateDirectory.toPath());
            temp = File.createTempFile("yj-out-", ".part", privateDirectory);
            MessageDigest digest = FileTransferEngine.sha256();
            long size = 0;
            try (FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    if (n == 0) throw new IOException("source_stalled");
                    size += n;
                    if (size > FileTransferEngine.MAX_BYTES || (declaredSize >= 0 && size > declaredSize))
                        throw new IOException("source_size_mismatch");
                    output.write(buffer, 0, n); digest.update(buffer, 0, n);
                }
                if (declaredSize >= 0 && size != declaredSize) throw new IOException("source_size_mismatch");
                output.getFD().sync();
            }
            return new FileTransferSource(temp, new FileTransferEngine.Offer(name, mime, size, FileTransferEngine.hex(digest.digest())));
        } catch (IOException | RuntimeException error) {
            if (temp != null) try { Files.deleteIfExists(temp.toPath()); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public FileTransferEngine.Offer offer() { return offer; }
    public synchronized InputStream open() throws IOException {
        if (closed) throw new IOException("source_closed");
        return Files.newInputStream(file.toPath());
    }
    @Override public synchronized void close() throws IOException { if (!closed) { Files.deleteIfExists(file.toPath()); closed = true; } }
}
