package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Delivers an authenticated file to the real target of an OEM DROP through its original URI.
 * Does not manufacture Continuity packets, Drag/CrossFile FINISH, URI grants or native results. */
public final class XiaomiFileReceiveHooks implements AutoCloseable {
    public record NativeFile(Uri uri, String name, long size, int index) {}
    public record Incoming(FileTransferEngine.Key key, FileTransferEngine.Offer offer) {}
    public interface Routes {
        /** Joins this exact OEM drag file with negotiated, authenticated transfer metadata.
         * null leaves the whole drag on its original OEM transport. Never infer a peer from a URL. */
        Incoming select(Object delegate, Object nativePeer, long nativeSession, int screenId, NativeFile file);
        boolean current(Object delegate, Object nativePeer, long nativeSession, int screenId, FileTransferEngine.Key key);
        /** Publishes only the actual endpoint authorization result to the corresponding source owner. */
        void decision(Incoming incoming, CompletableFuture<Void> authorized);
    }
    private record Start(long session, ClipData clip, List<NativeFile> files) {}
    private final class Slot {
        final Object owner, peer;
        final long nativeSession;
        final int screen, targetUid;
        final NativeFile file;
        final Incoming incoming;
        final FileTransferNativeResult consumed = new FileTransferNativeResult();
        final CompletableFuture<FileTransferReceiveLease<Uri>> lease = new CompletableFuture<>();
        volatile boolean revoked;
        boolean claimed;
        Slot(Object owner, Object peer, long session, int screen, int uid, NativeFile file, Incoming incoming) {
            this.owner = owner; this.peer = peer; nativeSession = session; this.screen = screen;
            targetUid = uid; this.file = file; this.incoming = incoming;
        }
        boolean current() {
            try {
                Object manager = connection.invoke(null);
                return !closed && !revoked && routes.current(owner, peer, nativeSession, screen, incoming.key())
                    && peer == peerForId.invoke(manager, peerForScreen.invoke(manager, screen));
            }
            catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        }
        synchronized Uri destination(FileTransferReceiveLease<Uri> received) throws IOException {
            Uri uri = claimed ? received.reopenClaimed(incoming.key(), incoming.offer())
                    : received.claim(incoming.key(), incoming.offer());
            claimed = true;
            return uri;
        }
    }
    private final XposedModule module;
    private final Context context;
    private final ClassLoader loader;
    private final FileTransferEndpoint endpoint;
    private final Routes routes;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, Start> starts = new IdentityHashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private final java.util.concurrent.Semaphore providerOpens = new java.util.concurrent.Semaphore(4);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { Thread t = new Thread(r, "yj-xiaomi-file-receive"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yj-xiaomi-file-expiry"); t.setDaemon(true); return t;
    });
    private Field nativeSession, sourceUid, targetUid, nativeClip, dropped;
    private Method connection, peerForScreen, peerForId, resourceManager, localUri;
    private volatile boolean closed;

