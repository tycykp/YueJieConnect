package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic transport only; original native UI and audible playback require separate evidence. */
public final class OplusFinderSenderCheck {
    private static final String PEER = "yj:" + "a".repeat(64), OTHER = "yj:" + "b".repeat(64);
    private static int assertions;
    record Call(String id, String session, String request, JSONObject payload, Consumer<JSONObject> reply) {}
    static final class Wire implements OplusFinderSender.Transport {
        final List<Call> calls = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        @Override public void request(String id, String session, String operation, String request, JSONObject payload, Consumer<JSONObject> reply) {
            check("device_finder".equals(operation), "typed operation");
            calls.add(new Call(id,session,request,payload,reply));
        }
        @Override public void cancel(String session, String request) { cancelled.add(session + request); }
    }
    private static JSONObject selected(String session, String kind, boolean supported) throws Exception {
        return new JSONObject().put("id", PEER).put("session_id", session).put("real", new JSONObject().put("kind", kind))
                .put("operations", supported ? new JSONArray().put("device_finder") : new JSONArray());
    }
    private static JSONObject reply(Call call, boolean ringing, long left) throws Exception {
        return new JSONObject().put("status","ok").put("session_id",call.session()).put("request_id",call.request())
                .put("result",new JSONObject().put("operation","device_finder").put("data",new JSONObject()
                        .put("ringing",ringing).put("remaining_ms",left)));
    }
    private static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    private static OplusFinderSender.Outcome last(List<OplusFinderSender.Outcome> outcomes) { return outcomes.get(outcomes.size()-1); }

