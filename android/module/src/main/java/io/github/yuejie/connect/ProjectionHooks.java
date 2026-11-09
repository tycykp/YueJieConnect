package io.github.yuejie.connect;

import io.github.libxposed.api.XposedModule;
import java.util.function.BooleanSupplier;

/** Brand boundary only; each adapter owns its hooks, state and sole projection listener. */
final class ProjectionHooks {
    private final XiaomiProtocolAdapter xiaomi;
    private final XiaomiCirculateAdapter xiaomiUi;
    private final OplusProtocolAdapter oplus;
    ProjectionHooks(XposedModule module,BridgeClient client,int source,ClassLoader loader,BooleanSupplier allowed){
        if(source!=1&&source!=2)throw new IllegalArgumentException("Unknown adapter");
        boolean ui=source==1&&"com.milink.service:ui".equals(android.app.Application.getProcessName());
        xiaomi=source==1&&!ui?new XiaomiProtocolAdapter(module,client,loader,allowed):null;
        xiaomiUi=ui?new XiaomiCirculateAdapter(module,client,loader,allowed):null;
        oplus=source==2?new OplusProtocolAdapter(module,client,loader,allowed):null;
    }
    void install()throws ReflectiveOperationException{
        if(xiaomi!=null)xiaomi.install();else if(xiaomiUi!=null)xiaomiUi.install();else oplus.install();
    }
    void close(){if(xiaomi!=null)xiaomi.close();else if(xiaomiUi!=null)xiaomiUi.close();else oplus.close();}
}
