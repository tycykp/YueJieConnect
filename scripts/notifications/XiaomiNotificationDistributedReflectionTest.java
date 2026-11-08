package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import org.junit.Test;

/** Uses the captured OEM's annotation-stripped plain Java NotificationMessage, not a UI stub. */
public class XiaomiNotificationDistributedReflectionTest {
    @Test public void realBuilderAndPrivateTargetFieldPreserveAllThirteenFields()throws Exception{
        var value=new XiaomiNotificationDistributedCodec.Message("synthetic-source","synthetic-target","io.github.synthetic.test",
                "synthetic-user|tag|900007",900007,23,"合成来源","人工标题","人工内容",1700000000000L,"msg",null,"{}");
        Object nativeMessage=XiaomiNotificationDistributedCodec.build(getClass().getClassLoader(),value);
        assertEquals(value,XiaomiNotificationDistributedCodec.capture(nativeMessage));
        assertThrows(NoSuchMethodException.class,()->nativeMessage.getClass().getMethod("getTargetDeviceId"));
        var field=nativeMessage.getClass().getDeclaredField("targetDeviceId");field.setAccessible(true);
        assertEquals("synthetic-target",field.get(nativeMessage));
    }
    @Test public void optionalNativeFieldsAndForeignClassRejectionAreExact()throws Exception{
        var value=new XiaomiNotificationDistributedCodec.Message("source","target","io.github.synthetic.test","key",-1,0,"device","title","text",0,null,"",null);
        assertEquals(value,XiaomiNotificationDistributedCodec.capture(XiaomiNotificationDistributedCodec.build(getClass().getClassLoader(),value)));
        assertThrows(IllegalArgumentException.class,()->XiaomiNotificationDistributedCodec.capture(new Object()));
    }
}
