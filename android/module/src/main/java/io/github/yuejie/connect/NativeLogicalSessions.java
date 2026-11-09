package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Per authenticated peer: real leases over implemented vendor execution channels.
 * Closing an unknown ID is retained so a delayed open cannot resurrect it. */
final class NativeLogicalSessions {
    static final Set<String> BUSINESS=Set.of("clipboard_write","clipboard_rich_write","clipboard_uri_write",
            "file_prepare","file_offer","file_chunk","file_commit","file_cancel","app_relay","media_control");
    static boolean validOperations(Set<String> operations){return operations!=null&&!operations.isEmpty()&&BUSINESS.containsAll(operations);}
    record State(String channelId,boolean open,Set<String> operations) {}
    private record Channel(Set<String> operations,long renewed) {}
    private final Map<String,Channel> channels=new LinkedHashMap<>();
    private final LongSupplier clock;
    private String session,recordSession;
    NativeLogicalSessions(){this(()->System.nanoTime()/1_000_000L);}
    NativeLogicalSessions(LongSupplier clock){this.clock=java.util.Objects.requireNonNull(clock);}
    synchronized void session(String current){
        if(current==null){
            // Losing the local Binder/projection revokes live leases, but a reconnect to the
            // same authenticated session must not erase close/expiry replay tombstones.
            channels.replaceAll((id,value)->new Channel(Set.of(),0));
        }else if(!java.util.Objects.equals(recordSession,current)){
            channels.clear();recordSession=current;
        }
        session=current;
    }
    synchronized State open(String expected,String channel,Set<String> requested,Set<String> available){
        check(expected,channel);
        expire();
        if(!NativeLogicalSessions.validOperations(requested))throw new IllegalArgumentException("unsupported_operation");
        if(!available.containsAll(requested))throw new IllegalStateException("executor_unavailable");
        Channel old=channels.get(channel);Set<String> previous=old==null?null:old.operations();
        if(previous!=null){
            if(previous.isEmpty())throw new IllegalStateException("channel_closed");
            if(!previous.equals(requested))throw new IllegalStateException("channel_conflict");
            return new State(channel,true,previous);
        }
        if(channels.size()>=256||channels.values().stream().filter(s->!s.operations().isEmpty()).count()>=16)throw new IllegalStateException("busy");
        Set<String> owned=Set.copyOf(requested);channels.put(channel,new Channel(owned,clock.getAsLong()));return new State(channel,true,owned);
    }
    synchronized State close(String expected,String channel){
        check(expected,channel);
        if(!channels.containsKey(channel)&&channels.size()>=256)throw new IllegalStateException("busy");
        channels.put(channel,new Channel(Set.of(),0));return new State(channel,false,Set.of());
    }
    synchronized State state(String expected,String channel,Set<String> available){
        check(expected,channel);expire();Channel current=channels.get(channel);Set<String> operations=current==null?Set.of():current.operations();
        if(!available.containsAll(operations))operations=Set.of();
        if(current!=null)channels.put(channel,new Channel(operations,clock.getAsLong()));
        return new State(channel,!operations.isEmpty(),operations);
    }
    // Only an explicit authenticated state check renews a live lease. A lost source process or
    // lost close ACK cannot consume an active slot indefinitely; expired IDs remain tombstones.
    private void expire(){long now=clock.getAsLong();channels.replaceAll((id,value)->!value.operations().isEmpty()&&now-value.renewed()>=8_000?new Channel(Set.of(),0):value);}
    synchronized boolean hasRecords(){return !channels.isEmpty();}
    synchronized boolean openFor(String expected,String operation){expire();return expected!=null&&expected.equals(session)&&channels.values().stream().anyMatch(c->c.operations().contains(operation));}
    private void check(String expected,String channel){
        if(session==null||!session.equals(expected)||!session.matches("[0-9a-f]{64}"))throw new IllegalStateException("stale_session");
        if(channel==null||!channel.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("invalid_payload");
    }
}
