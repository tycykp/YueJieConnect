package io.github.yuejie.connect;

import android.os.Handler;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import com.android.services.telephony.TelephonyConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static io.github.yuejie.connect.TelephonySyncController.*;

/** Runs the real production wrapper against isolated native/Looper doubles; no Android/radio action. */
public final class TelephonySyncConnectionsCheck {
    private static final Session SESSION = new Session("synthetic-peer", "a".repeat(64));
    private static final class Fixture implements Policy {
        boolean consent = true, audio = true;
        final List<Snapshot> events = new ArrayList<>();
        final List<Command> observedControls = new ArrayList<>();
        final ConnectionService service = new ConnectionService();
        final TelephonyConnection nativeCall = new TelephonyConnection();
        final TelephonySyncConnections source;
        Fixture() throws Exception {
            Handler.reset(); service.live.add(nativeCall);
            source = new TelephonySyncConnections(SESSION, this, events::add, getClass().getClassLoader(), (command, observed) -> observedControls.add(command));
            source.attach(service); Handler.runReady();
        }
        public boolean observes(Session s) { return consent; }
        public boolean allows(Session s, Action a) { return consent; }
        public boolean audio(Session s, String call) { return audio; }
        Snapshot latest() { return events.get(events.size()-1); }
        CompletableFuture<Result> action(String request, Action action) {
            Snapshot current = latest(); var result = source.execute(new Command(SESSION, request, current.call(), current.revision(), action),
                    android.os.SystemClock.elapsedRealtime() + 1000); Handler.runReady(); return result;
        }
        void state(int value) { nativeCall.state = value; Handler.advance(100); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] ignored) throws Exception {
        Fixture f = new Fixture();
        check(f.events.size()==1 && f.latest().state()==State.RINGING,"native initial ring exactly once");
        Handler.advance(100); check(f.events.size()==1,"unchanged native state must not flood events");

        var answer=f.action("answer",Action.ANSWER); check(f.nativeCall.actions==1 && !answer.isDone(),"void native action is not observed success");
        check(f.observedControls.isEmpty(),"audio approval signal must not precede observed native execution");
        f.state(Connection.STATE_ACTIVE); check("observed".equals(answer.join().code()),"actual native active resolves answer");
        check(f.observedControls.size()==1 && f.observedControls.get(0).action()==Action.ANSWER,"exact observed native control signal");
        check(f.source.audioPolicy().active(SESSION,f.latest().call(),TelephonySyncAudioSession.Role.PHONE),"exact active native audio lease");
        f.audio=false;check(!f.source.audioPolicy().active(SESSION,f.latest().call(),TelephonySyncAudioSession.Role.PHONE),"independent audio approval");

        f=new Fixture(); f.nativeCall.video=1;
        check("native_failure".equals(f.action("video",Action.ANSWER).join().code()) && f.nativeCall.actions==0,"audio answer must not operate video");

        f=new Fixture(); f.state(Connection.STATE_ACTIVE); var end=f.action("end",Action.END);
        String id=f.latest().call(); f.service.live.clear(); Handler.advance(100);
        check("observed".equals(end.join().code()),"native service removal proves terminal state");
        check(!f.source.audioPolicy().active(SESSION,id,TelephonySyncAudioSession.Role.PHONE),"missed remove callback must still withdraw audio");

        f=new Fixture(); f.state(Connection.STATE_ACTIVE); id=f.latest().call(); f.source.close();
        check(!f.source.audioPolicy().active(SESSION,id,TelephonySyncAudioSession.Role.PHONE),"close withdraws audio before queued cleanup"); Handler.runReady();

        f=new Fixture(); var pending=f.action("timeout",Action.ANSWER); Handler.advance(1000);
        check("outcome_unknown".equals(pending.join().code()),"native timeout cannot invent answer completion");

        f=new Fixture(); int count=f.events.size(); Connection nonNative=new Connection(); f.service.live.add(nonNative); Handler.advance(100);
        check(f.events.size()==count,"foreign/self-managed native class must not be exported");

        f=new Fixture(); for(int i=0;i<20;i++)f.service.live.add(new TelephonyConnection()); Handler.advance(100);
        check(f.events.size()==16,"bounded native call collection does not substitute a selected call");

        System.out.println("PASS: 8 production ConnectionService wrapper scenarios; isolated host native/Looper doubles only; no device/radio/audio");
    }
}
