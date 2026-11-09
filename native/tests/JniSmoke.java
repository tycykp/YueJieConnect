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
        contains(call(false,1,"{\"op\":\"bridge_abort\",\"session\":\"stale\"}"),"forbidden");
        contains(call(true,0,"{\"op\":\"bridge_abort\",\"session\":\"stale\"}"),"stale_session");
        contains(call(true,0,"{\"op\":\"set_enabled\",\"enabled\":true}"),"ok");
        var vendor=Pattern.compile("\"token\":\"([0-9a-f]{64})\"").matcher(call(false,1,"{\"op\":\"open\"}"));
        if(!vendor.find())throw new AssertionError("Missing executor lease");
        String lease=vendor.group(1);
        String registration="{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":1,\"operations\":[\"clipboard_write\"]}";
        contains(call(false,1,registration),"ok");
        contains(call(false,1,registration),"replay");
        contains(call(true,0,"{\"op\":\"bridge_status\"}"),"clipboard_write");
        String poll="{\"op\":\"bridge_poll_executor\",\"token\":\""+lease+"\",\"sequence\":2}";
        contains(NativeCore.dispatch(20,22,false,1,100,poll),"forbidden");
        contains(call(false,1,poll),"\"task\":null");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":3,\"operations\":[\"file_transfer\"]}"),"unsupported_operation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":4,\"operations\":[]}"),"ok");
        if(call(true,0,"{\"op\":\"bridge_status\"}").contains("clipboard_write"))throw new AssertionError("Executor capability not withdrawn");
        contains(call(true,0,"{\"op\":\"bridge_confirm\",\"session\":\"stale\",\"operations\":[\"file_transfer\"]}"),"stale_confirmation");
        contains(call(true,0,"{\"op\":\"bridge_confirm\",\"session\":\"stale\",\"operations\":[\"device_profile\"]}"),"stale_confirmation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":5,\"operations\":[\"clipboard_rich_write\",\"notification_post\",\"notification_remove\",\"notification_action\"]}"),"ok");
        contains(call(true,0,"{\"op\":\"bridge_status\"}"),"clipboard_rich_write");
        contains(call(true,0,"{\"op\":\"bridge_confirm\",\"session\":\"stale\",\"operations\":[\"clipboard_rich_write\",\"notification_post\",\"notification_action\"]}"),"stale_confirmation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":6,\"operations\":[]}"),"ok");
        if(call(true,0,"{\"op\":\"bridge_status\"}").contains("notification_post"))throw new AssertionError("Notification executor not withdrawn");
        contains(call(true,0,"{\"op\":\"bridge_confirm\",\"session\":\"stale\",\"operations\":[\"screen_video\",\"camera_video\"]}"),"stale_confirmation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":7,\"operations\":[\"screen_video\"]}"),"unsupported_operation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":8,\"operations\":[\"file_prepare\",\"file_offer\",\"file_chunk\",\"file_commit\",\"file_cancel\",\"clipboard_uri_write\",\"media_control\"]}"),"ok");
        contains(call(true,0,"{\"op\":\"bridge_status\"}"),"file_prepare");
        contains(call(false,1,"{\"op\":\"bridge_file_allocate\",\"token\":\""+lease+"\",\"sequence\":9,\"session_id\":\"stale\"}"),"stale_session");
        contains(call(true,0,"{\"op\":\"bridge_confirm\",\"session\":\"stale\",\"operations\":[\"file_transfer\",\"clipboard_uri_write\",\"media_control\"]}"),"stale_confirmation");
        contains(call(false,1,"{\"op\":\"bridge_register_executor\",\"token\":\""+lease+"\",\"sequence\":10,\"operations\":[]}"),"ok");
        String disconnect="{\"op\":\"bridge_disconnect\",\"token\":\""+lease+"\",\"sequence\":11,\"projection_id\":\"wrong-peer\",\"session_id\":\"stale\"}";
        contains(NativeCore.dispatch(20,22,false,1,100,disconnect),"forbidden");
        contains(call(false,1,disconnect),"stale_session");
        contains(call(false,1,disconnect),"replay");
        contains(call(false,1,"{\"op\":\"bridge_disconnect\"}"),"invalid_request");
        System.out.println("JNI smoke passed: consent, Unicode, observation, catalog, replay, denial, revoke, unsupported.");
        System.out.println("JNI v3 passed: executor registration, PID binding, replay, polling, operation whitelist, capability withdrawal.");
        System.out.println("JNI v4 passed: typed per-session consent rejects unsupported operations and stale confirmation.");
        System.out.println("JNI v6 passed: rich clipboard and separately granted notification operations register and withdraw.");
        System.out.println("JNI v7 passed: typed file/URI/media registration and stale file allocation/consent denial.");
    }
}
