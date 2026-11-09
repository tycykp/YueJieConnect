package io.github.yuejie.connect;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

/** OEM app-private, excluded from backup, one owner across all processes of that package. */
final class NetworkShareRecoveryStore implements NetworkShareRecovery.Store {
    private final AtomicFile file;
    private final FileChannel channel;
    private final FileLock lock;
    NetworkShareRecoveryStore(Context context) throws IOException {
        File privateRoot = context.getNoBackupFilesDir();
        if (privateRoot == null) throw new IOException("network_recovery_directory");
        File directory = new File(privateRoot, "yuejie-network-share");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("network_recovery_directory");
        channel = FileChannel.open(new File(directory, "owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock acquired = channel.tryLock();
            if (acquired == null) throw new IOException("network_recovery_owner_busy");
            lock = acquired;
        } catch (IOException | OverlappingFileLockException error) {
            channel.close(); throw new IOException("network_recovery_owner_busy");
        }
        file = new AtomicFile(new File(directory, "resources.bin"));
    }
    @Override public byte[] read() throws IOException {
        if (!lock.isValid()) throw new IOException("network_recovery_owner_released");
        try (InputStream in = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[512]; int count;
            while ((count = in.read(block)) != -1) {
                if (bytes.size() + count > 4096) throw new IOException("network_recovery_corrupt");
                bytes.write(block, 0, count);
            }
            return bytes.toByteArray();
        } catch (FileNotFoundException absent) {
            if (file.getBaseFile().exists()) throw absent;
            return null;
        }
    }
    @Override public void write(byte[] bytes) throws IOException {
        if (!lock.isValid()) throw new IOException("network_recovery_owner_released");
        FileOutputStream out = file.startWrite();
        try {
            out.write(bytes); out.getFD().sync(); file.finishWrite(out);
            // AtomicFile.finishWrite logs rename failures instead of throwing. Verify the durable value.
            if (!java.util.Arrays.equals(bytes, read())) throw new IOException("network_recovery_write_unverified");
        } catch (IOException | RuntimeException error) { file.failWrite(out); throw error; }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
