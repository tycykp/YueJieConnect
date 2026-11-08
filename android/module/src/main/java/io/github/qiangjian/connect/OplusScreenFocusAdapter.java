package io.github.qiangjian.connect;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Observes the real PAD Mirage callback, already filtered by OEM bus/display ownership.
 * Never infers input focus from the capture display, a package name or a network payload.
 * Actual injection still uses the OS target-UID overload in RemoteInputDesktopTarget. */
public final class OplusScreenFocusAdapter implements AutoCloseable {
    public record Route(ScreenCastSession.Scope scope, BooleanSupplier renderedStreamAndInputAllowed) {
        public Route { Objects.requireNonNull(scope); Objects.requireNonNull(renderedStreamAndInputAllowed); }
    }
    public interface Routes {
        /** Match this exact PadCastDisplayManager and bus to its authenticated native request.
         * Null is unrelated/unready; never select the only online peer by default. */
        Route resolve(Object actualPadManager, String actualBusId);
    }
    public interface Listener {
        void focused(NativeFocus focus);
        void revoked(NativeFocus focus);
    }
    public record NativeFocus(Object nativeOwner, ScreenCastSession.Scope scope,
                              RemoteInputDesktopSession.Focus focus, BooleanSupplier nativeCurrent) {
        public boolean current() {
            try { return nativeCurrent.getAsBoolean(); } catch (RuntimeException error) { return false; }
        }
        @Override public String toString() { return "NativePadInputFocus"; }
    }
    record DisplayState(String busId, int displayId, int width, int height) { }
    interface NativeState { DisplayState read(Object actualManager, int actualDisplayId); }
    private record Binding(NativeFocus focus, AtomicBoolean live, BooleanSupplier nativeCurrent, AtomicBoolean announced) { }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Routes routes;
    private final Listener listener;
    private final NativeState nativeState;
    private final Map<Object, Binding> bindings = new IdentityHashMap<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private Field callbackOwner;
    private long epoch;
    private volatile boolean closed;

