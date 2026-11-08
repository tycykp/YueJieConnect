package io.github.qiangjian.connect;

import android.inputmethodservice.InputMethodService;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Binds remote text/keys to the already active ColorOS native synergy IME, after normal consent. */
public final class OplusScreenInputAdapter implements AutoCloseable {
    /** UID comes from the system's actual InputBinding, never from a remote input payload.
     * This describes an IME focus only; it cannot prove an arbitrary display's pointer focus. */
    public record NativeFocus(long epoch, int ownerUid, String packageName, int inputType) {
        @Override public String toString() { return "NativeImeFocus"; }
    }
    public static final String SERVICE = "com.oplus.linker.synergy.wisetransfer.inputservice.SynergyInputMethodService";
    private final XposedModule module;
    private final ClassLoader loader;
    private final List<HookHandle> hooks = new ArrayList<>();
    private InputMethodService service;
    private long epoch;
    private RemoteInputSession session;
    private RemoteInputSession.Scope boundScope;
    private boolean closed;

    public OplusScreenInputAdapter(XposedModule module, ClassLoader loader) { this.module = module; this.loader = loader; }
    public void install() throws ReflectiveOperationException {
        Class<?> type = Class.forName(SERVICE, false, loader);
        try {
            hooks.add(module.hook(type.getDeclaredMethod("onStartInput", EditorInfo.class, boolean.class))
                    .setId("qj.screen.oplus.ime.focus").intercept(chain -> {
                        Object value = chain.proceed();
                        invalidate(); if (!closed) service = (InputMethodService) chain.getThisObject();
                        return value;
                    }));
            for (String name : new String[]{"onFinishInput", "onDestroy"}) {
                hooks.add(module.hook(type.getDeclaredMethod(name)).setId("qj.screen.oplus.ime." + name).intercept(chain -> {
                    if (chain.getThisObject() == service) invalidate();
                    return chain.proceed();
                }));
            }
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    /** Return this focus identity to the peer; it expires on every native onStart/FinishInput. */
    public RemoteInputSession.Scope bind(String sessionId, long streamId, BooleanSupplier currentAndAllowed) {
        requireMain();
        if (currentFocus() == null || !currentAndAllowed.getAsBoolean()) throw new IllegalStateException("native_ime_unavailable");
        if (session != null) session.close();
        RemoteInputSession.Scope scope = new RemoteInputSession.Scope(sessionId, streamId, ++epoch);
        session = new RemoteInputSession(scope, currentAndAllowed, new RemoteInputImeTarget(service, epoch));
        boundScope = scope;
        return scope;
    }
    /** Local read on the native IME main thread. Do not use a top-app name as substitute focus. */
    public NativeFocus currentFocus() {
        requireMain();
        if (closed || service == null || !service.getCurrentInputStarted() || service.getCurrentInputConnection() == null) return null;
        InputBinding binding = service.getCurrentInputBinding();
        EditorInfo editor = service.getCurrentInputEditorInfo();
        if (binding == null || binding.getUid() < 0 || editor == null || editor.packageName == null || editor.packageName.isBlank()) return null;
        try {
            if (service.getPackageManager().getPackageUid(editor.packageName, 0) != binding.getUid()) return null;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { return null; }
        return new NativeFocus(epoch, binding.getUid(), editor.packageName, editor.inputType);
    }
    public RemoteInputSession.Result text(RemoteInputSession.Scope scope, long sequence, String text) {
        requireMain(); return session == null ? RemoteInputSession.Result.NO_FOCUS : session.text(scope, sequence, text);
    }
    public RemoteInputSession.Result key(RemoteInputSession.Scope scope, long sequence, int keyCode, boolean down) {
        requireMain(); return session == null ? RemoteInputSession.Result.NO_FOCUS : session.key(scope, sequence, keyCode, down);
    }
    /** Queued cleanup from an old stream must not cancel a newer native focus binding. */
    public void revoke(RemoteInputSession.Scope expected) {
        requireMain();
        if (expected != null && expected.equals(boundScope)) sessionChanged();
    }
    public void sessionChanged() { requireMain(); if (session != null) session.close(); session = null; boundScope = null; epoch++; }
    private void invalidate() { sessionChanged(); service = null; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("input_requires_main_thread");
    }
    @Override public void close() {
        requireMain(); closed = true; invalidate();
        for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
    }
}
