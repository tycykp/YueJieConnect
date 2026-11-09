package io.github.yuejie.connect;

import android.content.ClipData;
import android.view.DragEvent;
import android.view.View;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Source binding for the OEM floating window. R0 converts ClipData asynchronously, so
 * a ThreadLocal around onDrag cannot identify the later SinkView.m1 START message. */
public final class XiaomiScreenFileSendHooks implements AutoCloseable {
    public interface Routes {
        default boolean consumeMessages(Object view) { return false; }
        default void observed(Object view,int action,boolean clip,boolean selected) {}
        boolean current(Object view);
        void started(Object view, long session, int screen, ClipData original, Object translated);
        void dropped(Object view, long session);
        void cancelled(Object view, long session);
    }
    private static final class Source {
        final Object view;
        final ClipData clip;
        final Object eventTarget;
        long session;
        boolean started, dropped, dropReported, ended;
        Source(Object view, ClipData clip,Object eventTarget) { this.view = view; this.clip = clip; this.eventTarget=eventTarget; }
    }
    private final Routes routes;
    private final Field ownerSession;
    private final List<HookHandle> hooks = new ArrayList<>();
    private final IdentityHashMap<Object, Source> sources = new IdentityHashMap<>();
    private volatile boolean closed;

    public XiaomiScreenFileSendHooks(XposedModule module, ClassLoader loader, Routes routes)
            throws ReflectiveOperationException {
        this.routes = routes;
        Class<?> handler = Class.forName("k4.R0", false, loader);
        Class<?> sink = Class.forName("com.xiaomi.mirror.sink.SinkView", false, loader);
        Class<?> drag = Class.forName("com.xiaomi.mirror.message.DragMessage", false, loader);
        Class<?> messageType = Class.forName("com.xiaomi.mirror.message.Message", false, loader);
        Field view = field(handler, "a"), session = field(handler, "b");
        ownerSession = session;
        Field action = drag.getField("action"), translated = drag.getField("clipData"), screen = drag.getField("screenId");
        try {
            hooks.add(module.hook(handler.getMethod("onDrag", View.class, DragEvent.class))
                    .setId("yj.screen.files.source").intercept(chain -> {
                Object owner = chain.getThisObject(), actual = view.get(owner);
                DragEvent event = (DragEvent) chain.getArg(1);
                if(event.getAction()!=2)routes.observed(actual,event.getAction(),event.getClipData()!=null,!closed&&actual!=null&&routes.current(actual));
                if (closed || actual == null || !routes.current(actual)) return chain.proceed();
                // A remote drag started by this same R0 is a receive, never a new source.
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED && event.getLocalState() != owner) {
                    ClipData clip = event.getClipData();
                    if (clip != null) synchronized (sources) {
                        Source existing=sources.get(owner);
                        // A global START can reach both the SinkView and its touch child.
                        // R0 rejects the second call as busy; do not replace the first owner.
                        if(existing!=null&&!existing.ended&&existing.view==actual&&existing.session>0
                                &&existing.session==session.getLong(owner)&&sameClip(existing.clip,clip))return true;
                        Source old = sources.remove(owner);
                        if (old != null) routes.cancelled(old.view, old.session);
                        sources.put(owner, new Source(actual, clip,chain.getArg(0)));
                    }
                }
                Object result;
                try { result = chain.proceed(); }
                catch (Throwable failure) { remove(owner); throw failure; }
                synchronized (sources) {
                    Source source = sources.get(owner);
                    if (source != null) {
                        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                            if (!Boolean.TRUE.equals(result)) remove(owner);
                            else source.session = session.getLong(owner);
                        } else if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                            source.ended=true;if(!source.dropped)remove(owner);
                        }
                    }
                }
                return result;
            }));
            hooks.add(module.hook(sink.getMethod("m1", messageType, long.class))
                    .setId("yj.screen.files.message").intercept(chain -> {
                Object actual = chain.getThisObject(), message = chain.getArg(0);
                if (!drag.isInstance(message)) return chain.proceed();
                long nativeSession = ((Number) chain.getArg(1)).longValue();
                if (!closed && routes.current(actual)) synchronized (sources) {
                    for (var entry : new ArrayList<>(sources.entrySet())) {
                        Source source = entry.getValue();
                        if (source.view != actual || nativeSession <= 0
                                || session.getLong(entry.getKey()) != nativeSession
                                || source.session != 0 && source.session != nativeSession) continue;
                        source.session = nativeSession;
                        int kind = action.getInt(message);
                        if (kind == 0 && !source.started) {
                            source.started = true;
                            routes.started(actual, nativeSession, screen.getInt(message), source.clip, translated.get(message));
                        } else if (kind == 4) {
                            source.dropped = true;
                        } else if (kind == 5 || kind == 9) {
                            remove(entry.getKey());
                            continue;
                        }
                        if (source.started && source.dropped && !source.dropReported) {
                            source.dropReported = true;
                            routes.dropped(actual, nativeSession);
                        }
                        // FINISH (6) follows DROP immediately; bytes may still be transferring.
                    }
                }
                return !closed && routes.current(actual) && routes.consumeMessages(actual) ? null : chain.proceed();
            }));
            hooks.add(module.hook(handler.getMethod("a", drag, long.class))
                    .setId("yj.screen.files.release").intercept(chain -> {
                int kind = action.getInt(chain.getArg(0));
                synchronized (sources) {
                    Source source = sources.get(chain.getThisObject());
                    if (source != null && source.session == ((Number) chain.getArg(1)).longValue()
                            && (kind == 5 || kind == 9)) remove(chain.getThisObject());
                }
                return chain.proceed();
            }));
            hooks.add(module.hook(handler.getMethod("e")).setId("yj.screen.files.close").intercept(chain -> {
                remove(chain.getThisObject()); return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    private void remove(Object owner) {
        synchronized (sources) {
            Source old = sources.remove(owner);
            if (old != null) routes.cancelled(old.view, old.session);
        }
    }
    public void sessionChanged() {
        synchronized (sources) {
            for (var entry : new ArrayList<>(sources.entrySet())) {
                Source source = entry.getValue();
                boolean valid;
                try {
                    valid = routes.current(source.view) && source.session > 0
                            && ownerSession.getLong(entry.getKey()) == source.session;
                } catch (IllegalAccessException | RuntimeException unavailable) { valid = false; }
                if (!valid) remove(entry.getKey());
            }
        }
    }
    @Override public void close() {
        closed = true;
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
        synchronized (sources) { for (Object owner : new ArrayList<>(sources.keySet())) remove(owner); }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static boolean sameClip(ClipData left,ClipData right){
        if(left==right)return true;
        if(left==null||right==null||left.getItemCount()!=right.getItemCount())return false;
        for(int i=0;i<left.getItemCount();i++)if(left.getItemAt(i).getUri()==null
                ||!left.getItemAt(i).getUri().equals(right.getItemAt(i).getUri()))return false;
        return true;
    }
}
