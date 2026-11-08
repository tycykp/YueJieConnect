package io.github.qiangjian.connect;

import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.json.*;

/** Isolated control fixture. The real client and JNI/wire are separately compiled and validated. */
final class BridgeClient {
    interface Subscription extends AutoCloseable {void close();}
    interface TaskCompletion {void success(JSONObject data);void fail(String code);}
    interface VendorExecutor {void execute(JSONObject task,TaskCompletion completion);void cancel(String session,String request);void sessionChanged();}
    final String name;
    BridgeClient remote;
    JSONObject projection;
    String poison;
    int allocations, releases;
    final Map<String,VendorExecutor> executors=new ConcurrentHashMap<>();
    final List<Consumer<JSONObject>> listeners=new ArrayList<>();
    final List<String> operations=Collections.synchronizedList(new ArrayList<>());
    final List<String> requests=Collections.synchronizedList(new ArrayList<>());
    final Map<String,Long> nextStep=new ConcurrentHashMap<>();
    final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    BridgeClient(String name){this.name=name;}
    static void pair(BridgeClient one,BridgeClient two)throws Exception{
        one.remote=two;two.remote=one;
        for(BridgeClient client:List.of(one,two))client.projection=new JSONObject().put("id",client.remote.name)
                .put("session_id","a".repeat(64)).put("operations",new JSONArray(FileTransferBridge.OPERATIONS))
                .put("local_grants",new JSONArray(FileTransferBridge.OPERATIONS));
    }
    JSONObject projection(){return projection;}
    boolean hasLocalGrant(String operation){return projection!=null&&FileTransferBridge.OPERATIONS.contains(operation);}
    Subscription addProjectionListener(Consumer<JSONObject> listener){listeners.add(listener);return ()->listeners.remove(listener);}
    Subscription registerExecutor(String operation,VendorExecutor executor){
        if(executors.putIfAbsent(operation,executor)!=null)throw new IllegalStateException("duplicate_executor");
        return ()->executors.remove(operation,executor);
    }
    void allocateFile(String session,Consumer<JSONObject> completion){
        try{completion.accept(new JSONObject().put("status","ok").put("session_id",session).put("transfer",String.format("%032x",++allocations)));}
        catch(JSONException failure){throw new AssertionError(failure);}
    }
    void releaseFile(String session,String transfer,String direction,Consumer<JSONObject> completion){
        try{
            if(inFlight.stream().anyMatch(id->id.startsWith("file:"+transfer+":")))completion.accept(new JSONObject().put("status","error").put("code","file_busy"));
            else{releases++;completion.accept(new JSONObject().put("status","ok").put("session_id",session).put("transfer",transfer).put("released",true));}
        }catch(JSONException failure){throw new AssertionError(failure);}
    }
    void cancel(String session,String request,Consumer<JSONObject> completion){
        for(VendorExecutor executor:remote.executors.values())executor.cancel(session,request);
        completion.accept(new JSONObject());
    }
    void request(String peer,String session,String operation,String request,JSONObject payload,Consumer<JSONObject> completion){
        try{
            if(projection==null||!peer.equals(projection.optString("id")))throw new AssertionError("wrong_peer");
            String transfer=payload.getString("transfer");
            long step=Long.parseLong(request.substring(request.lastIndexOf(':')+1));
            synchronized(nextStep){assertCanonical(request,transfer,step);long expected=nextStep.getOrDefault(transfer,0L);
                if(step!=expected)throw new AssertionError("file step skipped/reused");nextStep.put(transfer,step+1);}
            operations.add(operation);requests.add(request);inFlight.add(request);
            VendorExecutor executor=remote.executors.get(operation);
            if(executor==null)throw new AssertionError("unregistered_executor");
            JSONObject task=new JSONObject().put("projection_id",remote.projection.optString("id")).put("session_id",session)
                    .put("operation",operation).put("request_id",request).put("payload",new JSONObject(payload.toString()))
                    .put("deadline_elapsed_ms",SystemClock.elapsedRealtime()+15_000);
            executor.execute(task,new TaskCompletion(){
                public void success(JSONObject data){
                    inFlight.remove(request);
                    try{
                        JSONObject copy=new JSONObject(data.toString());
                        if("transfer".equals(poison)){copy.put("transfer","f".repeat(32));poison=null;}
                        if("commit_hash".equals(poison)&&operation.equals("file_commit")){copy.put("sha256","f".repeat(64));poison=null;}
                        JSONObject response=new JSONObject().put("status","ok").put("session_id",session).put("request_id",request)
                                .put("result",new JSONObject().put("operation",operation).put("data",copy));
                        if("session".equals(poison)){response.put("session_id","b".repeat(64));poison=null;}
                        completion.accept(response);
                    }catch(JSONException failure){throw new AssertionError(failure);}
                }
                public void fail(String code){inFlight.remove(request);try{completion.accept(new JSONObject().put("status","error").put("code",code).put("request_id",request).put("session_id",session));}catch(JSONException e){throw new AssertionError(e);}}
            });
        }catch(JSONException failure){throw new AssertionError(failure);}
    }
    private static void assertCanonical(String request,String transfer,long step){
        if(!request.equals("file:"+transfer+":"+step))throw new AssertionError("noncanonical_step");
    }
    void publish() {for(var listener:List.copyOf(listeners))listener.accept(projection);}
}
