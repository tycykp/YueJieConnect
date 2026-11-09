package io.github.yuejie.connect;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bridge-owned one-use Linker -> Cast request association. A claim does not grant capture. */
final class NativeScreenProofs {
    record Caller(int uid,int pid) { Caller {if(uid<0||pid<=0)throw new IllegalArgumentException("invalid_caller");} }
    record Claim(String session,String projection,long stream,String nativeUid,String deviceType,Caller issuer,Caller receiver) {}
    private record Pending(String session,String projection,long stream,String nativeUid,String deviceType,Caller issuer,long deadline) {}
    private final SecureRandom random;
    private final LongSupplier clock;
    private final Map<String,Pending> pending=new HashMap<>();
    private final Map<Long,Claim> claims=new HashMap<>();
    private final Map<Long,Long> deadlines=new HashMap<>();
    private final Set<Long> used=new HashSet<>(),approved=new HashSet<>();
    private String session="",projection="";
    private boolean permitted;
    NativeScreenProofs(){this(new SecureRandom(),android.os.SystemClock::elapsedRealtime);}
    NativeScreenProofs(SecureRandom random,LongSupplier clock){this.random=random;this.clock=clock;}
    synchronized void scope(String nextSession,String nextProjection,boolean granted){
        String s=nextSession==null?"":nextSession,p=nextProjection==null?"":nextProjection;
        if(!session.equals(s)||!projection.equals(p)){
            pending.clear();claims.clear();deadlines.clear();used.clear();approved.clear();session=s;projection=p;
        }
        if(!granted){pending.clear();claims.clear();deadlines.clear();approved.clear();}
        permitted=granted&&!s.isEmpty()&&!p.isEmpty();
    }
    synchronized String issue(Caller issuer,String expectedSession,String expectedProjection,String nativeUid,String type,long stream){
        expire();require(expectedSession,expectedProjection);
        if(nativeUid==null||nativeUid.isEmpty()||nativeUid.length()>256||!"SYNERGY_PAD".equals(type)||stream<=0)
            throw new IllegalArgumentException("invalid_screen_request");
        if(used.contains(stream))throw new IllegalStateException("screen_scope_reused");
        if(used.size()>=64||pending.size()+claims.size()>=8)throw new IllegalStateException("screen_request_capacity");
        byte[] bytes=new byte[32];String token;
        do{random.nextBytes(bytes);StringBuilder hex=new StringBuilder(64);for(byte value:bytes)hex.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));token=hex.toString();}while(pending.containsKey(token));
        java.util.Arrays.fill(bytes,(byte)0);
        used.add(stream);pending.put(token,new Pending(session,projection,stream,nativeUid,type,issuer,clock.getAsLong()+5000));
        return token;
    }
    synchronized Claim claim(Caller receiver,Caller originalBinderCaller,String token,String nativeUid,String type){
        expire();Pending request=pending.get(token);
        if(request==null||!permitted||!request.session.equals(session)||!request.projection.equals(projection)
                ||!request.issuer.equals(originalBinderCaller)||!request.nativeUid.equals(nativeUid)||!request.deviceType.equals(type))throw new IllegalStateException("screen_request_unproved");
        pending.remove(token);
        Claim claim=new Claim(session,projection,request.stream,nativeUid,type,request.issuer,receiver);
        claims.put(claim.stream,claim);deadlines.put(claim.stream,clock.getAsLong()+120000);return claim;
    }
    synchronized void nativeAllowed(Caller receiver,String expectedSession,long stream){
        expire();
        Claim claim=claims.get(stream);
        if(!permitted||claim==null||!Objects.equals(session,expectedSession)||!claim.receiver.equals(receiver))
            throw new IllegalStateException("screen_request_unproved");
        if(approved.contains(stream))throw new IllegalStateException("screen_scope_reused");
        approved.add(stream);deadlines.put(stream,clock.getAsLong()+4000);
    }
    synchronized boolean approved(Caller receiver,String expectedSession,long stream){
        expire();
        Claim claim=claims.get(stream);
        return permitted&&claim!=null&&claim.receiver.equals(receiver)&&session.equals(expectedSession)&&approved.contains(stream);
    }
    synchronized void renew(Caller receiver,String expectedSession,long stream){
        if(!approved(receiver,expectedSession,stream))throw new IllegalStateException("screen_request_unproved");
        deadlines.put(stream,clock.getAsLong()+4000);
    }
    synchronized long activeForIssuer(Caller issuer,String expectedSession){
        expire(); if(!permitted||!session.equals(expectedSession))return 0;
        for(Claim claim:claims.values())if(claim.issuer.equals(issuer)&&approved.contains(claim.stream))return claim.stream;
        return 0;
    }
    synchronized void revoke(Caller caller,String expectedSession,long stream){
        if(!session.equals(expectedSession))return;
        Claim claim=claims.get(stream);
        if(claim!=null&&(claim.issuer.equals(caller)||claim.receiver.equals(caller))){claims.remove(stream);deadlines.remove(stream);approved.remove(stream);}
        pending.values().removeIf(p->p.stream==stream&&p.issuer.equals(caller));
    }
    private void expire(){long now=clock.getAsLong();pending.values().removeIf(p->now>=p.deadline);
        deadlines.entrySet().removeIf(e->{if(now<e.getValue())return false;claims.remove(e.getKey());approved.remove(e.getKey());return true;});
    }
    private void require(String s,String p){if(!permitted||!session.equals(s)||!projection.equals(p))throw new IllegalStateException("screen_request_unproved");}
}
