package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Diagnostic revision agreement, after kernel caller/package verification. This is not an
 * identity proof and never grants a capability. Cached injected code must not call a new core. */
final class NativeRuntimeRegistry {
    private record Key(int uid,int pid) {}
    private record Seen(String packageName,String revision,boolean matches,long time,String token) {}
    private final LinkedHashMap<Key,Seen> clients=new LinkedHashMap<>();
    synchronized boolean observe(int uid,int pid,String packageName,String revision,String expected,long now){
        boolean valid=revision!=null&&revision.matches("[0-9a-f]{64}");
        boolean matches=valid&&revision.equals(expected);
        clients.entrySet().removeIf(entry->now-entry.getValue().time()>10_000);
        Key key=new Key(uid,pid);
        if(!clients.containsKey(key)&&clients.size()>=64)clients.remove(clients.keySet().iterator().next());
        Seen previous=clients.get(key);
        String token=matches&&previous!=null&&revision.equals(previous.revision())?previous.token():null;
        clients.put(key,new Seen(packageName,valid?revision:"missing_or_invalid",matches,now,token));
        return matches;
    }
    synchronized void opened(int uid,int pid,String token,long now){
        Key key=new Key(uid,pid);Seen seen=clients.get(key);
        if(seen!=null&&seen.matches()&&token!=null&&!token.isEmpty())
            clients.put(key,new Seen(seen.packageName(),seen.revision(),true,now,token));
    }
    synchronized boolean accepts(int uid,int pid,String token,String expected,long now){
        Key key=new Key(uid,pid);Seen seen=clients.get(key);
        if(seen==null||!seen.matches()||!seen.revision().equals(expected)||seen.token()==null||!seen.token().equals(token))return false;
        clients.put(key,new Seen(seen.packageName(),seen.revision(),true,now,seen.token()));return true;
    }
    synchronized JSONObject snapshot(String expected,long now)throws JSONException{
        JSONArray values=new JSONArray();
        for(var entry:clients.entrySet()){
            Seen seen=entry.getValue();long age=Math.max(0,now-seen.time());
            if(age>10_000)continue;
            values.put(new JSONObject().put("uid",entry.getKey().uid()).put("pid",entry.getKey().pid())
                .put("package",seen.packageName()).put("revision",seen.revision()).put("matches",seen.matches()).put("age_ms",age));
        }
        return new JSONObject().put("status","ok").put("expected_revision",expected).put("clients",values);
    }
}
