package io.github.qiangjian.connect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled native/PFD boundaries exercise the actual Java bridge. Actual Noise and PFD
 * framing are verified separately by media_runtime; no device rendering is asserted here. */
public class ScreenCastBridgeTest {
    private final ScreenCastSession.Scope scope=new ScreenCastSession.Scope("a".repeat(64),7);
    private final ScreenCastFormat format=new ScreenCastFormat(640,480,30,1_000_000);
    private final Object device=new Object(),window=new Object(),peer=new Object();
    private final AtomicLong clock=new AtomicLong(100);
    private final ArrayDeque<Runnable> network=new ArrayDeque<>();
    private final class Pipe implements ScreenCastBridge.Pipe {
        Consumer<byte[]> receiver;Runnable onClosed;Pipe other;boolean closed;int closes;
        public boolean offer(byte[] bytes){if(closed)return false;byte[] copy=bytes.clone();network.add(() -> {if(other!=null&&!other.closed&&other.receiver!=null)other.receiver.accept(copy);});return true;}
        public void setReceiver(Consumer<byte[]> receiver){this.receiver=receiver;}
        public void setOnClosed(Runnable closed){onClosed=closed;}
        public void close(){if(closed)return;closed=true;closes++;if(onClosed!=null)onClosed.run();}
    }
    private final class End implements ScreenCastBridge.Transport,ScreenCastBridge.NativeEndpoint,ScreenCastBridge.Listener {
        final CompletableFuture<ScreenCastBridge.Pipe> media=new CompletableFuture<>();
        final Pipe pipe=new Pipe();final List<ScreenCastBridge.Control> controls=new ArrayList<>();
        final List<String> closed=new ArrayList<>();final List<ScreenCastPacket> decoded=new ArrayList<>();
        int opens,cancels,revokes,stops,streaming;Object streamedOwner;End other;
        final ScreenCastBridge bridge=new ScreenCastBridge(this,this,clock::get);
        public CompletionStage<ScreenCastBridge.Pipe> open(ScreenCastSession.Scope scope,ScreenCastSession.Role role){opens++;return media;}
        public boolean send(ScreenCastBridge.Control control){controls.add(control);network.add(() -> {if(other!=null)other.bridge.control(control);});return true;}
        public void cancel(ScreenCastSession.Scope scope){cancels++;}
        public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet){decoded.add(packet);return ScreenCastFrameQueue.Offer.ACCEPTED;}
        public void requestKeyFrame(){}
        public void revokeInput(){revokes++;}
        public void stopNative(){stops++;}
        public void onStreaming(Object owner,ScreenCastSession.Scope scope,ScreenCastSession.Receipt receipt){streaming++;streamedOwner=owner;}
        public void onClosed(Object owner,ScreenCastSession.Scope scope,String code){closed.add(code);}
    }
    private void flush(){int count=0;while(!network.isEmpty()){assertTrue(++count<100);network.remove().run();}}
    private OplusScreenSessionLease source(End end){return new OplusScreenSessionLease(device,scope,120_100,() -> true,clock::get,
            end.bridge.sourceFactory(device,scope,format,end,ignored -> {}));}
    private XiaomiScreenSinkAdapter.Listener sink(End end){return (XiaomiScreenSinkAdapter.Listener)expectation(end).listener();}
    private XiaomiScreenRequestAdapter.Expectation expectation(End end){return end.bridge.sink(
            new XiaomiScreenRequestAdapter.NativeWindow(window,peer,"synthetic-device",4,true,() -> true),scope,format,() -> true,end);}
    private ScreenCastPacket frame(){return new ScreenCastPacket(7,1,100,ScreenCastPacket.KEY,new byte[]{1,2,3});}
    @Test public void nativeConsentPipesBothPreparedAndActualRenderCompleteOnePipeline(){
        End src=new End(),dst=new End();src.other=dst;dst.other=src;src.pipe.other=dst.pipe;dst.pipe.other=src.pipe;
        var lease=source(src);var sink=sink(dst);lease.onNativeAllowed(device);lease.onPrepared(42);sink.onPrepared();
        assertFalse(lease.offer(frame()));assertFalse(src.bridge.streaming(device,scope));assertEquals(0,src.streaming);
        src.media.complete(src.pipe);dst.media.complete(dst.pipe);flush();
        assertTrue(lease.offer(frame()));flush();assertEquals(1,dst.decoded.size());assertEquals(0,dst.streaming);
        sink.onFrameRendered(100,1);assertEquals(1,dst.streaming);assertEquals(0,src.streaming);flush();
        assertEquals(1,src.streaming);assertSame(device,src.streamedOwner);assertSame(window,dst.streamedOwner);
        assertTrue(src.bridge.streaming(device,scope));assertTrue(dst.bridge.streaming(window,scope));
        assertFalse(dst.bridge.streaming(peer,scope));
        lease.close();flush();assertEquals(1,src.stops);assertEquals(1,dst.stops);assertEquals(1,src.revokes);assertEquals(1,dst.revokes);
        assertEquals(0,dst.controls.stream().filter(c -> c.action()==ScreenCastBridge.Action.STOP).count());
        assertFalse(src.bridge.streaming(device,scope));assertEquals(1,src.closed.size());assertEquals(1,dst.closed.size());
    }
    @Test public void sessionChangeRetiresOldWindowAndAllowsFreshSession(){
        End end=new End();var old=expectation(end);
        end.bridge.sessionChanged();
        assertFalse(old.currentAndConsented().getAsBoolean());
        assertEquals(1,end.stops);
        var nextScope=new ScreenCastSession.Scope("b".repeat(64),8);
        var next=end.bridge.sink(new XiaomiScreenRequestAdapter.NativeWindow(
                new Object(),peer,"synthetic-device",5,true,()->true),
                nextScope,format,()->true,end);
        assertTrue(next.currentAndConsented().getAsBoolean());
        assertEquals(2,end.opens);
        old.listener().onFailure("late_old_callback");
        assertTrue(next.currentAndConsented().getAsBoolean());
        assertFalse(end.bridge.control(new ScreenCastBridge.Control(scope,ScreenCastBridge.Action.STOP,null,0,0)));
        end.bridge.close();
        assertFalse(next.currentAndConsented().getAsBoolean());
        assertEquals(2,end.stops);
    }
    @Test public void closingScreenAllowsAnotherStreamInTheSamePairedSession(){
        End end=new End();var old=expectation(end);
        end.bridge.stop(scope,"screen_user_stopped");
        assertFalse(old.currentAndConsented().getAsBoolean());assertEquals(1,end.stops);
        var nextScope=new ScreenCastSession.Scope(scope.sessionId(),scope.streamId()+1);
        var next=end.bridge.sink(new XiaomiScreenRequestAdapter.NativeWindow(
                new Object(),peer,"synthetic-device",5,true,()->true),nextScope,format,()->true,end);
        old.listener().onEnd();end.bridge.stop(scope,"late_old_close");
        assertTrue(next.currentAndConsented().getAsBoolean());assertEquals(2,end.opens);
        assertEquals(1,end.stops);
        end.bridge.stop(nextScope,"screen_user_stopped");assertEquals(2,end.stops);
    }
    @Test public void cancellationBeforeNativeAllowStillCancelsExactPendingNativeRequest(){
        End end=new End();var lease=source(end);lease.close();lease.onNativeAllowed(device);
        assertEquals(0,end.opens);assertEquals(1,end.stops);assertEquals(1,end.revokes);assertEquals(1,end.cancels);
    }
    @Test public void timeoutClosesLatePipeAndCannotReopenNativeWindow(){
        End end=new End();var expected=expectation(end);var sink=(XiaomiScreenSinkAdapter.Listener)expected.listener();sink.onPrepared();
        clock.set(10_100);end.bridge.tick();assertEquals(1,end.stops);assertFalse(expected.currentAndConsented().getAsBoolean());
        end.media.complete(end.pipe);assertTrue(end.pipe.closed);assertEquals(1,end.stops);assertEquals(0,end.streaming);
        sink.onFrameRendered(100,1);assertEquals(0,end.streaming);
    }
    @Test public void mismatchedNegotiatedFormatClosesEvenBeforeMediaReady(){
        End end=new End();var lease=source(end);lease.onNativeAllowed(device);
        assertFalse(end.bridge.control(new ScreenCastBridge.Control(scope,ScreenCastBridge.Action.PREPARED,
                new ScreenCastFormat(1280,720,30,1_000_000),0,0)));
        end.media.complete(end.pipe);assertTrue(end.pipe.closed);assertEquals(1,end.stops);assertEquals(0,end.streaming);
        assertTrue(end.closed.contains("screen_format_mismatch"));
    }
    @Test public void replacingNativeSurfaceWhilePipeOpeningFencesBothGenerations(){
        End end=new End();var expected=expectation(end);var sink=(XiaomiScreenSinkAdapter.Listener)expected.listener();
        sink.onPrepared();sink.onPrepared();assertFalse(expected.currentAndConsented().getAsBoolean());
        end.media.complete(end.pipe);assertTrue(end.pipe.closed);assertEquals(List.of("screen_native_surface_changed"),end.closed);
        assertEquals(1,end.stops);
    }
    @Test public void unknownScopeAndUnmatchedRenderCannotProduceStreaming(){
        End end=new End();var lease=source(end);lease.onNativeAllowed(device);end.media.complete(end.pipe);lease.onPrepared(42);
        assertFalse(end.bridge.control(new ScreenCastBridge.Control(new ScreenCastSession.Scope(scope.sessionId(),8),ScreenCastBridge.Action.PREPARED,format,0,0)));
        assertTrue(end.bridge.control(new ScreenCastBridge.Control(scope,ScreenCastBridge.Action.PREPARED,format,0,0)));
        assertFalse(end.bridge.control(new ScreenCastBridge.Control(scope,ScreenCastBridge.Action.RENDERED,null,1,100)));
        assertEquals(0,end.streaming);assertTrue(lease.offer(frame()));
        assertFalse(end.bridge.control(new ScreenCastBridge.Control(scope,ScreenCastBridge.Action.RENDERED,null,1,101)));
        assertEquals(0,end.streaming);end.bridge.close();
    }
    @Test public void actualPipeEofReleasesNativeOwnerOnceAndKeepsOriginalReason(){
        End end=new End();var lease=source(end);lease.onNativeAllowed(device);end.media.complete(end.pipe);
        end.pipe.close();lease.onStopped();end.bridge.close();
        assertEquals(1,end.stops);assertEquals(1,end.revokes);assertEquals(1,end.cancels);assertEquals(List.of("screen_media_closed"),end.closed);
    }
}
