package io.github.qiangjian.connect;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit remote logical open/close/state. No profile or battery result can create a lease. */
final class NativeLogicalClient {
    record Lease(String peer,String session,String channel,String transportAddress,java.util.Set<String> operations) {
        Lease { operations=java.util.Set.copyOf(operations);if(!NativeLogicalSessions.validOperations(operations))throw new IllegalArgumentException("unsupported_operation"); }
    }
    private final BridgeClient client;
    private final AtomicLong serial=new AtomicLong();
    private final String requestPrefix="logical:"+java.util.UUID.randomUUID().toString().replace("-","")+":";
    NativeLogicalClient(BridgeClient client){this.client=client;}
    Lease allocate(String id,String session){
        JSONObject p=client.projection();
        // Prefer the long-standing clipboard contract for old peers. Never use profile/battery
        // as evidence of an implemented native business channel.
        for(String operation:new String[]{"clipboard_write","clipboard_rich_write","app_relay","media_control","clipboard_uri_write","file_prepare"})
            if(supports(p,operation))return allocate(id,session,java.util.Set.of(operation));
        throw new IllegalStateException("executor_unavailable");
    }
    Lease allocate(String id,String session,java.util.Set<String> operations){
        JSONObject p=client.projection();
        if(p==null||!id.equals(p.optString("id"))||!session.equals(p.optString("session_id")))throw new IllegalStateException("stale_session");
        if(!NativeLogicalSessions.validOperations(operations))throw new IllegalArgumentException("unsupported_operation");
        if(!supports(p,"logical_open")||!supports(p,"logical_state")||!supports(p,"logical_close")||operations.stream().anyMatch(op->!supports(p,op)))throw new IllegalStateException("executor_unavailable");
        return new Lease(id,session,java.util.UUID.randomUUID().toString().replace("-",""),p.optString("transport_address"),operations);
    }
    boolean current(Lease lease){JSONObject p=client.projection();return lease!=null&&p!=null&&lease.peer().equals(p.optString("id"))&&lease.session().equals(p.optString("session_id"))&&supports(p,"logical_state")&&lease.operations().stream().allMatch(op->supports(p,op));}
    private static boolean supports(JSONObject p,String operation){JSONArray values=p==null?null:p.optJSONArray("operations");if(values!=null)for(int i=0;i<values.length();i++)if(operation.equals(values.optString(i)))return true;return false;}
    CompletableFuture<Boolean> open(Lease lease){return request(lease,"logical_open",true);}
    CompletableFuture<Boolean> close(Lease lease){return request(lease,"logical_close",false);}
    CompletableFuture<Boolean> state(Lease lease){return request(lease,"logical_state",null);}
    private CompletableFuture<Boolean> request(Lease lease,String operation,Boolean expected){
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        try{
            JSONObject payload=new JSONObject().put("channel_id",lease.channel());
            if(operation.equals("logical_open"))payload.put("operations",new JSONArray(new java.util.TreeSet<>(lease.operations())));
            String request=requestPrefix+operation+":"+serial.incrementAndGet();
            client.request(lease.peer(),lease.session(),operation,request,payload,response->{
                JSONObject receipt=response.optJSONObject("result"),data=receipt==null?null:receipt.optJSONObject("data");
                if(!"ok".equals(response.optString("status"))||!lease.session().equals(response.optString("session_id"))||!request.equals(response.optString("request_id"))
                        ||receipt==null||!operation.equals(receipt.optString("operation"))||data==null||!lease.channel().equals(data.optString("channel_id"))){
                    result.completeExceptionally(new IllegalStateException(response.optString("code","invalid_logical_result")));return;
                }
                boolean open="open".equals(data.optString("state"));
                if(!open&&!"closed".equals(data.optString("state"))){result.completeExceptionally(new IllegalStateException("invalid_logical_result"));return;}
                JSONArray returned=data.optJSONArray("operations");java.util.Set<String> actual=new java.util.HashSet<>();
                if(returned!=null)for(int i=0;i<returned.length();i++)actual.add(returned.optString(i));
                if(returned==null||returned.length()!=actual.size()||!(open?lease.operations():java.util.Set.of()).equals(actual)||open&&!current(lease)){
                    result.completeExceptionally(new IllegalStateException("invalid_logical_result"));return;
                }
                if(expected!=null&&open!=expected){result.completeExceptionally(new IllegalStateException("unexpected_logical_state"));return;}
                result.complete(open);
            });
        }catch(org.json.JSONException|RuntimeException failure){result.completeExceptionally(failure);}
        return result;
    }
}
