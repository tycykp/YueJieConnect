package io.github.yuejie.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Actual milink NotificationMessage fields. Separate from Mirror's PC-only NotificationPost. */
final class XiaomiNotificationDistributedCodec {
    static final int MAX_BYTES = 8192;
    record Message(String senderDeviceId, String targetDeviceId, String packageName, String notificationKey,
                   int notificationId, int messageNumber, String deviceName, String title, String content,
                   long timestamp, String category, String verifyCode, String focusParam) {
        Message {
            bounded(senderDeviceId, 256, false); bounded(targetDeviceId, 256, false);
            bounded(packageName, 200, false); bounded(notificationKey, 1024, false);
            if (!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw invalid();
            bounded(deviceName, 256, false); bounded(title, 1024, false); bounded(content, 2048, false);
            bounded(category, 128, true); bounded(verifyCode, 64, true); bounded(focusParam, 2048, true);
            if (timestamp < 0) throw invalid();
        }
    }
    static byte[] encode(Message m) {
        try {
            var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer);
            out.writeInt(0x594a4431); // YJD1: not a YJN1 framework notification frame.
            for (String s : List.of(m.senderDeviceId, m.targetDeviceId, m.packageName, m.notificationKey)) write(out, s);
            out.writeInt(m.notificationId); out.writeInt(m.messageNumber);
            for (String s : List.of(m.deviceName, m.title, m.content)) write(out, s);
            out.writeLong(m.timestamp);
            nullable(out, m.category); nullable(out, m.verifyCode); nullable(out, m.focusParam);
            out.flush(); if (buffer.size() > MAX_BYTES) throw invalid(); return buffer.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static Message decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x594a4431) throw invalid();
            var m = new Message(read(in,256,false),read(in,256,false),read(in,200,false),read(in,1024,false),
                    in.readInt(),in.readInt(),read(in,256,false),read(in,1024,false),read(in,2048,false),in.readLong(),
                    read(in,128,true),read(in,64,true),read(in,2048,true));
            if (in.available() != 0) throw invalid(); return m;
        } catch (Exception e) { throw invalid(); }
    }
    static Message capture(Object nativeMessage) throws ReflectiveOperationException {
        Class<?> c = nativeMessage.getClass();
        if (!c.getName().equals("com.xiaomi.dist.notification.common.data.NotificationMessage")) throw invalid();
        // The sampled class has a targetDeviceId field and Builder setter, but no getter.
        var target=c.getDeclaredField("targetDeviceId");target.setAccessible(true);
        return new Message(string(c,nativeMessage,"getSenderDeviceId"),(String)target.get(nativeMessage),
                string(c,nativeMessage,"getPackageName"),string(c,nativeMessage,"getNotificationKey"),
                (Integer)c.getMethod("getNotificationId").invoke(nativeMessage),(Integer)c.getMethod("getMessageNumber").invoke(nativeMessage),
                string(c,nativeMessage,"getDeviceName"),string(c,nativeMessage,"getTitle"),string(c,nativeMessage,"getContent"),
                (Long)c.getMethod("getTimestamp").invoke(nativeMessage),string(c,nativeMessage,"getCategory"),
                string(c,nativeMessage,"getVerifyCode"),string(c,nativeMessage,"getFocusParam"));
    }
    static Object build(ClassLoader loader, Message m) throws ReflectiveOperationException {
        Class<?> c = Class.forName("com.xiaomi.dist.notification.common.data.NotificationMessage$Builder",false,loader);
        Object b = c.getConstructor().newInstance();
        String[] methods = {"setSenderDeviceId","setTargetDeviceId","setPackageName","setNotificationKey","setDeviceName","setTitle","setContent","setCategory","setVerifyCode","setFocusParam"};
        String[] values = {m.senderDeviceId,m.targetDeviceId,m.packageName,m.notificationKey,m.deviceName,m.title,m.content,m.category,m.verifyCode,m.focusParam};
        for(int i=0;i<methods.length;i++)c.getMethod(methods[i],String.class).invoke(b,values[i]);
        c.getMethod("setNotificationId",int.class).invoke(b,m.notificationId);
        c.getMethod("setMessageNumber",int.class).invoke(b,m.messageNumber);
        c.getMethod("setTimestamp",long.class).invoke(b,m.timestamp);
        return c.getMethod("build").invoke(b);
    }
    private static String string(Class<?> c,Object value,String name)throws ReflectiveOperationException{return (String)c.getMethod(name).invoke(value);}
    private static byte[] utf8(String s) {
        try { ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));byte[] out=new byte[b.remaining()];b.get(out);return out; }
        catch(Exception invalid){throw invalid();}
    }
    private static void bounded(String s,int limit,boolean nullable){if(s==null){if(nullable)return;throw invalid();}if((!nullable&&s.isEmpty())||utf8(s).length>limit)throw invalid();}
    private static void write(DataOutputStream out,String s)throws java.io.IOException{byte[] b=utf8(s);out.writeInt(b.length);out.write(b);}
    private static void nullable(DataOutputStream out,String s)throws java.io.IOException{if(s==null)out.writeInt(-1);else write(out,s);}
    private static String read(DataInputStream in,int limit,boolean nullable)throws Exception{
        int count=in.readInt();if(count==-1&&nullable)return null;if(count<0||count>limit||count>in.available())throw invalid();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(count))).toString();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid_xiaomi_distributed_notification");}
}
