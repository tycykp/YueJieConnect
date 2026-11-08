package io.github.qiangjian.connect;

import android.app.Application;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** SCClient is used in :ui; its support gate must run in that process, not only in :core. */
final class XiaomiFinderUiSupport implements AutoCloseable {
    private final HookHandle hook;
    private volatile boolean closed;
    XiaomiFinderUiSupport(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed) throws ReflectiveOperationException {
        if (!"com.milink.service:ui".equals(Application.getProcessName()))
            throw new IllegalStateException("finder_ui_process_required");
        Class<?> nativeClient=Class.forName("com.miui.circulate.ringfind.sc.ui2client.SCClient",false,loader);
        hook=module.hook(nativeClient.getDeclaredMethod("isSupportGetStatus",String.class)).setId("qj.finder.xiaomi.ui.support").intercept(chain->{
            JSONObject p=client.projection();String id=(String)chain.getArg(0);
            if (!closed&&allowed.getAsBoolean()&&p!=null&&id!=null&&id.equals(p.optString("id"))
                    &&!p.optString("session_id").isEmpty()&&hasFinder(p)) return true;
            return chain.proceed();
        });
    }
    private static boolean hasFinder(JSONObject p){
        JSONArray operations=p.optJSONArray("operations");
        if(operations!=null)for(int i=0;i<operations.length();i++)if("device_finder".equals(operations.optString(i)))return true;
        return false;
    }
    @Override public void close(){if(closed)return;closed=true;hook.unhook();}
}
