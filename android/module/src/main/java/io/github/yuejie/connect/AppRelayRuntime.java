package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Owns the selected gateway DATA slot in the real Mirror/Linker relay process. */
final class AppRelayRuntime implements AutoCloseable {
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final int brand;
    private final BooleanSupplier allowed;
    private final FileTransferBridge files;
    private final NativeLogicalClient logical;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AppRelayNativeBinding binding;
    private volatile AppRelayPeer peer;
    private volatile NativeLogicalClient.Lease lease;
    private FileTransferBridge.Registration documents;
    private CompletableFuture<Void> retiring=CompletableFuture.completedFuture(null);
    private boolean opening,checking;
    private boolean claimingBrowser;
    private volatile boolean closed,acknowledged;
    private long retry,nextState,nextBrowserClaim;
    private String failedSession="";
    private final Runnable tick=new Runnable(){public void run(){if(!closed){
        try{refresh();}catch(RuntimeException failure){failCurrent();module.log(android.util.Log.WARN,"YueJie","relay_runtime_refresh_failed");}
        finally{if(!closed)main.postDelayed(this,250);}
    }}};
    AppRelayRuntime(XposedModule module,BridgeClient client,ClassLoader loader,int brand,
                    BooleanSupplier allowed,FileTransferBridge files){
        this.module=module;this.client=client;this.loader=loader;this.brand=brand;this.allowed=allowed;this.files=files;
        logical=new NativeLogicalClient(client);main.post(tick);
        ScreenCastLog.i("YueJieRelay","runtime_installed brand="+brand);
    }
    private boolean current(){JSONObject p=client.projection();AppRelayPeer selected=peer;return !closed&&allowed.getAsBoolean()&&selected!=null&&selected.matches(p)&&client.hasLocalGrant("app_relay");}
    private boolean channel(){return current()&&(acknowledged&&logical.current(lease)||client.hasIncomingLogicalChannel("app_relay"));}
    private void refresh(){
        JSONObject p=client.projection();
        if(binding!=null&&!current())retire();
        if(binding==null){
            if(closed||!allowed.getAsBoolean()||p==null||failedSession.equals(p.optString("session_id"))||!client.hasLocalGrant("app_relay")||!retiring.isDone()||SystemClock.elapsedRealtime()<retry)return;
            retry=SystemClock.elapsedRealtime()+5000;
            try{prepare(p);}catch(Exception failure){retire();module.log(android.util.Log.WARN,"YueJie","relay_runtime_unavailable "+failure.getClass().getSimpleName());return;}
        }
        binding.nativeStateChanged();
        claimBrowserOffer();
        if(!opening&&lease==null&&SystemClock.elapsedRealtime()>=retry){
            try{
                NativeLogicalClient.Lease attempt=logical.allocate(peer.projectionId(),peer.sessionId(),Set.of("app_relay"));
                lease=attempt;opening=true;
                logical.open(attempt).whenComplete((open,error)->{
                    if(lease!=attempt){logical.close(attempt);return;}
                    opening=false;
                    if(error!=null||!Boolean.TRUE.equals(open)||!current()){logical.close(attempt);lease=null;retry=SystemClock.elapsedRealtime()+2000;}
                    else{acknowledged=true;nextState=0;notifyBinding();if(acknowledged)ScreenCastLog.i("YueJieRelay","data_channel_open brand="+brand);}
                });
            }catch(RuntimeException notReady){retry=SystemClock.elapsedRealtime()+1000;}
        }
        if(acknowledged&&!checking&&SystemClock.elapsedRealtime()>=nextState){
            checking=true;nextState=SystemClock.elapsedRealtime()+2000;NativeLogicalClient.Lease selected=lease;
            logical.state(selected).whenComplete((open,error)->{
                if(lease!=selected)return;
                checking=false;
                if(error!=null||!Boolean.TRUE.equals(open)){acknowledged=false;logical.close(selected);lease=null;retry=SystemClock.elapsedRealtime()+2000;}
                notifyBinding();
            });
        }
    }
    private void claimBrowserOffer() {
        AppRelayNativeBinding selectedBinding = binding;
        AppRelayPeer selectedPeer = peer;
        long now = SystemClock.elapsedRealtime();
        if (claimingBrowser || now < nextBrowserClaim || !selectedBinding.browserRelayReady()) return;
        nextBrowserClaim = now + 750;
        try {
            JSONObject command = new JSONObject().put("op", "browser_relay_claim").put("session", selectedPeer.sessionId());
            claimingBrowser = true;
            client.browserRelay(command, result -> {
                claimingBrowser = false;
                if (result == null || selectedBinding != binding || !selectedPeer.equals(peer)) return;
                String clicked = result.optString("clicked_package");
                if (!clicked.isEmpty()) selectedBinding.openSharedOffer(selectedPeer, clicked);
                JSONObject value = result.optJSONObject("offer");
                if (value == null) return;
                try {
                    BrowserRelayOffer offer = BrowserRelayOffer.decode(value);
                    selectedBinding.publishBrowserOffer(selectedPeer, offer).whenComplete((receipt, failure) -> {
                        try {
                            client.browserRelay(new JSONObject().put("op", "browser_relay_finish").put("session", offer.session())
                                    .put("id", offer.id()).put("delivered", failure == null), ignored -> {});
                            ScreenCastLog.i("YueJieRelay", failure == null ? "browser_catalog_delivered" : "browser_catalog_failed");
                        } catch (org.json.JSONException invalid) {
                            ScreenCastLog.w("YueJieRelay", "browser_catalog_result_failed");
                        }
                    });
                } catch (org.json.JSONException | IllegalArgumentException invalid) {
                    ScreenCastLog.w("YueJieRelay", "invalid_browser_offer");
                }
            });
        } catch (org.json.JSONException invalid) {
            ScreenCastLog.w("YueJieRelay", "browser_claim_failed");
        }
    }
    private void notifyBinding(){try{if(binding!=null)binding.nativeStateChanged();}catch(RuntimeException failure){failCurrent();}}
    private void failCurrent(){if(peer!=null)failedSession=peer.sessionId();retire();retry=SystemClock.elapsedRealtime()+5000;}
    private void prepare(JSONObject projection)throws Exception{
        peer=new AppRelayPeer(projection.getString("id"),projection.getString("id"),projection.getString("session_id"));
        AppRelayPeer owned=peer;
        AppRelayNativeBinding.Selection selected=new AppRelayNativeBinding.Selection(){
            public AppRelayPeer selected(){return current()?peer:null;}
            public String deviceName(){JSONObject p=client.projection(),real=p==null?null:p.optJSONObject("real");return real==null?"":real.optString("name");}
            public boolean dataChannelReady(){return channel();}
            public boolean canPrepare(){return current();}
            // Retain this exact slot through native directory withdrawal, even after session loss.
            public boolean ownsNativeSlot(AppRelayPeer value){return owned.equals(value)&&owned.equals(peer);}
        };
        XiaomiRelayCodec.Files outgoing=files==null?null:new XiaomiRelayFiles(client.context(),files.fileClient(),files::current,
            new XiaomiRelayFiles.KeyAllocator(){
                public FileTransferEngine.Key allocate(String id,String session)throws Exception{
                    FileTransferEngine.Key key=files.allocateKey().get(7,TimeUnit.SECONDS);
                    if(!id.equals(key.peer())||!session.equals(key.session())){files.release(key);throw new IllegalStateException("stale_session");}return key;
                }
                public void release(FileTransferEngine.Key key){files.release(key);}
            },(key,offer,token)->{
                if(!files.bindOutgoing(key,FileTransferNegotiation.Purpose.APP_RELAY,token))return CompletableFuture.failedFuture(new IllegalStateException("native_file_route_unbound"));
                return files.awaitReceiver(key,offer);
            });
        binding=new AppRelayNativeBinding(module,client,loader,brand,selected,
            files==null?null:(id,session)->new AppRelayDocuments(client.context(),id,session,files::current),outgoing,
            ()->files!=null&&files.ready()&&channel());
        if(files!=null)documents=files.registerReceiver(FileTransferNegotiation.Purpose.APP_RELAY,new FileTransferBridge.NativeReceiver(){
            public void prepared(FileTransferNegotiation.Prepared value,FileTransferEndpoint endpoint,FileTransferNegotiation negotiation){if(binding!=null&&current())binding.nativeDocumentPrepared(value,endpoint,negotiation);}
            public void revoked(FileTransferNegotiation.Prepared value){if(binding!=null)binding.nativeDocumentRevoked(value.key());}
            public void sessionChanged(){if(binding!=null)binding.nativeDocumentsRevoked();}
        });
        retry=0;
        ScreenCastLog.i("YueJieRelay","native_resources_prepared brand="+brand);
    }
    private void retire(){
        NativeLogicalClient.Lease old=lease;lease=null;opening=checking=acknowledged=false;if(old!=null)logical.close(old);
        if(documents!=null){documents.close();documents=null;}
        AppRelayNativeBinding oldBinding=binding;binding=null;
        if(oldBinding!=null){oldBinding.close();retiring=oldBinding.whenRetired();}
    }
    @Override public void close(){if(closed)return;closed=true;main.removeCallbacks(tick);retire();}
}
