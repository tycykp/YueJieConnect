package io.github.qiangjian.connect;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit remote logical open/close/state. No profile or battery result can create a lease. */
final class NativeLogicalClient {
    record Lease(String peer,String session,String channel,String transportAddress) {}
    private final BridgeClient client;
    private final AtomicLong serial=new AtomicLong();
    NativeLogicalClient(BridgeClient client){this.client=client;}
    Lease allocate(String id,String session){
        JSONObject p=client.projection();
        if(p==null||!id.equals(p.optString("id"))||!session.equals(p.optString("session_id")))throw new IllegalStateException("stale_session");
        return new Lease(id,session,java.util.UUID.randomUUID().toString().replace("-",""),p.optString("transport_address"));
    }
    CompletableFuture<Boolean> open(Lease lease){return request(lease,"logical_open",true);}
    CompletableFuture<Boolean> close(Lease lease){return request(lease,"logical_close",false);}
    CompletableFuture<Boolean> state(Lease lease){return request(lease,"logical_state",null);}
    private CompletableFuture<Boolean> request(Lease lease,String operation,Boolean expected){
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        try{
            JSONObject payload=new JSONObject().put("channel_id",lease.channel());
            if(operation.equals("logical_open"))payload.put("operations",new JSONArray().put("clipboard_write"));
            String request="logical:"+operation+":"+serial.incrementAndGet();
            client.request(lease.peer(),lease.session(),operation,request,payload,response->{
                JSONObject receipt=response.optJSONObject("result"),data=receipt==null?null:receipt.optJSONObject("data");
                if(!"ok".equals(response.optString("status"))||!lease.session().equals(response.optString("session_id"))||!request.equals(response.optString("request_id"))
                        ||receipt==null||!operation.equals(receipt.optString("operation"))||data==null||!lease.channel().equals(data.optString("channel_id"))){
                    result.completeExceptionally(new IllegalStateException(response.optString("code","invalid_logical_result")));return;
                }
                boolean open="open".equals(data.optString("state"));
                if(!open&&!"closed".equals(data.optString("state"))){result.completeExceptionally(new IllegalStateException("invalid_logical_result"));return;}
                if(expected!=null&&open!=expected){result.completeExceptionally(new IllegalStateException("unexpected_logical_state"));return;}
                result.complete(open);
            });
        }catch(org.json.JSONException|RuntimeException failure){result.completeExceptionally(failure);}
        return result;
    }
}
