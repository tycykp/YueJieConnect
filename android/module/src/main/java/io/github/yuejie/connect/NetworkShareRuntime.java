package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** OEM endpoint registered in the authenticated mailbox, with a Bluetooth Noise session as control. */
final class NetworkShareRuntime implements BridgeClient.VendorExecutor, NetworkShareRpc.Link {
    private final BridgeClient client;
    private final NetworkShareRpc rpc;
    private final NetworkShareBinding binding;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile NetworkShareController.Target selected;
    private volatile Object lease;
    private volatile NetworkSharePolicy.Consent consent;
    private final boolean provider;
    private volatile boolean surfaceAvailable;
    private final java.util.concurrent.ConcurrentHashMap<String,String> mutations=new java.util.concurrent.ConcurrentHashMap<>();

    NetworkShareRuntime(XposedModule module, BridgeClient client, ClassLoader loader) throws Exception {
        this.client=client;
        provider=CallerPolicy.OPLUS.equals(client.context().getPackageName());
        rpc=new NetworkShareRpc(this);
        binding=new NetworkShareBinding(module,client.context(),loader,rpc,()->selected,()->consent,
            this::publish);
        client.registerExecutor("network_share",this);
        client.context().registerReceiver(new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context context,android.content.Intent intent){
                var target=selected;
                if(target==null||!target.session().equals(intent.getStringExtra("session")))return;
                if("stop".equals(intent.getStringExtra("action"))){binding.controller().stop();return;}
                if(!"start".equals(intent.getStringExtra("action"))||consent==null)return;
                var grant=consent;
                (provider?binding.controller().startProviding(target,grant.upstream(),grant.meteredAllowed())
                    :binding.controller().start(target,grant.upstream(),grant.meteredAllowed())).whenComplete((status,error)->{
                    if(error!=null)android.util.Log.w("YueJieNetwork","start_failed "+error.getMessage());
                });
            }
        },new android.content.IntentFilter("io.github.yuejie.connect.NETWORK_CONTROL"),
            "io.github.yuejie.connect.permission.NETWORK_CONTROL",main,android.content.Context.RECEIVER_EXPORTED);

        if(!provider)client.context().registerReceiver(new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context c,android.content.Intent intent){
                var target=selected;
                if(target==null||!target.session().equals(intent.getStringExtra("session")))return;
                String action=intent.getStringExtra("action");
                if("stop".equals(action)){binding.controller().stop();return;}
                if(!"start".equals(action)||consent==null||!surfaceAvailable)return;
                var grant=consent;
                binding.controller().start(target,grant.upstream(),grant.meteredAllowed()).whenComplete((status,error)->{
                    if(error!=null)android.util.Log.w("YueJieNetwork","native_start_failed "+error.getMessage());
                });
            }
        },new android.content.IntentFilter(NetworkShareSurface.CONTROL),NetworkShareSurface.SYSTEM_PERMISSION,main,
            android.content.Context.RECEIVER_EXPORTED);
        client.addProjectionListener(this::update);
        main.post(new Runnable(){public void run(){refreshConsent(); main.postDelayed(this,2000);}});
    }
    private void publish(NetworkShareController.Status status){
        android.util.Log.i("YueJieNetwork","phase="+status.phase()+" error="+status.error());
        if(status.target()==null)return;
        try{client.browserRelay(new JSONObject().put("op","network_status_publish")
            .put("session",status.target().session()).put("phase",status.phase().name())
            .put("upstream",status.upstream()==null?"":status.upstream().name())
            .put("error",status.error()==null?"":status.error()).put("available",surfaceAvailable));}catch(org.json.JSONException ignored){}
    }
    private void update(JSONObject projection) {
        NetworkShareController.Target next=null;
        if(projection!=null && "bluetooth".equals(projection.optString("control_transport"))) {
            var ops=projection.optJSONArray("operations");
            if(ops!=null)for(int i=0;i<ops.length();i++)if("network_share".equals(ops.optString(i)))
                next=new NetworkShareController.Target(projection.optString("id"),projection.optString("session_id"));
        }
        if(!Objects.equals(selected,next)){
            selected=next; lease=next==null?null:new Object(); consent=null;
            binding.sessionChanged();binding.controlChanged();refreshConsent();
        }
    }
    private void refreshConsent() {
        var target=selected; Object expected=lease;
        if(target==null||expected==null)return;
        var remote=provider?binding.endpoint().availability():rpc.availability(target);
        remote.thenCombine(binding.endpoint().availability(),(available,local)->{
            if(expected!=lease)return false;
            if(available.upstream()!=null)consent=new NetworkSharePolicy.Consent(available.upstream(),true);
            var status=binding.controller().status();
            boolean running=target.equals(status.target())&&java.util.Set.of(NetworkShareSession.Phase.REQUESTED,
                NetworkShareSession.Phase.STARTING,NetworkShareSession.Phase.READY,NetworkShareSession.Phase.ACTIVE,
                NetworkShareSession.Phase.STOPPING).contains(status.phase());
            surfaceAvailable=running||available.provider()&&(provider||local.consumer());
            publish(target.equals(status.target())?status:new NetworkShareController.Status(target,null,
                NetworkShareSession.Phase.STOPPED,available.upstream(),null));
            return true;
        }).exceptionally(error->{
            if(expected==lease){surfaceAvailable=false;publish(new NetworkShareController.Status(target,null,
                NetworkShareSession.Phase.FAILED,null,"network_unavailable"));}return false;
        });
    }
    @Override public Object stableLease(NetworkShareController.Target target) {
        return target!=null&&target.equals(selected)?lease:null;
    }
    @Override public CompletableFuture<NetworkShareRpc.Reply> request(NetworkShareController.Target target,
            String request,Object expected,Map<String,Object> payload) {
        var result=new CompletableFuture<NetworkShareRpc.Reply>();
        if(!target.equals(selected)||(expected!=null&&expected!=lease))return CompletableFuture.failedFuture(new IllegalStateException("stale_session"));
        client.request(target.id(),target.session(),"network_share",request,new JSONObject(payload),response->{
            try {
                JSONObject receipt=response.getJSONObject("result");
                if(!"ok".equals(response.optString("status"))||!"network_share".equals(receipt.optString("operation"))
                        ||!target.equals(selected)||(expected!=null&&expected!=lease))throw new IllegalStateException("network_request_failed");
                result.complete(new NetworkShareRpc.Reply(target,request,map(receipt.getJSONObject("data"))));
            }catch(Exception error){result.completeExceptionally(error);}
        });
        return result;
    }
    @Override public void execute(JSONObject task,BridgeClient.TaskCompletion completion) {
        try {
            var target=selected;
            if(target==null || lease==null || !target.session().equals(task.optString("session_id"))
                    || !target.id().equals(task.optString("projection_id")))throw new IllegalStateException("stale_session");
            long deadline=task.optLong("deadline_elapsed_ms");
            if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("request_timeout");
            var command=NetworkShareWire.decode(target.session(),map(task.getJSONObject("payload")));
            String request=task.getString("request_id");
            if(!java.util.Set.of("availability","stop").contains(command.action()))mutations.put(request,target.session());
            NetworkShareWire.execute(binding.endpoint(),target.session(),command).whenComplete((value,error)->{
                mutations.remove(request,target.session());
                if(error!=null){android.util.Log.w("YueJieNetwork","endpoint_failed action="+command.action()+" reason="+error.getClass().getSimpleName()+":"+error.getMessage());completion.fail("execution_failed");}
                else if(!target.equals(selected)||SystemClock.elapsedRealtime()>=deadline){binding.endpoint().invalidate();completion.fail("stale_session");}
                else completion.success(new JSONObject(value));
            });
        }catch(Exception error){completion.fail("invalid_payload");}
    }
    @Override public void cancel(String session,String request){if(mutations.remove(request,session))binding.endpoint().invalidate();}
    @Override public void sessionChanged(){update(null);}
    private static Map<String,Object> map(JSONObject json)throws org.json.JSONException {
        var result=new LinkedHashMap<String,Object>();var keys=json.keys();
        while(keys.hasNext()){String key=keys.next();result.put(key,json.get(key));}return result;
    }
}
