package io.github.yuejie.connect;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual pump implementation over real local duplex sockets/controlled worker boundaries.
 * Android fd duplication/shutdown is compiled separately, not claimed as device validation. */
public class ScreenCastMediaPipeTest {
    private final ScreenCastSession.Scope scope=new ScreenCastSession.Scope("a".repeat(64),7);
    private byte[] packet(long seq,int size){return new ScreenCastPacket(7,seq,seq*100,ScreenCastPacket.KEY,new byte[size]).encode();}
    private static final class Pair implements AutoCloseable {
        final Socket javaSide,nativeSide;
        Pair() throws Exception {
            try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
                javaSide=new Socket(server.getInetAddress(),server.getLocalPort());nativeSide=server.accept();
                nativeSide.setSoTimeout(2000);
            }
        }
        public void close(){try{javaSide.close();}catch(IOException ignored){}try{nativeSide.close();}catch(IOException ignored){}}
    }
    private ScreenCastMediaPipe pipe(Pair pair,ScreenCastSession.Role role,AtomicBoolean allowed) throws Exception {
        return new ScreenCastMediaPipe(scope,role,allowed::get,pair.javaSide.getInputStream(),role==ScreenCastSession.Role.SOURCE?pair.javaSide.getOutputStream():null,
                () -> {try{pair.javaSide.close();}catch(IOException ignored){}});
    }
    private static void write(OutputStream out,byte[] wire) throws Exception {
        int n=wire.length;out.write(new byte[]{(byte)n,(byte)(n>>>8),(byte)(n>>>16),(byte)(n>>>24)});out.write(wire);
    }
    @Test public void sourceWritesExactLittleEndianFrameAndCannotBeUsedAsReceiver() throws Exception {
        try(Pair pair=new Pair();var pipe=pipe(pair,ScreenCastSession.Role.SOURCE,new AtomicBoolean(true))){
            byte[] expected=packet(1,1234);assertTrue(pipe.offer(expected));DataInputStream in=new DataInputStream(pair.nativeSide.getInputStream());
            int n=Integer.reverseBytes(in.readInt());assertEquals(expected.length,n);byte[] actual=new byte[n];in.readFully(actual);assertArrayEquals(expected,actual);
            assertThrows(IllegalStateException.class,() -> pipe.setReceiver(ignored -> {}));
        }
    }
    @Test public void sinkAcceptsOneMibOnlyAfterFullFrameAndRetainsDataUntilReceiverInstalled() throws Exception {
        try(Pair pair=new Pair();var pipe=pipe(pair,ScreenCastSession.Role.SINK,new AtomicBoolean(true))){
            byte[] expected=packet(1,ScreenCastPacket.MAX_PAYLOAD);write(pair.nativeSide.getOutputStream(),expected);
            CountDownLatch delivered=new CountDownLatch(1);AtomicInteger length=new AtomicInteger();
            pipe.setReceiver(actual -> {assertArrayEquals(expected,actual);length.set(actual.length);delivered.countDown();});
            assertTrue(delivered.await(2,TimeUnit.SECONDS));assertEquals(expected.length,length.get());assertFalse(pipe.offer(packet(2,1)));
        }
    }
    @Test public void capacityIncludesBlockedWriterAndRevokeClearsAllCounters() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true),stopped=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        try(Pair pair=new Pair()){
            OutputStream blocked=new OutputStream(){public void write(int value)throws IOException{write(new byte[]{(byte)value});}public void write(byte[] data)throws IOException{
                entered.countDown();try{release.await();}catch(InterruptedException error){throw new IOException("synthetic");}if(stopped.get())throw new IOException("synthetic");}};
            try(var pipe=new ScreenCastMediaPipe(scope,ScreenCastSession.Role.SOURCE,allowed::get,pair.javaSide.getInputStream(),blocked,() -> {stopped.set(true);release.countDown();try{pair.javaSide.close();}catch(IOException ignored){}})){
                pipe.setOnClosed(closed::countDown);assertTrue(pipe.offer(packet(1,3)));assertTrue(entered.await(1,TimeUnit.SECONDS));
                for(int i=2;i<=8;i++)assertTrue(pipe.offer(packet(i,3)));assertEquals(8,pipe.queuedFrames());assertFalse(pipe.offer(packet(9,3)));
                allowed.set(false);assertTrue(closed.await(1,TimeUnit.SECONDS));assertEquals(0,pipe.queuedFrames());assertEquals(0,pipe.queuedBytes());assertTrue(stopped.get());
            }
        }
    }
    @Test public void malformedLengthAndWrongStreamCloseBeforeAnyNativeConsumer() throws Exception {
        try(Pair pair=new Pair();var pipe=pipe(pair,ScreenCastSession.Role.SINK,new AtomicBoolean(true))){
            CountDownLatch closed=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();pipe.setOnClosed(closed::countDown);pipe.setReceiver(ignored -> calls.incrementAndGet());
            pair.nativeSide.getOutputStream().write(new byte[]{-1,-1,-1,-1});assertTrue(closed.await(1,TimeUnit.SECONDS));assertEquals(0,calls.get());assertEquals("media_pipe_protocol_failed",pipe.closedCode());
        }
        try(Pair pair=new Pair();var pipe=pipe(pair,ScreenCastSession.Role.SOURCE,new AtomicBoolean(true))){
            assertFalse(pipe.offer(new ScreenCastPacket(8,1,100,ScreenCastPacket.KEY,new byte[]{1}).encode()));assertTrue(pipe.isClosed());
        }
    }
    @Test public void peerEofBeforeCloseListenerIsRegisteredStillNotifiesExactlyOnce() throws Exception {
        try(Pair pair=new Pair();var pipe=pipe(pair,ScreenCastSession.Role.SOURCE,new AtomicBoolean(true))){
            pair.nativeSide.close();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(!pipe.isClosed()&&System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue(pipe.isClosed());AtomicInteger calls=new AtomicInteger();pipe.setOnClosed(calls::incrementAndGet);pipe.close();assertEquals(1,calls.get());
        }
    }
}