    public OplusScreenFocusAdapter(XposedModule module,ClassLoader loader,Context context,Routes routes,Listener listener)
            throws ReflectiveOperationException {
        this.module=Objects.requireNonNull(module); this.loader=Objects.requireNonNull(loader);
        this.routes=Objects.requireNonNull(routes); this.listener=Objects.requireNonNull(listener);
        nativeState=androidState(Objects.requireNonNull(context),loader);
    }
    /** Controlled native boundary used only by host assembly checks. */
    OplusScreenFocusAdapter(Routes routes,Listener listener,NativeState nativeState) {
        module=null; loader=null; this.routes=routes; this.listener=listener; this.nativeState=nativeState;
    }
    private static NativeState androidState(Context context,ClassLoader loader) throws ReflectiveOperationException {
        String base="com.oplus.cast.engine.impl.odmf.display.castdisplaymanager.";
        Class<?> manager=Class.forName(base+"CastDisplayManager",false,loader);
        Class<?> pad=Class.forName(base+"PadCastDisplayManager",false,loader);
        Field bus=manager.getDeclaredField("g"),released=manager.getDeclaredField("q"); bus.setAccessible(true); released.setAccessible(true);
        if(bus.getType()!=String.class || released.getType()!=boolean.class) throw new NoSuchFieldException("native_pad_focus_field_changed");
        Class<?> directory=Class.forName("u2.i",false,loader);
        Object actualDirectory=directory.getMethod("q").invoke(null);
        Method mirage=directory.getMethod("s",String.class);
        Method width=directory.getMethod("v",String.class),height=directory.getMethod("u",String.class);
        DisplayManager displays=context.getSystemService(DisplayManager.class);
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        return (owner,displayId) -> {
            try {
                if(owner==null || owner.getClass()!=pad || released.getBoolean(owner) || displays==null
                        || keyguard==null || keyguard.isKeyguardLocked() || displayId<0) return null;
                String id=(String)bus.get(owner);
                if(id==null || id.isBlank() || id.length()>256 || (Integer)mirage.invoke(actualDirectory,id)!=displayId) return null;
                if(displays.getDisplay(displayId)==null) return null;
                // These are the same per-bus dimensions used by the native PadUibcPolicy when
                // routing into its Mirage display. Capture VirtualDisplay geometry is unrelated.
                return new DisplayState(id,displayId,(Integer)width.invoke(actualDirectory,id),(Integer)height.invoke(actualDirectory,id));
            } catch(ReflectiveOperationException|RuntimeException error) { return null; }
        };
    }
    public void install() throws ReflectiveOperationException {
        if(closed || module==null || !hooks.isEmpty()) throw new IllegalStateException("native_pad_focus_not_installable");
        Class<?> manager=Class.forName("com.oplus.cast.engine.impl.odmf.display.castdisplaymanager.CastDisplayManager",false,loader);
        Class<?> callback=Class.forName(manager.getName()+"$a",false,loader);
        callbackOwner=callback.getDeclaredField("a"); callbackOwner.setAccessible(true);
        if(callbackOwner.getType()!=manager) throw new NoSuchFieldException("native_pad_focus_owner_changed");
        try {
            hooks.add(module.hook(callback.getDeclaredMethod("onMirageDisplayTopActivityUidChanged",int.class,int.class))
                    .setId("qj.screen.oplus.pad.focus").intercept(chain -> {
                        Object result=chain.proceed();
                        observed(callbackOwner.get(chain.getThisObject()),(Integer)chain.getArg(0),(Integer)chain.getArg(1)); return result;
                    }));
            for(String name:new String[]{"onMirageDisplayExit","onMirageDisplayCastFailed"}) {
                hooks.add(module.hook(callback.getDeclaredMethod(name,int.class)).setId("qj.screen.oplus.pad."+name).intercept(chain -> {
                    revoke(callbackOwner.get(chain.getThisObject())); return chain.proceed();
                }));
            }
            hooks.add(module.hook(manager.getMethod("k0")).setId("qj.screen.oplus.pad.focus.release").intercept(chain -> {
                revoke(chain.getThisObject()); return chain.proceed();
            }));
        } catch(ReflectiveOperationException|RuntimeException error) { close(); throw error; }
    }
    void observed(Object owner,int actualUid,int actualDisplayId) {
        revoke(owner);
        if(closed || actualUid<0) return;
        DisplayState actual; Route route;
        try {
            actual=nativeState.read(owner,actualDisplayId); if(actual==null) return;
            route=routes.resolve(owner,actual.busId); if(route==null) return;
        } catch(RuntimeException error) { return; }
        AtomicBoolean live=new AtomicBoolean(true); NativeFocus focus;
        Binding binding;
        synchronized(this) {
            if(closed || bindings.size()>=4 || epoch==Long.MAX_VALUE) return;
            RemoteInputDesktopSession.Focus target;
            try { target=new RemoteInputDesktopSession.Focus(actual.displayId,actualUid,actual.width,actual.height,++epoch); }
            catch(IllegalArgumentException error) { return; }
            BooleanSupplier nativeCurrent=() -> live.get() && !closed && actual.equals(nativeState.read(owner,actualDisplayId));
            focus=new NativeFocus(owner,route.scope,target,() -> nativeCurrent.getAsBoolean() && route.renderedStreamAndInputAllowed.getAsBoolean());
            binding=new Binding(focus,live,nativeCurrent,new AtomicBoolean()); bindings.put(owner,binding);
        }
        refresh(owner,binding);
    }
    /** Supplier for RemoteInputDesktopTarget. A new OEM focus event always has a new epoch. */
    public RemoteInputDesktopSession.Focus currentFocus(Object exactNativeOwner) {
        Binding binding;
        synchronized(this) { binding=bindings.get(exactNativeOwner); }
        if(binding==null) return null;
        refresh(exactNativeOwner,binding);
        return binding.live.get() && binding.announced.get() && binding.focus.current() ? binding.focus.focus : null;
    }
    private void refresh(Object owner,Binding binding) {
        boolean nativeCurrent;
        try { nativeCurrent=binding.nativeCurrent.getAsBoolean(); } catch(RuntimeException error) { nativeCurrent=false; }
        if(!nativeCurrent) { revoke(owner,binding); return; }
        if(!binding.focus.current()) {
            if(binding.announced.get()) revoke(owner,binding);
            return; // Preserve only the real native observation while waiting for first render.
        }
        if(binding.announced.compareAndSet(false,true)) {
            try { listener.focused(binding.focus); } catch(RuntimeException error) { revoke(owner,binding); }
        }
    }
    private void revoke(Object owner) {
        revoke(owner,null);
    }
    private void revoke(Object owner,Binding expected) {
        Binding previous;
        synchronized(this) {
            if(expected!=null && bindings.get(owner)!=expected) return;
            previous=bindings.remove(owner); if(previous!=null) previous.live.set(false);
        }
        if(previous!=null && previous.announced.get()) try { listener.revoked(previous.focus); } catch(RuntimeException ignored) { }
    }
    public void tick() {
        List<Object> owners; synchronized(this) { owners=new ArrayList<>(bindings.keySet()); }
        for(Object owner:owners) currentFocus(owner);
    }
    public void sessionChanged() {
        List<Object> owners; synchronized(this) { owners=new ArrayList<>(bindings.keySet()); }
        for(Object owner:owners) revoke(owner);
    }
    @Override public void close() {
        closed=true; sessionChanged(); for(HookHandle hook:hooks) hook.unhook(); hooks.clear();
    }
}
