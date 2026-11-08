package io.github.qiangjian.connect;
import java.util.*;
import org.json.*;
import android.os.Handler;
import android.view.*;
import com.oplus.findphone.client.device.DeviceListItem;
import com.oplus.findphone.client.device.devicedetail.DeviceDetailData;
import com.oplus.findphone.client.ViewModel.DeviceDetailViewModel;
import com.oplus.findphone.client.map.*;
import io.github.libxposed.api.XposedModule;

public final class OplusFinderUiCheck {
    static final String ID="qj:"+"a".repeat(64);
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static JSONObject selected(String session,boolean allowed)throws Exception{return new JSONObject().put("id",ID).put("session_id",session)
        .put("operations",allowed?new JSONArray().put("device_finder"):new JSONArray()).put("real",new JSONObject().put("kind","tablet").put("name","Synthetic Pad"));}
    static JSONObject ack(BridgeClient.Call call,boolean ringing)throws Exception{return new JSONObject().put("status","ok").put("session_id",call.session()).put("request_id",call.request())
        .put("result",new JSONObject().put("operation","device_finder").put("data",new JSONObject().put("ringing",ringing).put("remaining_ms",ringing?5000:0)));}
    static BridgeClient.Call last(BridgeClient client){return client.calls.get(client.calls.size()-1);}
    static DeviceListItem local(D list){return ((hc.i.b)list.c.received.get(list.c.received.size()-1)).a;}
    public static void main(String[] args)throws Exception{
        BridgeClient client=new BridgeClient();OplusFinderUiAdapter ui=new OplusFinderUiAdapter(new XposedModule(),client,OplusFinderUiCheck.class.getClassLoader(),()->true);
        D list=new D();DeviceListItem cloud=new DeviceListItem();cloud.a="real-account-device";cloud.b="cloud fixture";cloud.h=9;
        hc.i.b nativeCloud=new hc.i.b();nativeCloud.a=cloud;List<Object> original=List.of(nativeCloud);
        list.c.d(original);list.onCreateView(new LayoutInflater(),new ViewGroup(),null);
        check(list.c.received.size()==1&&list.c.received.get(0)==nativeCloud,"no unpaired local card");
        client.update(selected("s1",true));
        check(original.size()==1&&list.c.received.size()==3&&list.c.received.get(0)==nativeCloud,"display copy appends local group, keeps cloud row identity");
        check(cloud.h==9&&"real-account-device".equals(cloud.a),"account source never mutated");
        DeviceListItem peer=local(list);check(ID.equals(peer.a)&&peer.d==3&&peer.h==1&&!peer.n,"truthful authenticated pad identity, no coordinates");
        check(peer.j.contains("附近已配对"),"explicit local source label");
        wc.e.c header=new wc.e.c();int binds=list.c.originalBinds;list.c.onBindViewHolder(header,1);
        check(list.c.originalBinds==binds&&header.a.text.toString().contains("附近已配对")&&header.b.visibility==View.GONE,"native header has local label and no cloud learn-more action");
        wc.e.a icon=new wc.e.a();list.c.onBindViewHolder(icon,2);
        check(icon.a.resource==7,"recycled account image replaced by OEM neutral drawable");
        N cloudClick=new N(list.c,cloud);cloudClick.onClick(new View());check(cloudClick.cloudClicks==1,"ordinary native cloud click unchanged");
        N localClick=new N(list.c,peer);localClick.onClick(new View());
        check(localClick.cloudClicks==0&&list.selected==peer,"local selection goes through original native detail navigation, no cloud click path");
        t detail=list.detail;detail.onViewCreated(new View(),null);Handler.drain();
        check(detail.m.cloudFetches==0&&client.calls.size()==1,"local native detail never fetches account repository");
        check(detail.m.a.value==null&&detail.m.c.value==wc.l.a,"native detail remains loading before remote state ack");
        BridgeClient.Call first=last(client);check("state".equals(first.payload().getString("action")),"no synthetic start to make UI ready");
        first.callback().accept(ack(first,true).put("session_id","old"));
        check(detail.m.a.value==null&&detail.m.c.value==wc.l.e,"wrong-session state cannot render success");
        Handler.tick();BridgeClient.Call real=last(client);real.callback().accept(ack(real,true));
        DeviceDetailData data=(DeviceDetailData)detail.m.b.value;
        check(detail.m.c.value==wc.l.g&&data.id.equals(ID)&&data.type==3&&data.support.ring&&data.support.stopRing,"real state ack creates native pad ring/stop support");
        check(data.instructions.get(s3.e.RING)==4,"actual remote ringing drives native STOP state");
        com.oplus.findphone.client.device.devicedetail.b view=(com.oplus.findphone.client.device.devicedetail.b)detail.m.a.value;
        check(view.g.equals(new ArrayList<>(List.of("native-ring")))&&view.h.isEmpty()&&view.i.isEmpty()&&view.j.isEmpty(),"original ring view retained, cloud history/account/product actions absent");
        detail.m.d.a.b=0;Handler.tick();last(client).callback().accept(ack(last(client),false));
        check(detail.m.d.a.b==0,"native in-flight start remains waiting until callback");
        detail.m.d.a.b=2;Handler.tick();last(client).callback().accept(ack(last(client),false));
        check(detail.m.d.a.b==-1&&((DeviceDetailData)detail.m.b.value).instructions.get(s3.e.RING)==-1,"real stopped state clears completed native wait");
        int before=client.calls.size();detail.onDestroyView();
        check(client.calls.size()==before+1&&"stop".equals(last(client).payload().getString("action")),"last native detail close sends actual remote stop");
        detail.m.onCleared();check(client.calls.size()==before+1,"view and model release do not double-stop");
        DeviceDetailViewModel cloudModel=new DeviceDetailViewModel();cloudModel.h="real-account-device";cloudModel.e();check(cloudModel.cloudFetches==1,"ordinary native repository unchanged");
        client.update(selected("s1",false));check(list.c.received.size()==1,"grant withdrawal removes only local display group");
        client.update(selected("s2",true));localClick=new N(list.c,local(list));localClick.onClick(new View());
        detail=list.detail;detail.onViewCreated(new View(),null);Handler.drain();BridgeClient.Call old=last(client);
        client.update(selected("s3",true));check(detail.backs==1&&client.cancels.contains(old.request()),"session change withdraws native detail and cancels stale state read");
        old.callback().accept(ack(old,true));check(detail.m.a.value==null,"late state ack cannot revive withdrawn native detail");
        ui.close();check(list.c.received.size()==1&&list.c.received.get(0)==nativeCloud&&client.listeners.isEmpty(),"close restores original display list and subscription");
        check(XposedModule.hooks.isEmpty(),"native hooks released");
        System.out.println("PASS "+checks+" actual local finder UI adapter assertions; OEM/Android/transport doubles only");
    }
}
