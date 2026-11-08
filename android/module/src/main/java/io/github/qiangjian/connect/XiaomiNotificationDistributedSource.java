package io.github.qiangjian.connect;

import android.app.Application;
import android.content.Context;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Observes the actual Android-pad export after the OEM's filtering/subscription decisions. */
final class XiaomiNotificationDistributedSource implements AutoCloseable {
    record Selection(String peer,String session,String localNativeId,String targetNativeId) {}
    interface Gate {
        boolean processAuthenticated();
        boolean allows(Selection selection);
        boolean allowsPackage(String pkg);
    }
    interface Sender {
        /** Bounded enqueue/copy only. Returning true is not a delivery or native publish callback. */
        boolean offer(Selection selection,byte[] nativeMessage);
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Gate gate;
    private final Supplier<Selection> selected;
    private final Sender sender;
    private HookHandle hook;
    private volatile boolean closed;
    XiaomiNotificationDistributedSource(XposedModule module,ClassLoader loader,Gate gate,Supplier<Selection> selected,Sender sender){
        this.module=module;this.loader=loader;this.gate=gate;this.selected=selected;this.sender=sender;
    }
    void install(Context nativeHost)throws ReflectiveOperationException{
        if(closed||hook!=null||!gate.processAuthenticated()||!"com.milink.service".equals(nativeHost.getPackageName())
                ||!"com.milink.crossdeviceservice".equals(Application.getProcessName()))throw new SecurityException("native_process_required");
        Class<?> request=Class.forName("com.xiaomi.dist.notification.listener.trans.NotificationPostRequest",false,loader);
        Class<?> device=Class.forName("com.xiaomi.continuity.networking.TrustedDeviceInfo",false,loader);
        Class<?> message=Class.forName("com.xiaomi.dist.notification.common.data.NotificationMessage",false,loader);
        Class<?> callback=Class.forName("com.xiaomi.continuity.messagecenter.PublisherManager$IPublishResult",false,loader);
        Method nativeId=device.getMethod("getDeviceId"),nativeType=device.getMethod("getDeviceType"),pkg=message.getMethod("getPackageName");
        hook=module.hook(request.getMethod("transNotification",device,message,callback))
                .setId("qj.xiaomi.notification.distributed.source").intercept(chain->{
                    if(!closed&&gate.processAuthenticated())try{
                        Selection s=selected.get();Object target=chain.getArg(0),value=chain.getArg(1);
                        if(s!=null&&s.peer()!=null&&!s.peer().isEmpty()&&s.session()!=null&&s.session().matches("[0-9a-f]{64}")
                                &&s.targetNativeId()!=null&&s.targetNativeId().equals(nativeId.invoke(target))
                                &&Integer.valueOf(2).equals(nativeType.invoke(target))&&gate.allows(s)
                                &&gate.allowsPackage((String)pkg.invoke(value))){
                            var payload=XiaomiNotificationDistributedCodec.capture(value);
                            if(s.localNativeId().equals(payload.senderDeviceId())&&s.targetNativeId().equals(payload.targetDeviceId()))
                                sender.offer(s,XiaomiNotificationDistributedCodec.encode(payload));
                        }
                    }catch(ReflectiveOperationException|RuntimeException rejected){/* No native content in logs. */}
                    // Preserve the exact callback and original behavior. No queued bridge event is
                    // reported as the OEM's onSendSuccess; shared routing must bind real receipts.
                    return chain.proceed();
                });
    }
    @Override public void close(){closed=true;if(hook!=null){hook.unhook();hook=null;}}
}
