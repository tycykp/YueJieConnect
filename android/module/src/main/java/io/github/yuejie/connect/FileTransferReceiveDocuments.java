package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Private new files exposed by the OEM's existing non-exported, grant-capable FileProvider.
 * Creation requires a prior local native decision; the native consumer still owns actual URI grants. */
public final class FileTransferReceiveDocuments {
    private FileTransferReceiveDocuments() {}

    public static FileTransferReceiveLease<Uri> createTemporary(Context context, FileTransferEndpoint endpoint,
            FileTransferEngine.Key key, FileTransferEngine.Offer offer,
            FileTransferEngine.SessionGate nativeDecisionStillCurrent) throws IOException {
        return createTemporary(context, endpoint, key, offer, nativeDecisionStillCurrent,
                java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    public static FileTransferReceiveLease<Uri> createTemporary(Context context, FileTransferEndpoint endpoint,
            FileTransferEngine.Key key, FileTransferEngine.Offer offer,
            FileTransferEngine.SessionGate nativeDecisionStillCurrent,
            java.util.concurrent.CompletionStage<Void> nativeResult) throws IOException {
        String pkg = context.getPackageName();
        if (!pkg.equals("com.xiaomi.mirror") && !pkg.equals("com.oplus.linker"))
            throw new SecurityException("invalid_executor_context");
        FileTransferUris.requireCurrent(nativeDecisionStillCurrent, key);
        String authority = pkg.equals("com.xiaomi.mirror") ? "com.xiaomi.mirror.fileprovider"
                : "com.oplus.linker.devicemanager.fileprovider";
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !pkg.equals(provider.packageName) || provider.exported || !provider.grantUriPermissions
                || !"androidx.core.content.FileProvider".equals(provider.name)) throw new IOException("native_file_provider_unavailable");
        File root = new File(context.getCacheDir(), "yj-file-receive").getCanonicalFile();
        Files.createDirectories(root.toPath());
        String extension = offer.name().substring(offer.name().lastIndexOf('.') + 1);
        String suffix = extension.matches("[A-Za-z0-9]{1,16}") ? "." + extension : ".bin";
        File file = File.createTempFile("yj-receive-", suffix, root);
        Uri createdUri = null;
        Exception allocationError = null;
        try {
            Class<?> fileProvider = Class.forName("androidx.core.content.FileProvider", false, context.getClassLoader());
            createdUri = (Uri) fileProvider.getMethod("getUriForFile", Context.class, String.class, File.class, String.class)
                    .invoke(null, context, authority, file, offer.name());
            if (createdUri == null || !authority.equals(createdUri.getAuthority())) throw new IOException("native_file_uri_invalid");
        } catch (ReflectiveOperationException | RuntimeException | IOException failure) { createdUri = null; allocationError = failure; }
        final Uri uri = createdUri;
        final Exception createFailed = allocationError;
        FileTransferEngine.Sink sink = uri == null ? null : FileTransferUris.newDocumentSink(context, uri);
        FileTransferReceiveLease.Destination<Uri> destination = new FileTransferReceiveLease.Destination<>() {
            @Override public Uri value() { return uri; }
            @Override public void publishVerified(File staged, FileTransferEngine.Offer actual,
                    FileTransferEngine.SessionGate gate, FileTransferEngine.Key actualKey) throws IOException {
                sink.publishVerified(staged, actual, gate, actualKey);
            }
            @Override public void verify(FileTransferEngine.Offer actual, FileTransferEngine.SessionGate gate,
                    FileTransferEngine.Key actualKey) throws IOException { FileTransferUris.verify(context, uri, actual, gate, actualKey); }
            @Override public void rollback() throws IOException {
                // The path was exclusively created here. Direct idempotent deletion also works if
                // provider URI allocation failed; no pre-existing user object can enter this path.
                try {
                    if (uri != null) context.revokeUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Files.deleteIfExists(file.toPath());
                } catch (RuntimeException denied) { throw new IOException("destination_cleanup_failed", denied); }
            }
        };
        FileTransferReceiveLease<Uri> lease = new FileTransferReceiveLease<>(key, offer, nativeDecisionStillCurrent,
                destination, new FileTransferReceiveLease.Authorizer() {
                    @Override public java.util.concurrent.CompletableFuture<FileTransferEngine.Authorization> authorize(
                            FileTransferEngine.Key k, FileTransferEngine.Offer o, FileTransferEngine.Sink target) {
                        return createFailed != null ? java.util.concurrent.CompletableFuture.failedFuture(
                                new IOException("destination_create_failed", createFailed)) : endpoint.authorizeTracked(k, o, target, nativeResult);
                    }
                    @Override public java.util.concurrent.CompletableFuture<Void> revoke(FileTransferEngine.Key k) {
                        return endpoint.revoke(k);
                    }
                }, SystemClock::elapsedRealtime);
        // Lease retains cleanup ownership even if URI allocation failed or the route was revoked.
        lease.authorize();
        return lease;
    }
}