    public static void main(String[] args) throws Exception {
        Wire wire = new Wire();
        OplusFinderSender sender = new OplusFinderSender(wire);
        List<OplusFinderSender.Outcome> outcomes = new ArrayList<>();
        check(!sender.send("cloud-device", "PAD", "cloud", "RING", outcomes::add), "cloud unclaimed");
        check(outcomes.isEmpty() && wire.calls.isEmpty(), "cloud unchanged");
        sender.send(PEER,"PAD","offline","RING",outcomes::add);
        check("stale_session".equals(last(outcomes).code()),"no peer rejected");
        sender.update(selected("session1","tablet",true));
        sender.send(PEER,"EARPHONE","wrong-type","RING",outcomes::add);
        check("permission_denied".equals(last(outcomes).code()),"no earphone masquerade");
        sender.send(OTHER,"PAD","wrong-peer","RING",outcomes::add);
        check("stale_session".equals(last(outcomes).code()),"only selected peer");
        sender.send(PEER,"PAD","erase","ERASE",outcomes::add);
        check("invalid_action".equals(last(outcomes).code()) && wire.calls.isEmpty(),"destructive command not routed");
        sender.send(PEER,"PAD","","RING",outcomes::add);
        check("invalid_request_id".equals(last(outcomes).code()),"empty message id rejected");

        outcomes.clear();
        sender.send(PEER,"PAD","start1","RING",outcomes::add);
        check(outcomes.isEmpty() && wire.calls.size()==1,"native completion waits remote ack");
        Call first = wire.calls.get(0);
        check(first.payload().getLong("duration_ms")==10000 && first.payload().length()==2,"bounded payload no native identity/content copied");
        sender.send(PEER,"PAD","start1","RING",outcomes::add);
        check(wire.calls.size()==1,"inflight duplicate not resubmitted");
        first.reply().accept(reply(first,true,9999));
        check(outcomes.size()==2 && last(outcomes).success() && last(outcomes).ringing(),"both native waiters receive actual playback ack");
        first.reply().accept(reply(first,true,9999));
        check(outcomes.size()==2,"duplicate transport callback ignored");
        sender.send(PEER,"PAD","start1","RING",outcomes::add);
        check(wire.calls.size()==1 && last(outcomes).success(),"completed native retry cached");
        sender.send(PEER,"PAD","start1","STOP_RING",outcomes::add);
        check("request_conflict".equals(last(outcomes).code()),"same id different action rejected");
        sender.send(PEER,"PAD","stop1","STOP_RING",outcomes::add);
        Call stop = wire.calls.get(1);
        check(stop.payload().length()==1 && "stop".equals(stop.payload().getString("action")),"native stop maps exactly");
        stop.reply().accept(reply(stop,false,0));
        check(last(outcomes).success() && !last(outcomes).ringing(),"stop only after resource-release ack");
        sender.close();
        check(wire.calls.size()==2,"closed stopped sender does not restart");

        for (int invalid=0; invalid<9; invalid++) {
            Wire badWire=new Wire(); OplusFinderSender bad=new OplusFinderSender(badWire);
            bad.update(selected("current","phone",true)); outcomes.clear();
            bad.send(PEER,"PHONE","bad","RING",outcomes::add);
            Call c=badWire.calls.get(0); JSONObject response=reply(c,true,100);
            switch(invalid) {
                case 0 -> response.put("session_id","old");
                case 1 -> response.put("request_id","other");
                case 2 -> response.getJSONObject("result").put("operation","battery_status");
                case 3 -> response.getJSONObject("result").getJSONObject("data").put("ringing",false);
                case 4 -> response.getJSONObject("result").getJSONObject("data").put("ringing","true");
                case 5 -> response.getJSONObject("result").getJSONObject("data").put("remaining_ms",0);
                case 6 -> response.getJSONObject("result").getJSONObject("data").put("remaining_ms",30001);
                case 7 -> response.getJSONObject("result").getJSONObject("data").put("remaining_ms",1.5);
                case 8 -> response.put("status","error");
            }
            c.reply().accept(response);
            check(!last(outcomes).success(),"bad ack never successful case="+invalid);
            bad.close();
        }

        Wire staleWire=new Wire(); OplusFinderSender stale=new OplusFinderSender(staleWire);
        stale.update(selected("old","tablet",true)); outcomes.clear();
        stale.send(PEER,"PAD","same-native-id","RING",outcomes::add); Call old=staleWire.calls.get(0);
        stale.update(selected("new","tablet",true));
        check(outcomes.size()==1 && "stale_session".equals(last(outcomes).code()) && staleWire.cancelled.size()==1,"withdraw cancels and completes old native waiter");
        stale.send(PEER,"PAD","same-native-id","RING",outcomes::add); Call fresh=staleWire.calls.get(1);
        old.reply().accept(reply(old,true,100));
        check(outcomes.size()==1,"late old callback cannot complete new request");
        fresh.reply().accept(reply(fresh,true,100));
        check(outcomes.size()==2 && last(outcomes).success() && !old.request().equals(fresh.request()),"new session fresh request and ack");
        stale.close();
        check(staleWire.calls.size()==3 && "stop".equals(staleWire.calls.get(2).payload().getString("action")),"closing live sender sends real stop");

        Wire boundWire=new Wire(); OplusFinderSender bound=new OplusFinderSender(boundWire);
        bound.update(selected("bound","tablet",true)); outcomes.clear();
        for(int i=0;i<16;i++) bound.send(PEER,"PAD","pending"+i,"RING",outcomes::add);
        bound.send(PEER,"PAD","overflow","RING",outcomes::add);
        check(boundWire.calls.size()==16 && "finder_busy".equals(last(outcomes).code()),"inflight starts bounded");
        bound.send(PEER,"PAD","reserved-stop","STOP_RING",outcomes::add);
        check(boundWire.calls.size()==17,"stop retains capacity under start saturation");
        bound.update(selected("bound","tablet",false));
        check(boundWire.cancelled.size()==17,"grant removal cancels all inflight tasks");
        bound.send(PEER,"PAD","grant-removed","RING",outcomes::add);
        check("permission_denied".equals(last(outcomes).code()),"effective operation required");
        bound.update(selected("bound","tablet",true));
        bound.send(PEER,"PAD","pending0","RING",outcomes::add);
        check(boundWire.calls.size()==17 && "stale_session".equals(last(outcomes).code()),"grant cycle retains native message tombstone within same session");
        bound.close();
        Wire closingWire=new Wire();OplusFinderSender closing=new OplusFinderSender(closingWire);
        closing.update(selected("closing","phone",true));outcomes.clear();
        closing.send(PEER,"PHONE","ack-in-flight","RING",outcomes::add);Call inFlight=closingWire.calls.get(0);
        closing.close();
        check(closingWire.cancelled.size()==1 && closingWire.calls.size()==2
                && "stop".equals(closingWire.calls.get(1).payload().getString("action")),"close sends real stop even before delayed start ack");
        int beforeLate=outcomes.size();inFlight.reply().accept(reply(inFlight,true,100));
        check(outcomes.size()==beforeLate && !last(outcomes).success(),"closed sender cannot report delayed start success");
        System.out.println("PASS " + assertions + " Oplus finder sender assertions; synthetic transport only");
    }
}
