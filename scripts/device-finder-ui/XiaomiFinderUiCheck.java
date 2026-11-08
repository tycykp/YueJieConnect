package io.github.qiangjian.connect;
import org.json.*;
import android.app.Application;
import io.github.libxposed.api.XposedModule;
import com.miui.circulate.ringfind.sc.ui2client.SCClient;
public final class XiaomiFinderUiCheck {
    static int count;static void check(boolean value,String message){count++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        BridgeClient client=new BridgeClient();boolean[] allowed={true};SCClient original=new SCClient();
        Application.process="com.milink.service:core";boolean rejected=false;
        try{new XiaomiFinderUiSupport(new XposedModule(),client,XiaomiFinderUiCheck.class.getClassLoader(),()->true);}catch(IllegalStateException expected){rejected=true;}
        check(rejected,"wrong process cannot silently install UI hook in core");
        Application.process="com.milink.service:ui";
        XiaomiFinderUiSupport support=new XiaomiFinderUiSupport(new XposedModule(),client,XiaomiFinderUiCheck.class.getClassLoader(),()->allowed[0]);
        check(!original.isSupportGetStatus("qj:peer"),"no projection leaves original support result");
        client.selected=new JSONObject().put("id","qj:peer").put("session_id","fresh").put("operations",new JSONArray().put("device_finder"));
        check(original.isSupportGetStatus("qj:peer"),"real current paired finder peer supported in actual UI process");
        check(!original.isSupportGetStatus("cloud-device"),"unrelated trusted-device support unchanged");
        client.selected.put("operations",new JSONArray());check(!original.isSupportGetStatus("qj:peer"),"effective grant withdrawal closes gate");
        client.selected.put("operations",new JSONArray().put("device_finder")).put("session_id","");check(!original.isSupportGetStatus("qj:peer"),"no session cannot enable native finder");
        client.selected.put("session_id","fresh");allowed[0]=false;check(!original.isSupportGetStatus("qj:peer"),"identity gate preserved");
        allowed[0]=true;support.close();check(!original.isSupportGetStatus("qj:peer")&&XposedModule.hooks.isEmpty(),"close returns ordinary OEM behavior");
        System.out.println("PASS "+count+" actual Xiaomi UI-process support assertions; native class/Android hooks are doubles");
    }
}
