package io.github.yuejie.connect;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actual local PFD video pump, shared by ScreenVideo/CameraVideo after their separate native
 * permissions and authenticated runtime have been verified by the control owner. Owns the fd.
 * Local framing is 4-byte little-endian length + canonical YJV1; keys never enter this pipe. */
public final class ScreenCastMediaPipe implements ScreenCastBridge.Pipe {
    public static final int MAX_FRAMES=8,MAX_BYTES=4*1024*1024;
    private static final long STALL_NS=5_000_000_000L;
    private record Streams(InputStream input,OutputStream output,Runnable shutdown){}
    private final Object queueLock=new Object();
    private final ScreenCastSession.Scope scope;
    private final ScreenCastSession.Role role;
    private final BooleanSupplier allowed;
    private final InputStream input;
    private final OutputStream output;
    private final Runnable shutdown;
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    private final AtomicBoolean closed=new AtomicBoolean(),notified=new AtomicBoolean();
    private final Thread reader,worker,watcher;
    private volatile Consumer<byte[]> receiver;
    private volatile Runnable onClosed;
    private volatile String closedCode="";
    private volatile long readDeadline,workDeadline;
    private int frames,bytes;
    private long lastSequence;

    public ScreenCastMediaPipe(ParcelFileDescriptor actualOwnedSocket,ScreenCastSession.Scope scope,
            ScreenCastSession.Role role,BooleanSupplier currentSessionAndNativeAllowed) throws IOException {
        this(scope,role,currentSessionAndNativeAllowed,streams(actualOwnedSocket,scope,role,currentSessionAndNativeAllowed));
    }
    private ScreenCastMediaPipe(ScreenCastSession.Scope scope,ScreenCastSession.Role role,BooleanSupplier allowed,Streams streams) {
        this(scope,role,allowed,streams.input,streams.output,streams.shutdown);
    }
    /** Native boundary injection used only by host framing/lifecycle checks. */
    ScreenCastMediaPipe(ScreenCastSession.Scope scope,ScreenCastSession.Role role,BooleanSupplier allowed,
            InputStream input,OutputStream output,Runnable shutdown) {
        this.scope=Objects.requireNonNull(scope);this.role=Objects.requireNonNull(role);this.allowed=Objects.requireNonNull(allowed);
        this.input=Objects.requireNonNull(input);this.output=output;this.shutdown=Objects.requireNonNull(shutdown);
        if(role==ScreenCastSession.Role.SOURCE&&output==null)throw new IllegalArgumentException("media_pipe_output_missing");
        reader=new Thread(this::readLoop,"yj-media-pfd-read");worker=new Thread(this::workLoop,"yj-media-pfd-dispatch");watcher=new Thread(this::watchLoop,"yj-media-pfd-life");
        reader.setDaemon(true);worker.setDaemon(true);watcher.setDaemon(true);reader.start();worker.start();watcher.start();
    }
    private static Streams streams(ParcelFileDescriptor owned,ScreenCastSession.Scope scope,ScreenCastSession.Role role,BooleanSupplier allowed) throws IOException {
        Objects.requireNonNull(owned);ParcelFileDescriptor read=null,write=null;
        try {
            Objects.requireNonNull(scope);Objects.requireNonNull(role);Objects.requireNonNull(allowed);
            read=ParcelFileDescriptor.dup(owned.getFileDescriptor());
            if(role==ScreenCastSession.Role.SOURCE)write=ParcelFileDescriptor.dup(owned.getFileDescriptor());
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(read);
            OutputStream output=write==null?null:new ParcelFileDescriptor.AutoCloseOutputStream(write);
            return new Streams(input,output,() -> {
                try{Os.shutdown(owned.getFileDescriptor(),OsConstants.SHUT_RDWR);}catch(Exception ignored){}
                try{input.close();}catch(IOException ignored){}if(output!=null)try{output.close();}catch(IOException ignored){}
                try{owned.close();}catch(IOException ignored){}
            });
        }catch(IOException|RuntimeException failure){
            if(read!=null)try{read.close();}catch(IOException ignored){}if(write!=null)try{write.close();}catch(IOException ignored){}
            try{owned.close();}catch(IOException ignored){}throw failure;
        }
    }
    private boolean current(){
        if(closed.get())return false;
        try{if(allowed.getAsBoolean())return true;}catch(RuntimeException ignored){}
        end("media_permission_revoked");return false;
    }
    @Override public boolean offer(byte[] yjv1){
        if(role!=ScreenCastSession.Role.SOURCE||!current()||yjv1==null)return false;
        if(yjv1.length<ScreenCastPacket.HEADER_BYTES||yjv1.length>ScreenCastPacket.HEADER_BYTES+ScreenCastPacket.MAX_PAYLOAD){end("media_pipe_protocol_failed");return false;}
        synchronized(queueLock){if(closed.get()||frames>=MAX_FRAMES||bytes>MAX_BYTES-yjv1.length)return false;}
        ScreenCastPacket packet;
        try{packet=ScreenCastPacket.decode(yjv1);}catch(IllegalArgumentException error){end("media_pipe_protocol_failed");return false;}
        if(packet.streamId!=scope.streamId()){end("media_pipe_protocol_failed");return false;}
        byte[] snapshot=packet.encode();
        synchronized(queueLock){
            if(closed.get()||frames>=MAX_FRAMES||bytes>MAX_BYTES-snapshot.length)return false;
            if(packet.sequence>lastSequence){lastSequence=packet.sequence;frames++;bytes+=snapshot.length;queue.add(snapshot);queueLock.notifyAll();return true;}
        }
        end("media_pipe_protocol_failed");return false;
    }
    @Override public void setReceiver(Consumer<byte[]> receiver){
        Objects.requireNonNull(receiver);if(role!=ScreenCastSession.Role.SINK)throw new IllegalStateException("media_pipe_wrong_role");
        synchronized(queueLock){if(this.receiver!=null)throw new IllegalStateException("media_pipe_receiver_already_set");this.receiver=receiver;queueLock.notifyAll();}
    }
    @Override public void setOnClosed(Runnable callback){
        Objects.requireNonNull(callback);synchronized(queueLock){if(onClosed!=null)throw new IllegalStateException("media_pipe_close_listener_already_set");onClosed=callback;}
        notifyClosed();
    }
    private void readLoop(){
        try{
            if(role==ScreenCastSession.Role.SOURCE){
                if(input.read()>=0)end("media_pipe_protocol_failed");else end("media_pipe_peer_closed");return;
            }
            while(current()){
                int first=input.read();if(first<0){end("media_pipe_peer_closed");return;}readDeadline=System.nanoTime()+STALL_NS;
                byte[] header=new byte[3];readFully(header);int length=first|(header[0]&255)<<8|(header[1]&255)<<16|(header[2]&255)<<24;
                if(length<ScreenCastPacket.HEADER_BYTES||length>ScreenCastPacket.HEADER_BYTES+ScreenCastPacket.MAX_PAYLOAD){end("media_pipe_protocol_failed");return;}
                synchronized(queueLock){
                    while(!closed.get()&&(frames>=MAX_FRAMES||bytes>MAX_BYTES-length))queueLock.wait(25);
                    if(closed.get())return;frames++;bytes+=length;
                }
                byte[] wire=new byte[length];readFully(wire);ScreenCastPacket packet=ScreenCastPacket.decode(wire);
                if(packet.streamId!=scope.streamId()||packet.sequence<=lastSequence){end("media_pipe_protocol_failed");return;}
                lastSequence=packet.sequence;
                synchronized(queueLock){if(closed.get())return;queue.add(wire);queueLock.notifyAll();}
                readDeadline=0;
            }
        }catch(IOException|InterruptedException|RuntimeException error){if(!closed.get())end("media_pipe_read_failed");}
    }
    private void readFully(byte[] bytes) throws IOException{
        int offset=0;while(offset<bytes.length){if(closed.get())throw new IOException("media_pipe_closed");int count=input.read(bytes,offset,bytes.length-offset);if(count<0)throw new IOException("media_pipe_eof");if(count==0)continue;offset+=count;}
    }
    private void workLoop(){
        try{
            while(current()){
                byte[] wire;Consumer<byte[]> consume;
                synchronized(queueLock){
                    while(!closed.get()&&(queue.isEmpty()||role==ScreenCastSession.Role.SINK&&receiver==null))queueLock.wait(25);
                    if(closed.get())return;wire=queue.remove();consume=receiver;
                }
                workDeadline=System.nanoTime()+STALL_NS;
                try{
                    if(role==ScreenCastSession.Role.SOURCE){int n=wire.length;output.write(new byte[]{(byte)n,(byte)(n>>>8),(byte)(n>>>16),(byte)(n>>>24)});output.write(wire);}
                    else consume.accept(wire);
                }finally{
                    workDeadline=0;synchronized(queueLock){if(!closed.get()){frames--;bytes-=wire.length;}queueLock.notifyAll();}
                }
            }
        }catch(IOException|InterruptedException|RuntimeException error){if(!closed.get())end("media_pipe_dispatch_failed");}
    }
    private void watchLoop(){
        try{
            while(current()){
                long now=System.nanoTime(),read=readDeadline,work=workDeadline;
                if(read!=0&&now>=read||work!=0&&now>=work){end("media_pipe_stalled");return;}
                Thread.sleep(50);
            }
        }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
    }
    private void end(String code){
        if(!closed.compareAndSet(false,true))return;closedCode=code;
        synchronized(queueLock){queue.clear();frames=0;bytes=0;queueLock.notifyAll();}
        try{shutdown.run();}catch(RuntimeException ignored){}
        reader.interrupt();worker.interrupt();watcher.interrupt();notifyClosed();
    }
    private void notifyClosed(){Runnable callback=onClosed;if(closed.get()&&callback!=null&&notified.compareAndSet(false,true))try{callback.run();}catch(RuntimeException ignored){}}
    public boolean isClosed(){return closed.get();}
    public String closedCode(){return closedCode;}
    public int queuedFrames(){synchronized(queueLock){return frames;}}
    public int queuedBytes(){synchronized(queueLock){return bytes;}}
    @Override public void close(){end("media_pipe_closed");}
}
