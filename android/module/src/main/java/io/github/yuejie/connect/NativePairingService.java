package io.github.yuejie.connect;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Manual first pairing plus identity-bound reconnection using persisted bilateral consent. */
final class NativePairingService implements AutoCloseable {
    interface Host { JSONObject dispatch(JSONObject command)throws JSONException; void prepare()throws JSONException; JSONObject restore(JSONObject command)throws JSONException; }
    private static final String TYPE="_yjconnect._tcp.";
    private final NsdManager nsd;
    private final Host host;
    private final android.content.SharedPreferences preferences;
    private final PairedPeers paired;
    private boolean paused,automaticSelection;
    private long nextRetry,nextScan;
    private String rememberedSession="";
    private final Map<String,Peer> peers=new LinkedHashMap<>();
    private final Map<String,Peer> knownPeers=new LinkedHashMap<>();
    private record Peer(String key,JSONObject real,String address,long found){}
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private long generation,deadline;
    private String ownName="",error="";
    private String selectedKey="",confirmedSession="";
    private int source;
    private String resolution="none";
    NativePairingService(Context context,Host host){
        this.nsd=context.getSystemService(NsdManager.class);this.host=host;
        preferences=context.getSharedPreferences("paired-peers",Context.MODE_PRIVATE);
        paired=new PairedPeers(preferences.getStringSet("peers",java.util.Set.of()));
        paused=preferences.getBoolean("paused",false);
    }
    synchronized void resume(){paused=false;nextRetry=0;preferences.edit().putBoolean("paused",false).apply();}
    synchronized void pause(){paused=true;selectedKey="";automaticSelection=false;stopDiscovery();preferences.edit().putBoolean("paused",true).apply();}
    synchronized boolean reconnecting(){return !paused&&!paired.isEmpty();}
    synchronized JSONObject command(JSONObject request)throws JSONException{
        String action=request.optString("action");
        JSONObject state=host.dispatch(new JSONObject().put("op","bridge_status")).getJSONObject("bridge");
        source="hyper_os4".equals(state.getJSONObject("local").optString("platform"))?1:2;
        String phase=state.optString("phase");
        switch(action){
            case "start" -> {
                resume();
                if(!NativePairingPolicy.idle(phase))return snapshot(state);
                if(discovery==null){
                    host.prepare();
                    int port=state.optInt("listening_port");
                    if(!"listening".equals(phase)||port==0){
                        JSONObject started=host.dispatch(new JSONObject().put("op","bridge_listen").put("port",0));
                        if(!"ok".equals(started.optString("status")))return started;
                        port=started.getInt("port");
                    }
                    start(state.getJSONObject("local"),port);
                    state=host.dispatch(new JSONObject().put("op","bridge_status")).getJSONObject("bridge");
                }
            }
            case "select" -> {
                resume();automaticSelection=false;
                String id=request.optString("peer");
                JSONObject remote=remote(state);
                if("awaiting_confirmation".equals(phase)&&remote!=null&&id.equals(NativePairingPolicy.alias(source,key(remote)))){
                    return host.dispatch(new JSONObject().put("op","bridge_confirm").put("session",state.getString("session")).put("operations",new JSONArray(NativePairingPolicy.DEFAULT_GRANTS)));
                }
                if(!NativePairingPolicy.idle(phase))return failure("pairing_busy");
                Peer peer=peers.values().stream().filter(p->id.equals(NativePairingPolicy.alias(source,p.key()))).findFirst().orElse(null);
                if(peer==null||SystemClock.elapsedRealtime()>=deadline||SystemClock.elapsedRealtime()-peer.found()>120_000)return failure("peer_offline");
                host.prepare();
                JSONObject result=host.dispatch(new JSONObject().put("op","bridge_connect").put("address",peer.address()));
                if(!"ok".equals(result.optString("status")))return result;
                selectedKey=peer.key();confirmedSession="";
                // Retain the advertisement while awaiting consent so the other side can
                // resolve this public identity even when its NSD callback arrives later.
                state=host.dispatch(new JSONObject().put("op","bridge_status")).getJSONObject("bridge");
            }
            case "confirm" -> {
                String session=request.optString("session");
                if(!NativePairingPolicy.session(session)||!session.equals(state.optString("session"))
                        ||!"awaiting_confirmation".equals(phase)||!request.optString("comparison_code").equals(state.optString("comparison_code")))return failure("stale_confirmation");
                JSONArray values=request.optJSONArray("operations");java.util.Set<String> grants=new java.util.HashSet<>();
                if(values!=null)for(int i=0;i<values.length();i++)grants.add(values.getString(i));
                if(values==null||grants.size()!=values.length()||!NativePairingPolicy.grants(grants))return failure("invalid_grants");
                return host.dispatch(new JSONObject().put("op","bridge_confirm").put("session",session).put("operations",values));
            }
            case "disconnect" -> {
                String session=request.optString("session");
                if(!NativePairingPolicy.session(session))return failure("stale_session");
                JSONObject result=host.dispatch(new JSONObject().put("op","bridge_abort").put("session",session));
                if("ok".equals(result.optString("status")))pause();
                return result;
            }
            case "hide" -> {stopDiscovery();}
            case "status" -> { }
            default -> {return failure("invalid_request");}
        }
        if(SystemClock.elapsedRealtime()>=deadline||Settle.finished(state.optString("phase")))stopDiscovery();
        return snapshot(state);
    }
    private static final class Settle {static boolean finished(String phase){return "active".equals(phase)||"stopped".equals(phase)||"disconnected".equals(phase);}}
    private JSONObject snapshot(JSONObject state)throws JSONException{
        JSONObject remote=remote(state);
        String phase=state.optString("phase"),session=state.optString("session");
        if("awaiting_confirmation".equals(phase)&&!selectedKey.isEmpty()&&remote!=null&&!session.equals(confirmedSession)){
            if(!selectedKey.equals(key(remote))){host.dispatch(new JSONObject().put("op","bridge_abort").put("session",session));selectedKey="";return failure("peer_identity_changed");}
            JSONObject confirm=new JSONObject().put("op","bridge_confirm").put("session",session)
                    .put("operations",new JSONArray(automaticSelection?paired.grants(selectedKey):NativePairingPolicy.DEFAULT_GRANTS));
            JSONObject result=automaticSelection?host.restore(confirm):host.dispatch(confirm);
            if("ok".equals(result.optString("status")))confirmedSession=session;else return result;
        }
        if("active".equals(phase)||"stopped".equals(phase)||"disconnected".equals(phase))selectedKey="";
        JSONArray found=new JSONArray();
        if("awaiting_confirmation".equals(phase)&&remote!=null){found.put(candidate(key(remote),remote,session));}
        else if("listening".equals(phase))for(Peer peer:peers.values())found.put(candidate(peer.key(),peer.real(),""));
        // Pairing metadata only: never expose sockets or operation payloads to the page.
        JSONObject view=new JSONObject().put("phase",state.optString("phase")).put("session",state.optString("session"))
                .put("comparison_code",state.optString("comparison_code")).put("local_confirmed",state.optBoolean("local_confirmed"))
                .put("remote_confirmed",state.optBoolean("remote_confirmed"));
        if(remote!=null)view.put("name",remote.optString("name")).put("real",remote).put("id",NativePairingPolicy.alias(source,key(remote)));
        return new JSONObject().put("status","ok").put("pairing",view).put("peers",found)
                .put("searching",discovery!=null).put("error",error.isEmpty()?state.optString("last_error",""):error);
    }
    private void start(JSONObject local,int port)throws JSONException{
        stopDiscovery();error="";deadline=SystemClock.elapsedRealtime()+120_000;nextScan=SystemClock.elapsedRealtime()+60_000;long ticket=generation;
        knownPeers.clear();
        String platform=local.getString("platform");source="hyper_os4".equals(platform)?1:2;selectedKey="";
        ownName="yj-"+UUID.randomUUID().toString().replace("-","");
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(ownName);info.setServiceType(TYPE);info.setPort(port);
        info.setAttribute("platform",platform);info.setAttribute("name",local.getString("name"));
        info.setAttribute("key",key(local));info.setAttribute("kind",local.getString("kind"));info.setAttribute("model",local.getString("model"));
        registration=new NsdManager.RegistrationListener(){
            public void onServiceRegistered(NsdServiceInfo value){synchronized(NativePairingService.this){if(ticket==generation)ownName=value.getServiceName();}}
            public void onRegistrationFailed(NsdServiceInfo value,int code){failed(ticket,"discovery_register_failed");}
            public void onServiceUnregistered(NsdServiceInfo value){}
            public void onUnregistrationFailed(NsdServiceInfo value,int code){}
        };
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int code){failed(ticket,"discovery_start_failed");}
            public void onStopDiscoveryFailed(String type,int code){}
            public void onServiceLost(NsdServiceInfo value){synchronized(NativePairingService.this){if(ticket==generation)peers.remove(value.getServiceName());}}
            public void onServiceFound(NsdServiceInfo value){synchronized(NativePairingService.this){
                if(ticket!=generation||value.getServiceName().equals(ownName)||!value.getServiceName().startsWith("yj-")||peers.size()>=16)return;
                nsd.resolveService(value,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo item,int code){synchronized(NativePairingService.this){if(ticket==generation)resolution="resolve_failed_"+code;}}
                    public void onServiceResolved(NsdServiceInfo item){synchronized(NativePairingService.this){
                        java.net.InetAddress address=NativePairingPolicy.lanAddress(item.getHostAddresses(),item.getPort());
                        if(ticket!=generation)return;
                        if(address==null){resolution="no_lan_ipv4";return;}
                        byte[] brand=item.getAttributes().get("platform"),name=item.getAttributes().get("name"),identity=item.getAttributes().get("key"),kind=item.getAttributes().get("kind"),model=item.getAttributes().get("model");
                        if(brand==null||name==null||name.length>128||identity==null||kind==null||model==null||model.length>128){resolution="invalid_metadata";return;}
                        String other=new String(brand,StandardCharsets.UTF_8);
                        if(other.equals(platform)||!java.util.Set.of("hyper_os4","color_os17").contains(other))return;
                        String key=new String(identity,StandardCharsets.UTF_8),role=new String(kind,StandardCharsets.UTF_8);
                        if(!NativePairingPolicy.session(key)||!java.util.Set.of("phone","tablet").contains(role))return;
                        try{JSONObject real=new JSONObject().put("name",new String(name,StandardCharsets.UTF_8)).put("kind",role).put("model",new String(model,StandardCharsets.UTF_8)).put("platform",other).put("os_build","");
                            Peer peer=new Peer(key,real,address.getHostAddress()+":"+item.getPort(),SystemClock.elapsedRealtime());
                            peers.put(item.getServiceName(),peer);if(knownPeers.size()<32)knownPeers.put(key,peer);
                            resolution="resolved_peer";
                        }catch(JSONException ignored){}
                    }}
                });
            }}
        };
        try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}
        catch(RuntimeException unavailable){stopDiscovery();error="discovery_unavailable";}
    }
    private synchronized void failed(long ticket,String reason){if(ticket==generation){stopDiscovery();error=reason;}}
    // Called on a service worker under its lifecycle lock, never on the main looper.
    synchronized void tick(){
        long now=SystemClock.elapsedRealtime();
        try{
            JSONObject state=host.dispatch(new JSONObject().put("op","bridge_status")).getJSONObject("bridge");
            String phase=state.optString("phase"),session=state.optString("session");
            if("active".equals(phase)){
                if(!session.equals(rememberedSession)&&state.optBoolean("local_confirmed")&&state.optBoolean("remote_confirmed")){
                    JSONObject remote=state.getJSONObject("remote");java.util.List<String> grants=new java.util.ArrayList<>();
                    JSONArray values=state.getJSONArray("granted_operations");for(int i=0;i<values.length();i++)grants.add(values.getString(i));
                    paired.remember(key(remote),grants);
                    if(preferences.edit().putStringSet("peers",paired.encode()).commit())rememberedSession=session;
                    else error="pairing_store_unavailable";
                }
                selectedKey="";stopDiscovery();return;
            }
            if(discovery!=null&&now>=deadline)stopDiscovery();
            if("awaiting_confirmation".equals(phase)){
                JSONArray identity=state.optJSONArray("pending_peer_key");
                String authenticated=identity==null?"":key(new JSONObject().put("peer_key",identity));
                if(!selectedKey.isEmpty()&&!selectedKey.equals(authenticated)){
                    host.dispatch(new JSONObject().put("op","bridge_abort").put("session",session));selectedKey="";nextRetry=now+15_000;return;
                }
                // Discovery metadata never authorizes a peer: only Noise's authenticated key does.
                if(!paused&&!state.optBoolean("local_confirmed")&&!session.equals(confirmedSession)&&!paired.grants(authenticated).isEmpty()){
                    JSONObject result=host.restore(new JSONObject().put("op","bridge_confirm").put("session",session)
                            .put("operations",new JSONArray(paired.grants(authenticated))));
                    if("ok".equals(result.optString("status")))confirmedSession=session;
                }else if(!selectedKey.isEmpty()&&!automaticSelection)snapshot(state);
                return;
            }
            if(!reconnecting()||!NativePairingPolicy.idle(phase)||now<nextRetry)return;
            selectedKey="";automaticSelection=false;
            if(!"listening".equals(phase)||discovery==null||now>=nextScan){
                nextRetry=now+5_000;host.prepare();int port=state.optInt("listening_port");
                if(!"listening".equals(phase)||port==0){
                    JSONObject result=host.dispatch(new JSONObject().put("op","bridge_listen").put("port",0));
                    if(!"ok".equals(result.optString("status")))return;port=result.getInt("port");
                }
                start(state.getJSONObject("local"),port);return;
            }
            String local=key(state.getJSONObject("local"));
            for(Peer peer:peers.values())if(!paired.grants(peer.key()).isEmpty()&&now-peer.found()<60_000&&PairedPeers.initiates(local,peer.key())){
                nextRetry=now+15_000;
                JSONObject result=host.dispatch(new JSONObject().put("op","bridge_connect").put("address",peer.address()));
                if("ok".equals(result.optString("status"))){selectedKey=peer.key();automaticSelection=true;confirmedSession="";}
                break;
            }
        }catch(JSONException|RuntimeException unavailable){nextRetry=now+15_000;error="reconnect_unavailable";}
    }
    synchronized JSONObject inspect()throws JSONException{return new JSONObject().put("searching",discovery!=null).put("peer_count",peers.size()).put("known_count",knownPeers.size()).put("paired_count",paired.size()).put("auto_reconnect",reconnecting()).put("resolution",resolution).put("error",error);}
    private void stopDiscovery(){
        generation++;peers.clear();
        if(discovery!=null){try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}discovery=null;}
        if(registration!=null){try{nsd.unregisterService(registration);}catch(RuntimeException ignored){}registration=null;}
    }
    private static JSONObject failure(String code)throws JSONException{return new JSONObject().put("status","error").put("code",code);}
    private JSONObject candidate(String key,JSONObject real,String session)throws JSONException{
        return new JSONObject().put("id",NativePairingPolicy.alias(source,key)).put("real",real).put("session_id",session)
                .put("target_sdk",source==1?"hyper_os4":"color_os17").put("sdk_device_type",source==1?1:("tablet".equals(real.optString("kind"))?10:8))
                .put("operations",new JSONArray()).put("unpaired",true);
    }
    private JSONObject remote(JSONObject state)throws JSONException{
        JSONObject remote=state.optJSONObject("remote");if(remote!=null)return remote;
        JSONArray bytes=state.optJSONArray("pending_peer_key");if(bytes==null)return null;
        String identity=key(new JSONObject().put("peer_key",bytes));Peer known=knownPeers.get(identity);
        if(known==null||SystemClock.elapsedRealtime()-known.found()>120_000)return null;
        return new JSONObject(known.real().toString()).put("peer_key",bytes);
    }
    private static String key(JSONObject profile)throws JSONException{
        JSONArray bytes=profile.getJSONArray("peer_key");if(bytes.length()!=32)throw new JSONException("invalid key");
        StringBuilder text=new StringBuilder();for(int i=0;i<32;i++)text.append(String.format(java.util.Locale.ROOT,"%02x",bytes.getInt(i)));return text.toString();
    }
    @Override public synchronized void close(){stopDiscovery();}
}
