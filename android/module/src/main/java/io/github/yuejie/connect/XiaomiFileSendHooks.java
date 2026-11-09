package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.Context;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Observes the actual locally initiated Mirror drag after its original URI conversion.
 * Control messages and native results run unchanged. A file receipt never generates FINISH. */
public final class XiaomiFileSendHooks implements AutoCloseable {
    public record NativeFile(String name, long size, int index) {}
    public interface Routes extends XiaomiFileAdapter.Authority {
        /** True only for the selected, currently streaming OEM SinkView. */
        default boolean screenCurrent(Object view) { return false; }
        /** The real local DROP authorizes the screen route's receive negotiation. */
        default void screenDropped(Object view, long nativeSession) {}
        /** Select only an authenticated, actually open native screen route. Null leaves the
         * entire drag on the OEM path. Allocation is local; this call must not perform I/O. */
        FileTransferEngine.Key select(Object owner, Object peer, long nativeSession,
                                      int screenId, NativeFile file);
        /** Always release the reservation, including when a later file could not be selected.
         * A receipt confirms actual file commit, not native drag success. error is fixed text. */
        void finished(FileTransferEngine.Key key, FileTransferEngine.Receipt receipt, String error);
    }
    private record Invocation(Object owner, ClipData clip) {}
    private final class Slot {
        final Object owner, peer;
        final String screenPeerId;
        final ClipData clip;
        final long session;
        final int screen, index;
        final XiaomiFileAdapter.LocalFile file;
        final FileTransferEngine.Key key;
        volatile boolean cancelled;
        Slot(Object owner, Object peer, ClipData clip, long session, int screen, int index,
                XiaomiFileAdapter.LocalFile file, FileTransferEngine.Key key, String screenPeerId) {
            this.owner = owner; this.peer = peer; this.clip = clip; this.session = session;
            this.screen = screen; this.index = index; this.file = file; this.key = key;
            this.screenPeerId = screenPeerId;
        }
        boolean current() {
            try {
                Object manager = connection.invoke(null);
                return !closed && !cancelled && slots.contains(this)
                        && (screenPeerId == null
                            ? peer == peerForId.invoke(manager, peerForScreen.invoke(manager, screen))
                            : routes.screenCurrent(owner) && screenPeerId.equals(screenDeviceId.invoke(owner))
                                && peer == peerForId.invoke(manager, screenPeerId))
                        && routes.current(owner, session, key);
            } catch (ReflectiveOperationException | RuntimeException stale) { return false; }
        }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final FileTransferSendCoordinator coordinator;
    private final Routes routes;
    private final XiaomiFileAdapter adapter;
    private final ThreadLocal<Invocation> invocation = new ThreadLocal<>();
    private final CopyOnWriteArrayList<Slot> slots = new CopyOnWriteArrayList<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "yj-xiaomi-file-source"); thread.setDaemon(true); return thread;
    });
    private Method connection, peerForScreen, peerForId;
    private Method screenDeviceId;
    private XiaomiScreenFileSendHooks screenHooks;
    private Field nativeSession, nativeClip, sourceUid;
    private volatile boolean closed;

    public XiaomiFileSendHooks(XposedModule module, Context context, ClassLoader loader,
            FileTransferSendCoordinator coordinator, Routes routes) throws ReflectiveOperationException {
        this.module = module; this.loader = loader; this.coordinator = coordinator; this.routes = routes;
        adapter = new XiaomiFileAdapter(context, loader, coordinator, (owner, session, key) -> {
            for (Slot slot : slots) if (slot.owner == owner && slot.session == session && slot.key.equals(key))
                return slot.current();
            return false;
        });
    }

    public synchronized void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("file_hooks_already_installed");
        Class<?> delegate = Class.forName("com.xiaomi.mirror.d0", false, loader);
        Class<?> drag = Class.forName("com.xiaomi.mirror.message.DragMessage", false, loader);
        Class<?> message = Class.forName("com.xiaomi.mirror.message.Message", false, loader);
        Class<?> fusion = Class.forName("q4.A", false, loader);
        Class<?> connections = Class.forName("com.xiaomi.mirror.connection.G", false, loader);
        nativeSession = field(delegate, "b"); nativeClip = field(delegate, "c"); sourceUid = field(delegate, "e");
        connection = connections.getMethod("J"); peerForScreen = connections.getMethod("J0", int.class);
        peerForId = connections.getMethod("t0", String.class);
        Field action = drag.getField("action"), screen = drag.getField("screenId"), translated = drag.getField("clipData");
        try {
            screenDeviceId = Class.forName("com.xiaomi.mirror.sink.SinkView", false, loader).getMethod("getDeviceId");
            screenHooks = new XiaomiScreenFileSendHooks(module, loader, new XiaomiScreenFileSendHooks.Routes() {
                public boolean current(Object view) { return !closed && routes.screenCurrent(view); }
                public void started(Object view, long session, int screen, ClipData clip, Object translated) {
                    try {
                        String id = (String) screenDeviceId.invoke(view);
                        if (id == null || id.isEmpty()) return;
                        Object peer = peerForId.invoke(connection.invoke(null), id);
                        if (peer != null) startSource(new Invocation(view, clip), session, screen, peer, translated, id);
                    } catch (Exception invalid) { cancel(view, session); }
                }
                public void dropped(Object view, long session) { routes.screenDropped(view, session); }
                public void cancelled(Object view, long session) { cancel(view, session); }
            });
            hooks.add(module.hook(delegate.getMethod("onDragStart", ClipData.class, int.class, int.class, int.class))
                    .setId("yj.xiaomi.files.send.owner").intercept(chain -> {
                if (closed) return chain.proceed();
                if (((Number) chain.getArg(2)).intValue() == android.os.Process.myPid()) return chain.proceed();
                Object owner = chain.getThisObject();
                cancel(owner, 0);
                Invocation previous = invocation.get();
                invocation.set(new Invocation(owner, (ClipData) chain.getArg(0)));
                try { return chain.proceed(); }
                catch (Throwable failure) { cancel(owner, 0); throw failure; }
                finally { if (previous == null) invocation.remove(); else invocation.set(previous); }
            }));
            hooks.add(module.hook(fusion.getMethod("P", message, long.class, String.class))
                    .setId("yj.xiaomi.files.send.start").intercept(chain -> {
                Object original = chain.getArg(0);
                if (closed || !drag.isInstance(original)) return chain.proceed();
                Invocation call = invocation.get();
                int kind = action.getInt(original);
                long session = ((Number) chain.getArg(1)).longValue();
                String peerId = (String) chain.getArg(2);
                if (kind == 0 && call != null) {
                    try { start(call, session, screen.getInt(original), peerId, translated.get(original)); }
                    catch (Exception invalid) { cancel(call.owner(), session); }
                } else if (kind == 5 || kind == 9) cancel(session, peerId);
                try { return chain.proceed(); }
                catch (Throwable failure) { if (call != null) cancel(call.owner(), session); throw failure; }
            }));
            hooks.add(module.hook(delegate.getMethod("a", drag, long.class))
                    .setId("yj.xiaomi.files.send.cancel").intercept(chain -> {
                int kind = action.getInt(chain.getArg(0));
                if (kind == 5 || kind == 9) cancel(chain.getThisObject(), ((Number) chain.getArg(1)).longValue());
                return chain.proceed();
            }));
            hooks.add(module.hook(delegate.getMethod("onDelegatePermissionReleased", List.class))
                    .setId("yj.xiaomi.files.send.release").intercept(chain -> {
                List<?> released = (List<?>) chain.getArg(0);
                for (Slot slot : slots) if (slot.owner == chain.getThisObject() && released != null
                        && released.contains(slot.file.uri())) cancel(slot);
                return chain.proceed();
            }));
            timer.scheduleWithFixedDelay(this::sessionChanged, 5, 5, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }

    private void start(Invocation call, long session, int screen, String peerId, Object translated) throws Exception {
        if (session <= 0 || nativeSession.getLong(call.owner()) != session
                || sourceUid.getInt(call.owner()) <= 0 || nativeClip.get(call.owner()) != call.clip()) return;
        Object manager = connection.invoke(null);
        if (peerId == null || !peerId.equals(peerForScreen.invoke(manager, screen))) return;
        Object peer = peerForId.invoke(manager, peerId);
        if (peer == null) return;
        startSource(call, session, screen, peer, translated, null);
    }

    private void startSource(Invocation call, long session, int screen, Object peer, Object translated,
            String screenPeerId) throws Exception {
        List<XiaomiFileAdapter.LocalFile> files = adapter.localFiles(call.clip(), translated);
        List<Slot> selected = new ArrayList<>();
        boolean reserved = false;
        try {
            for (int index = 0; index < files.size(); index++) {
                XiaomiFileAdapter.LocalFile file = files.get(index);
                FileTransferEngine.Key key = routes.select(call.owner(), peer, session, screen,
                        new NativeFile(file.name(), file.size(), index));
                if (key == null) return;
                if (selected.stream().anyMatch(s -> s.key.equals(key))
                        || slots.stream().anyMatch(s -> s.key.equals(key))) return;
                Slot slot = new Slot(call.owner(), peer, call.clip(), session, screen, index, file, key, screenPeerId);
                // Keep every reservation owned, even if the following validation fails.
                selected.add(slot);
                if (!routes.current(call.owner(), session, key)) return;
            }
            synchronized (slots) {
                if (closed || slots.size() + selected.size() > FileTransferEngine.MAX_TRANSFERS
                        || selected.stream().anyMatch(s -> slots.stream().anyMatch(old -> old.key.equals(s.key)))) return;
                slots.addAll(selected); reserved = true;
            }
            for (Slot slot : selected) {
                try {
                    adapter.send(slot.key, slot.owner, slot.session, call.clip(), translated, slot.index)
                            .whenComplete((receipt, error) -> finish(slot, receipt, error));
                } catch (Exception failure) { finish(slot, null, failure); }
            }
        } finally {
            if (!reserved) for (Slot slot : selected) report(slot.key, null, "native_file_route_unbound");
        }
    }

    private void finish(Slot slot, FileTransferEngine.Receipt receipt, Throwable error) {
        boolean valid = error == null && receipt != null && slot.current() && slot.key.equals(receipt.key())
                && slot.file.size() == receipt.size();
        if (slots.remove(slot)) report(slot.key, valid ? receipt : null,
                valid ? null : slot.cancelled ? "file_cancelled" : "file_transfer_failed");
    }
    private void report(FileTransferEngine.Key key, FileTransferEngine.Receipt receipt, String error) {
        try { routes.finished(key, receipt, error); }
        catch (RuntimeException unavailable) { android.util.Log.w("YueJieFiles", "file_source_route_release_failed"); }
    }
    private void cancel(Slot slot) { slot.cancelled = true; coordinator.cancel(slot.key); }
    private void cancel(Object owner, long session) {
        for (Slot slot : slots) if (slot.owner == owner && (session == 0 || slot.session == session)) cancel(slot);
    }
    private void cancel(long session, String peerId) {
        if (session <= 0 || peerId == null) return;
        try {
            Object peer = peerForId.invoke(connection.invoke(null), peerId);
            for (Slot slot : slots) if (slot.session == session && slot.peer == peer) cancel(slot);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { sessionChanged(); }
    }
    public void sessionChanged() {
        if (screenHooks != null) screenHooks.sessionChanged();
        for (Slot slot : slots) if (!slot.current()) cancel(slot);
    }
    @Override public synchronized void close() {
        closed = true; timer.shutdown();
        if (screenHooks != null) screenHooks.close();
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
        for (Slot slot : slots) cancel(slot);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
