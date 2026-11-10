package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Native, grant-bearing URI handles. Only an actual permitted provider open makes a receive decision.
 * No bytes are authorized while staging metadata; unrelated OEM URIs always retain their native path. */
final class ClipboardUriPasteHooks implements AutoCloseable {
    record Prepared(FileTransferEngine.Key key, FileTransferEngine.Offer offer, String ownerToken) {
        Prepared {
            Objects.requireNonNull(key); Objects.requireNonNull(offer);
            if (ownerToken == null || !ownerToken.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("invalid_native_owner_token");
        }
        String request() { return "uri:" + key.transfer(); }
    }
    record Caller(int uid, int pid, String packageName) {}
    /** Integration retains all lease cleanup ownership, including failed allocation/attach and revocation.
     * applied is the actual ClipboardUriRoutes result, not the file engine's commit receipt. */
    record Received(FileTransferReceiveLease<Uri> lease, CompletableFuture<ClipboardUriReceiver.Result> applied) {}
    interface Routes {
        boolean current(Prepared prepared);
        CompletableFuture<Received> receive(Prepared prepared, Caller caller);
        void revoke(Prepared prepared);
        default void delivered(Prepared prepared, Caller caller) {}
    }
    private static final class Slot {
        final Prepared prepared;
        final Uri uri;
        final long expires = SystemClock.elapsedRealtime() + 300_000;
        volatile boolean revoked;
        volatile Caller caller;
        CompletableFuture<Received> receiving;
        Slot(Prepared prepared, Uri uri) { this.prepared = prepared; this.uri = uri; }
    }
    private final XposedModule module;
    private final Context context;
    private final ClassLoader loader;
    private final ClipboardNativeContent endpoint;
    private final ClipboardEchoGuard echoes;
    private final Routes routes;
    private final int source;
    private final String authority, providerName;
    private final List<Slot> slots = new ArrayList<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Semaphore opens = new Semaphore(4);
    private Class<?> providerType;
    private volatile boolean closed;

    ClipboardUriPasteHooks(XposedModule module, Context context, ClassLoader loader, int source,
            ClipboardNativeContent endpoint, ClipboardEchoGuard echoes, Routes routes) {
        if (source != 1 && source != 2 || context == null || !(source == 1 ? "com.xiaomi.mirror" : "com.oplus.linker").equals(context.getPackageName()))
            throw new SecurityException("invalid_executor_context");
        this.module = module; this.context = context; this.loader = loader; this.source = source;
        this.endpoint = Objects.requireNonNull(endpoint); this.echoes = Objects.requireNonNull(echoes); this.routes = Objects.requireNonNull(routes);
        authority = source == 1 ? "com.xiaomi.mirror.remoteprovider" : "com.oplus.linker.clipboard";
        providerName = source == 1 ? "com.xiaomi.mirror.provider.RemoteProvider"
                : "com.oplus.linker.synergy.wisetransfer.clipboard.provider.ClipboardProvider";
    }