    public XiaomiFileReceiveHooks(XposedModule module, Context context, ClassLoader loader,
            FileTransferEndpoint endpoint, Routes routes) {
        if (!"com.xiaomi.mirror".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.module = module; this.context = context; this.loader = loader; this.endpoint = endpoint; this.routes = routes;
    }

    public synchronized void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("file_hooks_already_installed");
        Class<?> delegate = Class.forName("com.xiaomi.mirror.d0", false, loader);
        Class<?> drag = Class.forName("com.xiaomi.mirror.message.DragMessage", false, loader);
        Class<?> provider = Class.forName("com.xiaomi.mirror.provider.RemoteProvider", false, loader);
        Class<?> resources = Class.forName("V3.V", false, loader);
        Class<?> connections = Class.forName("com.xiaomi.mirror.connection.G", false, loader);
        nativeSession = field(delegate, "b"); nativeClip = field(delegate, "c");
        sourceUid = field(delegate, "e"); targetUid = field(delegate, "g");
        dropped = field(delegate, "j");
        connection = connections.getMethod("J"); peerForScreen = connections.getMethod("J0", int.class);
        peerForId = connections.getMethod("t0", String.class);
        resourceManager = resources.getMethod("G"); localUri = resources.getMethod("M", String.class);
        try {
            hooks.add(module.hook(delegate.getMethod("a", drag, long.class)).setId("yj.xiaomi.files.receive.drag").intercept(chain -> {
                if (closed) return chain.proceed();
                Object owner = chain.getThisObject(), message = chain.getArg(0);
                long session = ((Number) chain.getArg(1)).longValue();
                int action = drag.getField("action").getInt(message);
                int screen = drag.getField("screenId").getInt(message);
                if (action == 4) {
                    try { prepareDrop(owner, session, screen); }
                    catch (ReflectiveOperationException | RuntimeException invalid) { revoke(owner, session); }
                }
                if (action == 5 || (action == 9 && targetUid.getInt(owner) == 0)) revoke(owner, session);
                try {
                    Object result = chain.proceed();
                    if (action == 0) {
                        try { rememberStart(owner, session, drag.getField("clipData").get(message)); }
                        catch (ReflectiveOperationException | RuntimeException invalid) { revoke(owner, session); }
                    }
                    return result;
                } catch (Throwable failure) { revoke(owner, session); throw failure; }
            }));
            hooks.add(module.hook(delegate.getMethod("onDragResult", boolean.class)).setId("yj.xiaomi.files.receive.result").intercept(chain -> {
                Object owner = chain.getThisObject(); long session = nativeSession.getLong(owner);
                boolean wasDropped = dropped.getBoolean(owner);
                if (!((Boolean) chain.getArg(0))) revoke(owner, session);
                // A successful native callback is allowed to run unchanged, including its real FINISH.
                Object result = chain.proceed();
                if (wasDropped && session > 0) synchronized (slots) {
                    for (Slot slot : slots) if (slot.owner == owner && slot.nativeSession == session)
                        if (slot.current()) slot.consumed.nativeResult((Boolean) chain.getArg(0)); else slot.consumed.fail();
                }
                return result;
            }));
            hooks.add(module.hook(delegate.getMethod("onDelegatePermissionReleased", List.class))
                    .setId("yj.xiaomi.files.receive.release").intercept(chain -> {
                Object result = chain.proceed();
                List<?> released = (List<?>) chain.getArg(0);
                synchronized (slots) { for (Slot slot : slots) if (slot.owner == chain.getThisObject()
                        && released != null && released.contains(slot.file.uri())) slot.revoked = true; }
                scheduleMaintenance(); return result;
            }));
            hooks.add(module.hook(provider.getMethod("openFile", Uri.class, String.class, CancellationSignal.class))
                    .setId("yj.xiaomi.files.receive.open").intercept(chain -> {
                Uri uri = (Uri) chain.getArg(0);
                Slot slot = find(uri);
                if (slot == null) return chain.proceed();
                String mode = (String) chain.getArg(1);
                if (!"r".equals(mode) || Binder.getCallingUid() != slot.targetUid
                        || context.checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
                    throw new FileNotFoundException("native_file_grant_required");
                return open(slot, (CancellationSignal) chain.getArg(2));
            }));
            timer.scheduleWithFixedDelay(this::scheduleMaintenance, 5, 5, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    private void rememberStart(Object owner, long session, Object clipMessage) throws ReflectiveOperationException {
        revoke(owner, 0);
        if (session <= 0 || nativeSession.getLong(owner) != session || sourceUid.getInt(owner) != 0) return;
        ClipData clip = (ClipData) nativeClip.get(owner);
        if (clip == null || clipMessage == null) return;
        Object raw = clipMessage.getClass().getField("fileList").get(clipMessage);
        if (!(raw instanceof List<?> files) || files.isEmpty() || files.size() > FileTransferEngine.MAX_TRANSFERS) return;
        List<NativeFile> copy = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Object file = files.get(i);
            String name = (String) file.getClass().getField("name").get(file);
            long size = file.getClass().getField("size").getLong(file);
            Uri uri = (Uri) localUri.invoke(resourceManager.invoke(null), file.getClass().getField("url").get(file));
            if (!"content".equals(uri.getScheme()) || !"com.xiaomi.mirror.remoteprovider".equals(uri.getAuthority())
                    || copy.stream().anyMatch(f -> f.uri().equals(uri))) return;
            boolean present = false;
            for (int at = 0; at < clip.getItemCount(); at++) present |= uri.equals(clip.getItemAt(at).getUri());
            if (!present) return;
            copy.add(new NativeFile(uri, name, size, i));
        }
        synchronized (starts) { if (starts.size() < 4) starts.put(owner, new Start(session, clip, List.copyOf(copy))); }
    }

    private void prepareDrop(Object owner, long session, int screen) throws ReflectiveOperationException {
        Start start;
        synchronized (starts) { start = starts.remove(owner); }
        if (start == null || start.session() != session || nativeSession.getLong(owner) != session
                || sourceUid.getInt(owner) != 0 || nativeClip.get(owner) != start.clip() || targetUid.getInt(owner) <= 0) return;
        Object manager = connection.invoke(null);
        Object peer = peerForId.invoke(manager, peerForScreen.invoke(manager, screen));
        if (peer == null) return;
        List<Slot> selected = new ArrayList<>();
        for (NativeFile file : start.files()) {
            Incoming incoming = routes.select(owner, peer, session, screen, file);
            if (incoming == null) return; // Never steal the unmatched part of an OEM drag.
            if (!file.name().equals(incoming.offer().name()) || file.size() != incoming.offer().size()
                    || selected.stream().anyMatch(s -> s.incoming.key().equals(incoming.key()))) return;
            Slot slot = new Slot(owner, peer, session, screen, targetUid.getInt(owner), file, incoming);
            if (!slot.current()) return;
            selected.add(slot);
        }
        synchronized (slots) {
            if (slots.size() + selected.size() > FileTransferEngine.MAX_TRANSFERS
                    || selected.stream().anyMatch(s -> slots.stream().anyMatch(old -> old.file.uri().equals(s.file.uri())
                        || old.incoming.key().equals(s.incoming.key())))) return;
            slots.addAll(selected);
        }
        for (Slot slot : selected) {
            CompletableFuture<Void> authorized = slot.lease.thenCompose(FileTransferReceiveLease::accepted);
            routes.decision(slot.incoming, authorized);
            try { worker.execute(() -> {
                try {
                    if (!slot.current()) throw new IOException("stale_session");
                    slot.lease.complete(FileTransferReceiveDocuments.createTemporary(context, endpoint,
                            slot.incoming.key(), slot.incoming.offer(), k -> slot.current(), slot.consumed.completion()));
                } catch (Exception failure) { slot.lease.completeExceptionally(new IOException("native_receive_failed")); }
            }); } catch (RuntimeException busy) { slot.lease.completeExceptionally(new IOException("file_busy")); }
        }
    }

    private ParcelFileDescriptor open(Slot slot, CancellationSignal signal) throws FileNotFoundException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new FileNotFoundException("file_worker_required");
        if (!providerOpens.tryAcquire()) throw new FileNotFoundException("file_busy");
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + FileTransferReceiveLease.RECEIVE_MILLIS;
            FileTransferReceiveLease<Uri> lease = await(slot.lease, slot, signal, deadline);
            // Once accepted, the engine's progress-refreshed inactivity timer owns the receive
            // deadline. An actively advancing large transfer may exceed the first 60 seconds.
            await(lease.committed(), slot, signal, Long.MAX_VALUE);
            // Original target UID and READ grant were checked before entering open(). The new
            // private URI belongs to this provider owner; it is never granted to the target.
            // A local ContentProvider call can retain the incoming Binder identity otherwise.
            long identity = Binder.clearCallingIdentity();
            try {
                Uri destination = slot.destination(lease);
                if (!slot.current()) throw new IOException("stale_session");
                ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(destination, "r", signal);
                if (descriptor == null) throw new IOException("destination_unreadable");
                if (!slot.current() || (signal != null && signal.isCanceled())) { descriptor.close(); throw new IOException("stale_session"); }
                slot.consumed.descriptorOpened();
                return descriptor; // Real provider FD; the native target performs the read and native result.
            } finally { Binder.restoreCallingIdentity(identity); }
        } catch (Exception failed) {
            slot.revoked = true; slot.consumed.fail(); scheduleMaintenance();
            throw new FileNotFoundException("native_file_receive_failed");
        } finally { providerOpens.release(); }
    }
    private static <T> T await(CompletableFuture<T> future, XiaomiFileReceiveHooks.Slot slot,
            CancellationSignal signal, long deadline) throws Exception {
        while (true) {
            if (!slot.current() || (signal != null && signal.isCanceled())) throw new IOException("file_cancelled");
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("request_timeout");
            try { return future.get(100, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waiting) { }
        }
    }
    private Slot find(Uri uri) {
        synchronized (slots) { for (Slot slot : slots) if (slot.file.uri().equals(uri)) return slot; }
        return null;
    }
    private void revoke(Object owner, long session) {
        synchronized (starts) { Start start = starts.get(owner); if (start != null && (session == 0 || start.session() == session)) starts.remove(owner); }
        synchronized (slots) { for (Slot slot : slots) if (slot.owner == owner && (session == 0 || slot.nativeSession == session)) {
            slot.revoked = true; slot.consumed.fail();
        } }
        scheduleMaintenance();
    }
    public void sessionChanged() { scheduleMaintenance(); }
    private void scheduleMaintenance() {
        try { worker.execute(this::maintain); } catch (RuntimeException busy) { /* Timer/next owner event retries. */ }
    }
    private void maintain() {
        List<Slot> snapshot;
        synchronized (slots) { snapshot = List.copyOf(slots); }
        for (Slot slot : snapshot) {
            if (!slot.lease.isDone()) continue;
            if (slot.lease.isCompletedExceptionally()) { synchronized (slots) { slots.remove(slot); } continue; }
            FileTransferReceiveLease<Uri> lease = slot.lease.getNow(null);
            try { if (!slot.current()) { slot.consumed.fail(); lease.close(); } else lease.maintain(); }
            catch (IOException cleanup) { android.util.Log.w("YueJieFiles", "temporary_receive_cleanup_pending"); }
            if (lease.disposed()) synchronized (slots) { slots.remove(slot); }
        }
        if (closed) { synchronized (slots) { if (slots.isEmpty()) { timer.shutdown(); worker.shutdown(); } } }
    }
    @Override public synchronized void close() {
        closed = true;
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        synchronized (starts) { starts.clear(); }
        scheduleMaintenance(); // Retry failed deletes on the existing timer until ownership is released.
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
