package io.github.yuejie.connect;

import java.util.*;

/** Persist only authenticated identities and the user's original operation grants, never sessions. */
final class PairedPeers {
    private static final Set<String> INPUT_GRANTS=Set.of("device_profile","battery_status","clipboard_write",
            "clipboard_rich_write","device_finder","notification_post","notification_remove","notification_action",
            "app_relay","file_transfer","clipboard_uri_write","media_control","screen_input","screen_video",
            "camera_video","telephony_call_state","telephony_call_control","telephony_sms_status");
    private final Map<String,Set<String>> entries=new TreeMap<>();
    PairedPeers(Set<String> stored){
        for(String row:stored){
            String[] parts=row.split("\\|",-1);
            if(parts.length!=2||!NativePairingPolicy.session(parts[0]))continue;
            Set<String> grants=new TreeSet<>(Arrays.asList(parts[1].split(",",-1)));
            if(grants.contains("device_profile")&&INPUT_GRANTS.containsAll(grants)&&entries.size()<16)
                entries.put(parts[0],Set.copyOf(grants));
        }
    }
    boolean remember(String key,Collection<String> granted){
        if(!NativePairingPolicy.session(key)||(!entries.containsKey(key)&&entries.size()>=16))return false;
        Set<String> inputs=new TreeSet<>(granted);inputs.retainAll(INPUT_GRANTS);
        if(!inputs.contains("device_profile"))return false;
        return !Set.copyOf(inputs).equals(entries.put(key,Set.copyOf(inputs)));
    }
    Set<String> grants(String authenticatedKey){return entries.getOrDefault(authenticatedKey,Set.of());}
    boolean isEmpty(){return entries.isEmpty();}
    int size(){return entries.size();}
    Set<String> encode(){
        Set<String> rows=new TreeSet<>();
        entries.forEach((key,grants)->rows.add(key+"|"+String.join(",",new TreeSet<>(grants))));
        return rows;
    }
    static boolean initiates(String local,String remote){
        return NativePairingPolicy.session(local)&&NativePairingPolicy.session(remote)&&local.compareTo(remote)<0;
    }
}