    void install() throws ReflectiveOperationException {
        main();
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("clipboard_uri_lifecycle");
        ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
        if (info == null || !context.getPackageName().equals(info.packageName) || !providerName.equals(info.name)
                || !info.grantUriPermissions || info.exported != (source == 1)) throw new SecurityException("native_clipboard_provider_unavailable");
        providerType = Class.forName(providerName, false, loader);
        try {
            for (boolean cancellation : new boolean[]{false, true}) {
                Method open = cancellation ? providerType.getMethod("openFile", Uri.class, String.class, CancellationSignal.class)
                        : providerType.getMethod("openFile", Uri.class, String.class);
                hooks.add(module.hook(open).setId("yj.clipboard.uri.open." + cancellation)
                        .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Slot slot = find(chain.getThisObject(), (Uri) chain.getArg(0));
                    if (slot == null) { if (owned(chain.getThisObject(), (Uri) chain.getArg(0))) throw denied(); return chain.proceed(); }
                    return open(slot, (ContentProvider) chain.getThisObject(), (String) chain.getArg(1),
                            cancellation ? (CancellationSignal) chain.getArg(2) : null);
                }));
            }
            // ContentResolver.openInputStream uses the asset API. OEM overrides need not
            // delegate to openFile; handle our URI at each public provider read entry.
            for (boolean cancellation : new boolean[]{false, true}) {
                Method asset = cancellation ? providerType.getMethod("openAssetFile", Uri.class, String.class, CancellationSignal.class)
                        : providerType.getMethod("openAssetFile", Uri.class, String.class);
                hooks.add(module.hook(asset).setId("yj.clipboard.uri.asset." + cancellation)
                        .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Uri uri = (Uri) chain.getArg(0);
                    Slot slot = find(chain.getThisObject(), uri);
                    if (slot == null) { if (owned(chain.getThisObject(), uri)) throw denied(); return chain.proceed(); }
                    return new AssetFileDescriptor(open(slot, (ContentProvider) chain.getThisObject(), (String) chain.getArg(1),
                            cancellation ? (CancellationSignal) chain.getArg(2) : null), 0, slot.prepared.offer().size());
                }));
                Method typed = cancellation ? providerType.getMethod("openTypedAssetFile", Uri.class, String.class, android.os.Bundle.class, CancellationSignal.class)
                        : providerType.getMethod("openTypedAssetFile", Uri.class, String.class, android.os.Bundle.class);
                hooks.add(module.hook(typed).setId("yj.clipboard.uri.typed." + cancellation)
                        .setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Uri uri = (Uri) chain.getArg(0);
                    Slot slot = find(chain.getThisObject(), uri);
                    if (slot == null) { if (owned(chain.getThisObject(), uri)) throw denied(); return chain.proceed(); }
                    String filter = (String) chain.getArg(1);
                    if (filter == null || !android.content.ClipDescription.compareMimeTypes(slot.prepared.offer().mime(), filter)) throw denied();
                    return new AssetFileDescriptor(open(slot, (ContentProvider) chain.getThisObject(), "r",
                            cancellation ? (CancellationSignal) chain.getArg(3) : null), 0, slot.prepared.offer().size());
                }));
            }
            hooks.add(module.hook(providerType.getMethod("getType", Uri.class)).setId("yj.clipboard.uri.type").intercept(chain -> {
                Slot slot = find(chain.getThisObject(), (Uri) chain.getArg(0));
                return slot == null ? owned(chain.getThisObject(), (Uri) chain.getArg(0)) ? null : chain.proceed()
                        : current(slot) ? slot.prepared.offer().mime() : null;
            }));
            for (boolean cancellation : new boolean[]{false, true}) {
                Method query = cancellation ? providerType.getMethod("query", Uri.class, String[].class, String.class, String[].class, String.class, CancellationSignal.class)
                        : providerType.getMethod("query", Uri.class, String[].class, String.class, String[].class, String.class);
                hooks.add(module.hook(query).setId("yj.clipboard.uri.query." + cancellation).intercept(chain -> {
                Slot slot = find(chain.getThisObject(), (Uri) chain.getArg(0));
                if (slot == null) return owned(chain.getThisObject(), (Uri) chain.getArg(0)) ? null : chain.proceed();
                return query(slot, (String[]) chain.getArg(1));
                }));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    /** Called by authenticated file_prepare purpose=clipboard routing, on native main.
     * true means metadata reached the native clipboard, never that file bytes were delivered. */
    boolean stage(Prepared prepared) throws ReflectiveOperationException {
        main();
        if (closed || hooks.isEmpty() || !routes.current(prepared)) return false;
        final Slot slot;
        synchronized (slots) {
            slots.removeIf(old -> { if (current(old)) return false; if (!old.revoked) { old.revoked = true; routes.revoke(old.prepared); } return true; });
            for (Slot old : slots) if (old.prepared.key().equals(prepared.key())) return old.prepared.equals(prepared) && current(old);
            if (slots.size() >= 4) return false;
            for (Slot old : slots) if (old.prepared.ownerToken().equals(prepared.ownerToken())) return false;
            Uri uri = new Uri.Builder().scheme("content").authority(authority).appendPath("yj-clipboard").appendPath(prepared.ownerToken())
                    .appendQueryParameter("source", "clipboard").build();
            slot = new Slot(prepared, uri); slots.add(slot);
        }
        // Do not hold the table monitor across the native sink/system clipboard Binder call.
        echoes.armUri(prepared.key().session(), slot.uri, prepared.offer().mime());
        try { ClipData actual = endpoint.stageLocalUri(slot.uri, prepared.offer().mime()); echoes.seal(actual); }
        catch (ReflectiveOperationException | RuntimeException failure) { slot.revoked = true; echoes.clear(); routes.revoke(prepared); throw failure; }
        return current(slot);
    }

    boolean current(Prepared prepared) {
        synchronized (slots) { for (Slot slot : slots) if (slot.prepared.equals(prepared)) return current(slot); }
        return false;
    }
    boolean onClipboard(Prepared prepared) {
        main();
        Slot match = null;
        synchronized (slots) {
            for (Slot slot : slots) if (slot.prepared.equals(prepared)) { match = slot; break; }
        }
        return match != null && current(match) && endpoint.hasLocalUri(match.uri, prepared.offer().mime());
    }
    boolean permitted(Prepared prepared, Caller caller) {
        synchronized (slots) {
            for (Slot slot : slots) if (slot.prepared.equals(prepared))
                return slot.caller != null && slot.caller.uid() == caller.uid() && readable(slot, caller);
        }
        return false;
    }

    private Slot find(Object provider, Uri uri) {
        if (providerType == null || !providerType.isInstance(provider) || uri == null) return null;
        synchronized (slots) { for (Slot slot : slots) if (slot.uri.equals(uri)) return slot; }
        return null;
    }
    private boolean owned(Object provider, Uri uri) {
        return providerType != null && providerType.isInstance(provider) && uri != null && authority.equals(uri.getAuthority())
                && uri.getPathSegments().size() > 0 && "yj-clipboard".equals(uri.getPathSegments().get(0));
    }
    private boolean current(Slot slot) {
        try { return !closed && !slot.revoked && SystemClock.elapsedRealtime() < slot.expires && routes.current(slot.prepared); }
        catch (RuntimeException unavailable) { return false; }
    }
    private MatrixCursor query(Slot slot, String[] projection) {
        String[] requested = projection == null ? new String[]{"_display_name", "_size", "mime_type"} : projection;
        if (requested.length > 64) throw new IllegalArgumentException("invalid_clipboard_query");
        List<String> columns = new ArrayList<>(); List<Object> values = new ArrayList<>();
        for (String column : requested) {
            if ("_display_name".equals(column)) { columns.add(column); values.add(slot.prepared.offer().name()); }
            else if ("_size".equals(column)) { columns.add(column); values.add(slot.prepared.offer().size()); }
            else if ("mime_type".equals(column)) { columns.add(column); values.add(slot.prepared.offer().mime()); }
        }
        MatrixCursor result = new MatrixCursor(columns.toArray(new String[0]));
        if (current(slot)) result.addRow(values.toArray());
        return result;
    }

    private ParcelFileDescriptor open(Slot slot, ContentProvider provider, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        if (!"r".equals(mode) || Looper.myLooper() == Looper.getMainLooper() || cancellation != null && cancellation.isCanceled()) throw denied();
        Caller caller = new Caller(Binder.getCallingUid(), Binder.getCallingPid(), provider.getCallingPackage());
        if (caller.uid() == android.os.Process.myUid() || caller.pid() == android.os.Process.myPid() || caller.packageName() == null
                || !readable(slot, caller)) throw denied();
        // This slot serves a module-owned URI. The OS grant authorizes its consumer.
        // Stock provider calls, including their own package policy, proceed unchanged.
        if (!opens.tryAcquire()) throw denied();
        boolean decided = false;
        try {
            CompletableFuture<Received> receiving;
            synchronized (slot) {
                if (!current(slot)) throw denied();
                if (slot.receiving == null) {
                    decided = true;
                    slot.caller = caller; slot.receiving = new CompletableFuture<>();
                    try {
                        Objects.requireNonNull(routes.receive(slot.prepared, caller)).whenComplete((received, failure) -> {
                            if (failure == null) slot.receiving.complete(received); else slot.receiving.completeExceptionally(failure);
                        });
                    } catch (RuntimeException failure) { slot.receiving.completeExceptionally(failure); }
                }
                receiving = slot.receiving;
            }
            Received received = waitFor(receiving, slot, caller, cancellation);
            if (received == null || received.lease() == null || received.applied() == null
                    || !slot.prepared.key().equals(received.lease().key()) || !slot.prepared.offer().equals(received.lease().offer())) throw denied();
            ClipboardUriReceiver.Result result = waitFor(received.applied(), slot, caller, cancellation);
            // Full identity and native application receipt, not the file commit future.
            ClipboardUriCodec.applied(slot.prepared.key(), slot.prepared.request(), slot.prepared.offer(), result);
            if (!readable(slot, caller)) throw denied();
            long identity = Binder.clearCallingIdentity();
            ParcelFileDescriptor descriptor;
            try {
                Uri local = received.lease().reopenClaimed(slot.prepared.key(), slot.prepared.offer());
                descriptor = context.getContentResolver().openFileDescriptor(local, "r");
            } finally { Binder.restoreCallingIdentity(identity); }
            if (descriptor == null) throw denied();
            if (!readable(slot, caller) || cancellation != null && cancellation.isCanceled()) { descriptor.close(); throw denied(); }
            routes.delivered(slot.prepared, caller);
            return descriptor;
        } catch (Exception failure) {
            if (decided && !slot.revoked) { slot.revoked = true; routes.revoke(slot.prepared); }
            throw denied();
        }
        finally { opens.release(); }
    }
    private <T> T waitFor(CompletableFuture<T> future, Slot slot, Caller caller, CancellationSignal signal) throws Exception {
        while (current(slot) && readable(slot, caller) && (signal == null || !signal.isCanceled())) {
            try { return future.get(250, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* Only reads; no retry of receive/authorize or transport operations. */ }
        }
        throw denied();
    }
    private boolean readable(Slot slot, Caller caller) {
        return current(slot) && context.checkUriPermission(slot.uri, caller.pid(), caller.uid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }
    private static FileNotFoundException denied() { return new FileNotFoundException("clipboard_uri_paste_unavailable"); }
    void sessionChanged() {
        main(); synchronized (slots) { for (Slot slot : slots) if (!current(slot) && !slot.revoked) { slot.revoked = true; routes.revoke(slot.prepared); } }
    }
    private static void main() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_requires_main"); }
    @Override public void close() {
        main(); closed = true;
        synchronized (slots) { for (Slot slot : slots) if (!slot.revoked) { slot.revoked = true; routes.revoke(slot.prepared); } }
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
    }
}
