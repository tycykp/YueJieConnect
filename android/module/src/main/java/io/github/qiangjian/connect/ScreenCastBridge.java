package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Process-local screen assembly. The shared owner authenticates native routes, permission and
 * strict screen_control messages, and supplies the actual PFD media pipe through Transport.
 * No unregistered operation, native peer, window, comparison confirmation or permit is invented. */
public final class ScreenCastBridge implements AutoCloseable {
    public enum Action { PREPARED, RENDERED, KEYFRAME, STOP }
    public record Control(ScreenCastSession.Scope scope,Action action,ScreenCastFormat format,long sequence,long presentationTimeUs) {
        public Control {
            Objects.requireNonNull(scope); Objects.requireNonNull(action);
            boolean valid=switch(action) {
                case PREPARED -> format!=null && sequence==0 && presentationTimeUs==0;
                case RENDERED -> format==null && sequence>0 && presentationTimeUs>=0;
                case KEYFRAME,STOP -> format==null && sequence==0 && presentationTimeUs==0;
            };
            if(!valid) throw new IllegalArgumentException("invalid_screen_control");
        }
    }
    public interface Pipe extends AutoCloseable {
        boolean offer(byte[] qjv1);
        void setReceiver(Consumer<byte[]> receiver);
        void setOnClosed(Runnable closed);
        @Override void close();
    }
    public interface Transport {
        /** Nonblocking. Complete only after actual authenticated media establishment, not merely
         * receiving a PFD. Source/receiver roles and ScreenVideo native grants are checked outside.
         * Cancellation/late completion must close that exact stream; no PSK enters this interface. */
        CompletionStage<Pipe> open(ScreenCastSession.Scope scope,ScreenCastSession.Role role);
        /** Nonblocking enqueue over the confirmed parent session; false means admission failure. */
        boolean send(Control message);
        void cancel(ScreenCastSession.Scope scope);
    }
    public interface NativeEndpoint {
        ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet);
        void requestKeyFrame();
        void revokeInput();
        /** Enqueue the scoped stop on the original native source/main worker. */
        void stopNative();
        default void sourceManager(Object actualDeviceInfo,Object actualPadManager,String actualBusId) { }
    }
    public interface Listener {
        /** Enqueue any UI/media consumer work onto its owner thread. Never synchronously call
         * back into a route whose gate reads streaming(), since the session monitor is held. */
        void onStreaming(Object exactNativeOwner,ScreenCastSession.Scope scope,ScreenCastSession.Receipt receipt);
        void onClosed(Object exactNativeOwner,ScreenCastSession.Scope scope,String code);
    }
    private static final class Entry {
        final Object owner;
        final ScreenCastSession.Scope scope;
        final ScreenCastFormat format;
        final ScreenCastSession.Role role;
        final BooleanSupplier current;
        final NativeEndpoint nativeEndpoint;
        final long deadline;
        final CompletableFuture<ScreenCastNativeSession> opening=new CompletableFuture<>();
        boolean live=true, sinkPrepared, closingMedia;
        ScreenCastFormat remotePrepared;
        Pipe pipe;
        ScreenCastNativeSession session;
        XiaomiScreenSinkAdapter.Listener sink;
        Entry(Object owner,ScreenCastSession.Scope scope,ScreenCastFormat format,ScreenCastSession.Role role,
                BooleanSupplier current,NativeEndpoint nativeEndpoint,long deadline) {
            this.owner=owner;this.scope=scope;this.format=format;this.role=role;this.current=current;this.nativeEndpoint=nativeEndpoint;this.deadline=deadline;
        }
    }
    private final Transport transport;
    private final Listener listener;
    private final LongSupplier elapsed;
    private final Map<ScreenCastSession.Scope,Entry> entries=new HashMap<>();
    private boolean closed;
    public ScreenCastBridge(Transport transport,Listener listener,LongSupplier elapsedRealtimeMs) {
        this.transport=Objects.requireNonNull(transport);this.listener=Objects.requireNonNull(listener);elapsed=Objects.requireNonNull(elapsedRealtimeMs);
    }
    public OplusScreenSessionLease.Factory sourceFactory(Object actualDeviceInfo,ScreenCastSession.Scope scope,
            ScreenCastFormat negotiatedFormat,NativeEndpoint nativeEndpoint,Consumer<String> fixedFailure) {
        Objects.requireNonNull(actualDeviceInfo);Objects.requireNonNull(scope);Objects.requireNonNull(negotiatedFormat);
        Objects.requireNonNull(nativeEndpoint);Objects.requireNonNull(fixedFailure);
        return new OplusScreenSessionLease.Factory() {
            public CompletionStage<ScreenCastNativeSession> openAfterNativeConsent(Object actual,BooleanSupplier permit) {
                if(actual!=actualDeviceInfo) return CompletableFuture.failedStage(new IllegalStateException("screen_native_owner_mismatch"));
                return open(actual,scope,negotiatedFormat,ScreenCastSession.Role.SOURCE,permit,nativeEndpoint).opening.minimalCompletionStage();
            }
            public void nativeSource(Object actual,Object manager,String busId) {
                if(actual!=actualDeviceInfo) throw new IllegalStateException("screen_native_owner_mismatch");
                nativeEndpoint.sourceManager(actual,manager,busId);
            }
            public void cancel() {
                Entry entry;synchronized(ScreenCastBridge.this){entry=entries.get(scope);}
                if(entry!=null)finish(entry,"screen_native_request_closed");
                else { attempt(nativeEndpoint::revokeInput);attempt(nativeEndpoint::stopNative);attempt(() -> transport.cancel(scope)); }
            }
            public void failed(String code) { fixedFailure.accept(code); }
        };
    }
    /** Called by the verified native window resolver, after source OEM consent was authenticated.
     * The native window and peer remain those actually supplied by XiaomiScreenRequestAdapter. */
    public XiaomiScreenRequestAdapter.Expectation sink(XiaomiScreenRequestAdapter.NativeWindow actualWindow,
            ScreenCastSession.Scope scope,ScreenCastFormat negotiatedFormat,BooleanSupplier sourceConsentAndCurrent,
            NativeEndpoint nativeEndpoint) {
        Objects.requireNonNull(actualWindow);Objects.requireNonNull(sourceConsentAndCurrent);
        AtomicReference<Entry> selected=new AtomicReference<>();
        BooleanSupplier gate=() -> actualWindow.current() && sourceConsentAndCurrent.getAsBoolean()
                && (selected.get()==null || live(selected.get()));
        Entry entry=open(actualWindow.window(),scope,negotiatedFormat,ScreenCastSession.Role.SINK,gate,nativeEndpoint);
        selected.set(entry);
        return new XiaomiScreenRequestAdapter.Expectation(new XiaomiScreenSinkAdapter.Scope(scope.sessionId(),scope.streamId()),
                negotiatedFormat,entry.deadline,gate,deferredSink(entry));
    }
    private Entry open(Object owner,ScreenCastSession.Scope scope,ScreenCastFormat format,ScreenCastSession.Role role,
            BooleanSupplier current,NativeEndpoint nativeEndpoint) {
        Objects.requireNonNull(owner);Objects.requireNonNull(scope);Objects.requireNonNull(format);Objects.requireNonNull(current);Objects.requireNonNull(nativeEndpoint);
        Entry entry=new Entry(owner,scope,format,role,current,nativeEndpoint,elapsed.getAsLong()+10_000);
        synchronized(this) {
            if(closed || entries.containsKey(scope) || entries.size()>=64
                    || entries.values().stream().filter(ScreenCastBridge::live).count()>=4) throw new IllegalStateException("screen_route_capacity_or_reuse");
            entries.put(scope,entry);
        }
        if(!allowed(entry)) { finish(entry,"screen_native_request_revoked"); return entry; }
        try {
            CompletionStage<Pipe> opening=transport.open(scope,role);
            if(opening==null) finish(entry,"screen_media_open_failed");
            else opening.whenComplete((pipe,error) -> opened(entry,pipe,error));
        } catch(RuntimeException error) { finish(entry,"screen_media_open_failed"); }
        return entry;
    }
    private static boolean live(Entry entry) { synchronized(entry) { return entry.live; } }
    private boolean allowed(Entry entry) {
        if(!live(entry)) return false;
        try { return entry.current.getAsBoolean(); } catch(RuntimeException error) { return false; }
    }
    private void opened(Entry entry,Pipe pipe,Throwable error) {
        if(error!=null || pipe==null || !allowed(entry) || elapsed.getAsLong()>=entry.deadline) {
            if(pipe!=null) pipe.close(); finish(entry,"screen_media_open_failed"); return;
        }
        ScreenCastNativeSession session=new ScreenCastNativeSession(entry.owner,entry.scope,entry.format,entry.role,
                () -> allowed(entry),elapsed,new ScreenCastNativeSession.Endpoint() {
                    public boolean offer(ScreenCastPacket packet) { return pipe.offer(packet.encode()); }
                    public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet) { return entry.nativeEndpoint.receive(packet); }
                    public void requestKeyFrame() { entry.nativeEndpoint.requestKeyFrame(); }
                    public void revokeInput() { entry.nativeEndpoint.revokeInput(); }
                    public void stopNative() { entry.nativeEndpoint.stopNative(); }
                    public void closeMedia() { closePipe(entry,pipe); }
                },new ScreenCastNativeSession.Control() {
                    private void send(Action action,ScreenCastFormat format,long seq,long pts) {
                        if(!transport.send(new Control(entry.scope,action,format,seq,pts))) throw new IllegalStateException("screen_control_failed");
                    }
                    public void prepared(ScreenCastSession.Scope scope,ScreenCastFormat format) { send(Action.PREPARED,format,0,0); }
                    public void rendered(ScreenCastSession.Receipt receipt) { send(Action.RENDERED,null,receipt.sequence(),receipt.presentationTimeUs()); }
                    public void requestKeyFrame(ScreenCastSession.Scope scope) { send(Action.KEYFRAME,null,0,0); }
                    public void stopped(ScreenCastSession.Scope scope,String code) { send(Action.STOP,null,0,0); }
                },new ScreenCastNativeSession.Listener() {
                    public void onStreaming(ScreenCastSession.Receipt receipt) { listener.onStreaming(entry.owner,entry.scope,receipt); }
                    public void onClosed(String code) { finish(entry,code); }
                });
        boolean prepared;ScreenCastFormat remote;
        synchronized(entry) {
            if(!entry.live) { pipe.close(); return; }
            entry.pipe=pipe;entry.session=session;
            if(entry.role==ScreenCastSession.Role.SINK) entry.sink=session.sinkListener(entry.owner);
            prepared=entry.sinkPrepared;remote=entry.remotePrepared;
        }
        try {
            pipe.setOnClosed(() -> {
                boolean expected;synchronized(entry){expected=entry.closingMedia;}
                if(!expected)finish(entry,"screen_media_closed");
            });
            if(entry.role==ScreenCastSession.Role.SINK) pipe.setReceiver(bytes -> {
                if(!allowed(entry)) { finish(entry,"screen_native_request_revoked"); return; }
                try { session.receive(entry.scope,ScreenCastPacket.decode(bytes)); }
                catch(RuntimeException failure) { finish(entry,"screen_media_protocol_failed"); }
            });
            if(remote!=null) session.remotePrepared(entry.scope,remote);
            entry.opening.complete(session);
            if(prepared && entry.sink!=null) entry.sink.onPrepared();
        } catch(RuntimeException failure) { finish(entry,"screen_media_open_failed"); }
    }
    private XiaomiScreenSinkAdapter.Listener deferredSink(Entry entry) {
        return new XiaomiScreenSinkAdapter.Listener() {
            public void onPrepared() {
                XiaomiScreenSinkAdapter.Listener sink;
                boolean duplicate;
                synchronized(entry) { if(!entry.live)return;duplicate=entry.sinkPrepared;entry.sinkPrepared=true;sink=entry.sink; }
                if(duplicate) finish(entry,"screen_native_surface_changed");
                else if(sink!=null)sink.onPrepared();
            }
            public void onFrameRendered(long pts,long nano) { XiaomiScreenSinkAdapter.Listener sink; synchronized(entry){sink=entry.sink;} if(sink!=null)sink.onFrameRendered(pts,nano); }
            public void onKeyFrameNeeded() { XiaomiScreenSinkAdapter.Listener sink; synchronized(entry){sink=entry.sink;} if(sink!=null)sink.onKeyFrameNeeded(); }
            public void onStopped() { finish(entry,"screen_native_stopped"); }
            public void onEnd() { finish(entry,"screen_ended"); }
            public void onFailure(String ignored) { finish(entry,"screen_native_failed"); }
        };
    }
    /** Inbound from the authenticated, scope-checked parent control owner only. */
    public boolean control(Control control) {
        Entry entry; synchronized(this) { entry=entries.get(control.scope); }
        if(entry==null || !allowed(entry)) return false;
        ScreenCastNativeSession session;
        synchronized(entry) {
            if(control.action==Action.PREPARED) entry.remotePrepared=control.format;
            session=entry.session;
        }
        if(control.action==Action.STOP) {
            if(session!=null)session.remoteStopped(control.scope);
            else finish(entry,"screen_remote_stopped");
            return true;
        }
        if(control.action==Action.PREPARED && !entry.format.equals(control.format)) { finish(entry,"screen_format_mismatch"); return false; }
        if(session==null) return control.action==Action.PREPARED;
        return switch(control.action) {
            case PREPARED -> session.remotePrepared(control.scope,control.format);
            case RENDERED -> session.remoteRendered(new ScreenCastSession.Receipt(control.scope,control.sequence,control.presentationTimeUs));
            case KEYFRAME -> session.remoteKeyFrame(control.scope);
            case STOP -> false;
        };
    }
    public boolean streaming(Object exactOwner,ScreenCastSession.Scope scope) {
        Entry entry; synchronized(this){entry=entries.get(scope);}
        ScreenCastNativeSession session; if(entry==null)return false; synchronized(entry){session=entry.session;}
        return session!=null && session.streaming(exactOwner,scope);
    }
    public void stop(ScreenCastSession.Scope scope,String fixedLocalCode) {
        Entry entry;synchronized(this){entry=entries.get(scope);}if(entry!=null)finish(entry,fixedLocalCode);
    }
    private void finish(Entry entry,String code) {
        ScreenCastNativeSession session;Pipe pipe;
        synchronized(entry) { if(!entry.live)return;entry.live=false;session=entry.session;pipe=entry.pipe; }
        if(session!=null)session.close();
        else { attempt(entry.nativeEndpoint::revokeInput);attempt(entry.nativeEndpoint::stopNative); }
        if(pipe!=null)attempt(() -> closePipe(entry,pipe));
        entry.opening.completeExceptionally(new IllegalStateException("screen_route_closed"));
        attempt(() -> transport.cancel(entry.scope));
        attempt(() -> listener.onClosed(entry.owner,entry.scope,code));
    }
    public void tick() {
        ArrayList<Entry> selected;synchronized(this){selected=new ArrayList<>(entries.values());}
        for(Entry entry:selected) {
            if(!live(entry))continue;
            if(!allowed(entry)) { finish(entry,"screen_native_request_revoked");continue; }
            ScreenCastNativeSession session;synchronized(entry){session=entry.session;}
            if(session!=null)session.tick();
            else if(elapsed.getAsLong()>=entry.deadline)finish(entry,"screen_media_open_timeout");
        }
    }
    public void sessionChanged() {
        ArrayList<Entry> selected;synchronized(this){selected=new ArrayList<>(entries.values());entries.clear();}
        for(Entry entry:selected)finish(entry,"screen_session_changed");
    }
    private static void attempt(Runnable action){try{action.run();}catch(RuntimeException ignored){}}
    private static void closePipe(Entry entry,Pipe pipe){synchronized(entry){entry.closingMedia=true;}pipe.close();}
    @Override public void close(){synchronized(this){closed=true;}sessionChanged();}
}
