package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** Disposable local application icon, exposed through the OEM's existing grant-capable provider. */
final class AppRelayDirectoryIcon implements AutoCloseable {
    Uri uri;
    private final Context context;
    private final File file;
    private AppRelayDirectoryIcon(Context context, File file, Uri uri) { this.context = context; this.file = file; this.uri = uri; }
    static Bitmap bitmap(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        android.graphics.Rect bounds = new android.graphics.Rect(drawable.getBounds());
        try { drawable.setBounds(0, 0, 128, 128); drawable.draw(new Canvas(bitmap)); }
        finally { drawable.setBounds(bounds); }
        return bitmap;
    }
    static AppRelayDirectoryIcon create(Context context, ClassLoader loader, String pkg,
            java.util.function.Consumer<AppRelayDirectoryIcon> cleanupOwner) throws Exception {
        String authority = "com.oplus.linker.devicemanager.fileprovider";
        var provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !context.getPackageName().equals(provider.packageName) || provider.exported || !provider.grantUriPermissions
                || !"androidx.core.content.FileProvider".equals(provider.name)) throw new IOException("native_file_provider_unavailable");
        File file = File.createTempFile("yj-relay-icon-", ".png", context.getCacheDir());
        AppRelayDirectoryIcon owned = new AppRelayDirectoryIcon(context, file, null);
        cleanupOwner.accept(owned); // Preserve cleanup ownership even if URI allocation/grant fails.
        Bitmap bitmap = bitmap(context.getPackageManager().getApplicationIcon(pkg));
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("native_icon_unavailable");
        } finally { bitmap.recycle(); }
        Class<?> fileProvider = Class.forName("androidx.core.content.FileProvider", false, loader);
        owned.uri = (Uri) fileProvider.getMethod("getUriForFile", Context.class, String.class, File.class, String.class)
                .invoke(null, context, authority, file, "Relay application icon.png");
        if (owned.uri == null || !authority.equals(owned.uri.getAuthority())) throw new IOException("native_file_uri_invalid");
        context.grantUriPermission("com.android.launcher", owned.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return owned;
    }
    @Override public void close() throws IOException {
        try { if (uri != null) context.revokeUriPermission("com.android.launcher", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (RuntimeException failure) { throw new IOException("native_icon_cleanup_failed", failure); }
        Files.deleteIfExists(file.toPath());
    }
}
