package io.github.qiangjian.connect;

import java.util.regex.Pattern;

/** Executes the actual Rust cdylib through the JVM, no JNI mock. Synthetic observations only. */
public final class JniSmoke {
    private static String call(boolean owner,int source,String json) {
        return NativeCore.dispatch(owner?10:20,owner?10:21,owner,source,100,json);
    }
    private static void contains(String text,String expected) {
        if(!text.contains(expected)) throw new AssertionError("Expected "+expected+" in "+text);
    }
    public static void main(String[] args) {
        contains(call(false,1,"{\"op\":\"open\"}"),"consent_required");
        contains(call(true,0,"{\"op\":\"set_enabled\",\"enabled\":true}"),"ok");
        String opened=call(false,1,"{\"op\":\"open\"}");
        var matcher=Pattern.compile("\"token\":\"([0-9a-f]{64})\"").matcher(opened);
        if(!matcher.find()) throw new AssertionError("No valid session token");
        String observe="{\"op\":\"observe\",\"token\":\""+matcher.group(1)+"\",\"sequence\":1,\"event\":{"+
            "\"kind\":\"found\",\"native_id\":\"synthetic-private-id\",\"name\":\"测试平板 📱\","+
            "\"device_type\":\"3\",\"capability\":7,\"service_id\":\"00070A2A\"}}";
        contains(call(false,1,observe),"observation_accepted");
        contains(call(false,1,observe),"replay");
        String catalog=call(true,0,"{\"op\":\"request\",\"capability\":\"local_device_catalog\"}");
        contains(catalog,"测试平板 📱");contains(catalog,"unsupported");
        if(catalog.contains("synthetic-private-id")) throw new AssertionError("Native ID leaked");
        contains(call(false,1,"{\"op\":\"catalog\"}"),"forbidden");
        contains(call(true,0,"{\"op\":\"request\",\"capability\":\"native_file_transfer\"}"),"unsupported");
        contains(call(true,0,"{\"op\":\"set_enabled\",\"enabled\":false}"),"ok");
        contains(call(false,1,observe),"consent_required");
        contains(NativeCore.dispatch(-1,21,false,1,100,"{}"),"invalid_caller");
        String zeros="0,".repeat(31)+"0";
        String local="{\"peer_key\":["+zeros+"],\"platform\":\"hyper_os4\",\"kind\":\"tablet\",\"name\":\"JNI 测试平板\",\"manufacturer\":\"Test\",\"model\":\"Synthetic\",\"os_build\":\"Test\",\"operations\":[]}";
        contains(NativeCore.initialize("build/jni-test/identity.key",local),"ok");
        contains(call(true,0,"{\"op\":\"bridge_status\"}"),"stopped");
        contains(call(false,1,"{\"op\":\"bridge_status\"}"),"forbidden");
        contains(call(false,1,"{\"op\":\"bridge_listen\",\"port\":0}"),"forbidden");
        System.out.println("JNI smoke passed: consent, Unicode, observation, catalog, replay, denial, revoke, unsupported.");
    }
}
