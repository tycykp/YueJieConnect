package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded copied input data for an authenticated, ordered control owner. This does not register
 * a public operation or execute native input. Complete/assemble then call RemoteInputReceiver;
 * a buffered fragment must never be acknowledged as a native execution result. */
public final class RemoteInputPacket {
    public static final int HEADER_BYTES=68,MAX_PACKET=HEADER_BYTES+8192,PART_BYTES=2048;
    private static final int COMMAND=0x514a4931,RESULT=0x514a5231;
    public record Response(RemoteInputSession.Scope scope,long sequence,RemoteInputSession.Result result) {
        public Response {Objects.requireNonNull(scope);Objects.requireNonNull(result);if(sequence<=0)throw invalid();}
        @Override public String toString(){return "NativeInputResponse";}
    }
    public record Part(RemoteInputSession.Scope scope,long sequence,int index,int count,int length,byte[] sha256,byte[] data) {
        public Part {
            Objects.requireNonNull(scope);
            if(sequence<=0||length<HEADER_BYTES||length>MAX_PACKET||count!=(length+PART_BYTES-1)/PART_BYTES||index<0||index>=count
                    ||sha256==null||sha256.length!=32||data==null||data.length!=Math.min(PART_BYTES,length-index*PART_BYTES))throw invalid();
            sha256=sha256.clone();data=data.clone();
        }
        @Override public byte[] sha256(){return sha256.clone();}
        @Override public byte[] data(){return data.clone();}
        @Override public String toString(){return "NativeInputPart";}
    }
    private RemoteInputPacket(){}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid_native_input_packet");}
    private static ByteBuffer header(int magic,int type,RemoteInputSession.Scope scope,long sequence,int length){
        ByteBuffer b=ByteBuffer.allocate(HEADER_BYTES+length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(magic).putShort((short)1).putShort((short)type);
        String session=scope.sessionId();for(int i=0;i<64;i+=2)b.put((byte)Integer.parseInt(session.substring(i,i+2),16));
        return b.putLong(scope.streamId()).putLong(scope.focusEpoch()).putLong(sequence).putInt(length);
    }
    public static byte[] encode(RemoteInputSender.Command command){
        Objects.requireNonNull(command);
        if(command.payload() instanceof RemoteInputSender.Text text){
            byte[] bytes=text.event().text().getBytes(StandardCharsets.UTF_8);
            return header(COMMAND,5,command.scope(),command.sequence(),bytes.length).put(bytes).array();
        }
        XiaomiScreenInputCodec.Event e=((RemoteInputSender.Desktop)command.payload()).event();
        int type=switch(e.kind()){case MOVE->1;case BUTTON->2;case SCROLL->3;case KEY->4;};
        return header(COMMAND,type,command.scope(),command.sequence(),32).putInt(e.keyCode()).putInt(e.button()).putInt(e.down()?1:0)
                .putFloat(e.x()).putFloat(e.y()).putFloat(e.horizontal()).putFloat(e.vertical()).putInt(e.metaState()).array();
    }
    private record Header(int type,RemoteInputSession.Scope scope,long sequence,int length,ByteBuffer bytes){}
    private static Header decodeHeader(byte[] wire,int magic){
        if(wire==null||wire.length<HEADER_BYTES||wire.length>MAX_PACKET)throw invalid();
        ByteBuffer b=ByteBuffer.wrap(wire.clone()).order(ByteOrder.BIG_ENDIAN);
        if(b.getInt()!=magic||b.getShort()!=1)throw invalid();int type=Short.toUnsignedInt(b.getShort());
        StringBuilder session=new StringBuilder(64);for(int i=0;i<32;i++){int v=Byte.toUnsignedInt(b.get());session.append(Character.forDigit(v>>>4,16)).append(Character.forDigit(v&15,16));}
        var scope=new RemoteInputSession.Scope(session.toString(),b.getLong(),b.getLong());long sequence=b.getLong();int length=b.getInt();
        if(sequence<=0||length!=b.remaining())throw invalid();return new Header(type,scope,sequence,length,b);
    }
    public static RemoteInputSender.Command decode(byte[] wire){
        Header h=decodeHeader(wire,COMMAND);RemoteInputSender.Payload payload;
        if(h.type==5){
            if(h.length<1||h.length>8192)throw invalid();
            try {
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(h.bytes).toString();
                payload=new RemoteInputSender.Text(new XiaomiScreenInputCodec.TextEvent(text));
            }catch(CharacterCodingException error){throw invalid();}
        }else{
            XiaomiScreenInputCodec.Kind kind=switch(h.type){case 1->XiaomiScreenInputCodec.Kind.MOVE;case 2->XiaomiScreenInputCodec.Kind.BUTTON;case 3->XiaomiScreenInputCodec.Kind.SCROLL;case 4->XiaomiScreenInputCodec.Kind.KEY;default->throw invalid();};
            if(h.length!=32)throw invalid();ByteBuffer b=h.bytes;int key=b.getInt(),button=b.getInt(),down=b.getInt();if(down!=0&&down!=1)throw invalid();
            payload=new RemoteInputSender.Desktop(new XiaomiScreenInputCodec.Event(kind,key,button,down==1,b.getFloat(),b.getFloat(),b.getFloat(),b.getFloat(),b.getInt()));
        }
        return new RemoteInputSender.Command(h.scope,h.sequence,payload);
    }
    private static int code(RemoteInputSession.Result result){return switch(result){
        case ACCEPTED->1;case DENIED->2;case STALE_SESSION->3;case STALE_FOCUS->4;case REPLAY->5;case NO_FOCUS->6;case INVALID_PAYLOAD->7;case NATIVE_REJECTED->8;};}
    public static byte[] encodeResult(Response response){return header(RESULT,code(response.result),response.scope,response.sequence,0).array();}
    public static Response decodeResult(byte[] wire){
        Header h=decodeHeader(wire,RESULT);if(h.length!=0)throw invalid();
        var result=switch(h.type){case 1->RemoteInputSession.Result.ACCEPTED;case 2->RemoteInputSession.Result.DENIED;case 3->RemoteInputSession.Result.STALE_SESSION;
            case 4->RemoteInputSession.Result.STALE_FOCUS;case 5->RemoteInputSession.Result.REPLAY;case 6->RemoteInputSession.Result.NO_FOCUS;
            case 7->RemoteInputSession.Result.INVALID_PAYLOAD;case 8->RemoteInputSession.Result.NATIVE_REJECTED;default->throw invalid();};
        return new Response(h.scope,h.sequence,result);
    }
    public static List<Part> parts(RemoteInputSender.Command command){
        byte[] wire=encode(command),sha=digest(wire);int count=(wire.length+PART_BYTES-1)/PART_BYTES;List<Part> parts=new ArrayList<>(count);
        for(int i=0;i<count;i++)parts.add(new Part(command.scope(),command.sequence(),i,count,wire.length,sha,Arrays.copyOfRange(wire,i*PART_BYTES,Math.min(wire.length,(i+1)*PART_BYTES))));
        return List.copyOf(parts);
    }
    /** Caller must bound pending assemblies, include queue time in the original <=2s deadline,
     * and preserve per-stream seq across desktop/IME. This complete-list helper allocates <=8260B. */
    public static RemoteInputSender.Command assemble(List<Part> parts){
        if(parts==null||parts.isEmpty()||parts.size()>5)throw invalid();Part first=parts.get(0);if(first==null||parts.size()!=first.count)throw invalid();
        ByteBuffer wire=ByteBuffer.allocate(first.length);
        for(int i=0;i<parts.size();i++){
            Part part=parts.get(i);
            if(part==null||part.index!=i||!part.scope.equals(first.scope)||part.sequence!=first.sequence||part.count!=first.count||part.length!=first.length
                    ||!MessageDigest.isEqual(part.sha256,first.sha256))throw invalid();wire.put(part.data);
        }
        if(!MessageDigest.isEqual(digest(wire.array()),first.sha256))throw invalid();
        RemoteInputSender.Command command=decode(wire.array());if(!command.scope().equals(first.scope)||command.sequence()!=first.sequence)throw invalid();return command;
    }
    private static byte[] digest(byte[] data){try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException error){throw new IllegalStateException("sha256_unavailable");}}
}
