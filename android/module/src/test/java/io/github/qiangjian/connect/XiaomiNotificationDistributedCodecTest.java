package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;

public class XiaomiNotificationDistributedCodecTest {
    private XiaomiNotificationDistributedCodec.Message message(String title,String category){
        return new XiaomiNotificationDistributedCodec.Message("actual-source-id","actual-target-id","io.github.synthetic.test","0|synthetic|1",900006,7,"合成设备",title,"人工测试内容",123L,category,null,"{}");
    }
    @Test public void allNativeFieldsAndNullDistinctionSurviveRoundTrip(){
        var value=message("合成标题",null);
        assertEquals(value,XiaomiNotificationDistributedCodec.decode(XiaomiNotificationDistributedCodec.encode(value)));
        assertNotEquals(XiaomiNotificationDistributedCodec.decode(XiaomiNotificationDistributedCodec.encode(value)),message("合成标题",""));
    }
    @Test public void binaryCannotBeConfusedWithFrameworkOrAcceptTrailingBytes(){
        byte[] good=XiaomiNotificationDistributedCodec.encode(message("test","msg"));
        byte[] bad=good.clone();ByteBuffer.wrap(bad).putInt(0x514a4e31);
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(bad));
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(Arrays.copyOf(good,good.length+1)));
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(Arrays.copyOf(good,good.length-1)));
    }
    @Test public void strictUtf8AndFieldLimitsRejectAmbiguousTargets(){
        assertThrows(IllegalArgumentException.class,()->message("\ud800",null));
        assertThrows(IllegalArgumentException.class,()->message("x".repeat(1025),null));
        byte[] good=XiaomiNotificationDistributedCodec.encode(message("test",null));
        byte[] malformed=good.clone();malformed[8]=(byte)0xc0;malformed[9]=(byte)0xaf;
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(malformed));
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(new byte[8193]));
    }
    @Test public void lengthPrefixCannotAllocateOutsideRemainingFrame(){
        byte[] good=XiaomiNotificationDistributedCodec.encode(message("test",null));
        ByteBuffer.wrap(good).putInt(4,Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.decode(good));
    }
}
