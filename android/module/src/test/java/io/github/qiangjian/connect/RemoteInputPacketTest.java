package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteInputPacketTest {
    private final RemoteInputSession.Scope scope=new RemoteInputSession.Scope("a".repeat(64),7,13);
    private RemoteInputSender.Command text(String text){return new RemoteInputSender.Command(scope,19,new RemoteInputSender.Text(new XiaomiScreenInputCodec.TextEvent(text)));}
    @Test public void actualDesktopAndUnicodeTextRoundTripWithScopeAndSequence(){
        var event=new XiaomiScreenInputCodec.Event(XiaomiScreenInputCodec.Kind.SCROLL,0,0,false,.5f,.25f,1,-2,0);
        var command=new RemoteInputSender.Command(scope,1,new RemoteInputSender.Desktop(event));assertEquals(command,RemoteInputPacket.decode(RemoteInputPacket.encode(command)));
        var unicode=text("synthetic汉字😀");assertEquals(unicode,RemoteInputPacket.decode(RemoteInputPacket.encode(unicode)));
    }
    @Test public void maximumImeTextUsesFiveBoundedPartsAndOnlyCompleteCommandAssembles(){
        var command=text("x".repeat(8192));var parts=RemoteInputPacket.parts(command);assertEquals(5,parts.size());
        assertEquals(command,RemoteInputPacket.assemble(parts));for(var part:parts)assertTrue(part.data().length<=2048);
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.assemble(parts.subList(0,4)));
        var reordered=new ArrayList<>(parts);reordered.set(0,parts.get(1));assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.assemble(reordered));
    }
    @Test public void fragmentHashOuterScopeAndSequenceCannotBeSubstituted(){
        var original=RemoteInputPacket.parts(text("synthetic"));var part=original.get(0);byte[] data=part.data();data[data.length-1]^=1;
        var tampered=new RemoteInputPacket.Part(scope,19,0,1,part.length(),part.sha256(),data);
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.assemble(List.of(tampered)));
        var wrong=new RemoteInputPacket.Part(new RemoteInputSession.Scope(scope.sessionId(),8,13),19,0,1,part.length(),part.sha256(),part.data());
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.assemble(List.of(wrong)));
        var replay=new RemoteInputPacket.Part(scope,20,0,1,part.length(),part.sha256(),part.data());
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.assemble(List.of(replay)));
        byte[] copied=part.data();copied[0]=0;assertEquals(text("synthetic"),RemoteInputPacket.assemble(original));
    }
    @Test public void malformedHeaderAndUtf8CannotReachNativeReceiver(){
        byte[] bytes=RemoteInputPacket.encode(text("synthetic"));bytes[0]=0;assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decode(bytes));
        byte[] truncated=java.util.Arrays.copyOf(RemoteInputPacket.encode(text("synthetic")),69);assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decode(truncated));
        byte[] invalid=RemoteInputPacket.encode(text("x"));invalid[68]=(byte)0xff;assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decode(invalid));
        byte[] nul=RemoteInputPacket.encode(text("x"));nul[68]=0;assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decode(nul));
    }
    @Test public void nativeResultCodesRoundTripAndNeverCountFragmentsAsNativeAck(){
        for(var result:RemoteInputSession.Result.values()){
            var expected=new RemoteInputPacket.Response(scope,19,result);byte[] wire=RemoteInputPacket.encodeResult(expected);assertEquals(68,wire.length);assertEquals(expected,RemoteInputPacket.decodeResult(wire));
        }
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decodeResult(RemoteInputPacket.encode(text("synthetic"))));
        byte[] unknown=RemoteInputPacket.encodeResult(new RemoteInputPacket.Response(scope,19,RemoteInputSession.Result.ACCEPTED));unknown[7]=99;
        assertThrows(IllegalArgumentException.class,() -> RemoteInputPacket.decodeResult(unknown));
    }
}
