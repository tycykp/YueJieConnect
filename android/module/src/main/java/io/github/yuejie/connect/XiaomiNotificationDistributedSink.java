package io.github.yuejie.connect;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Invokes the real phone/pad consumer with its policy intact; an OS observation is the receipt. */
final class XiaomiNotificationDistributedSink implements AutoCloseable {
    static final String POST = "xiaomi_notification_post", REMOVE = "xiaomi_notification_remove";
    interface Gate {
        boolean allowed(String peer,String session,String operation);
        /** Both native IDs must be bound to the actual authenticated directory, never guessed. */
        boolean nativeRoute(String peer,String session,String nativeSender,String nativeTarget);
        boolean allowsPackage(String pkg);
    }
    record Effect(String key,long revision,String effect) {}
    private record Owned(String key,long revision,XiaomiNotificationDistributedCodec.Message message,
                         XiaomiNotificationDistributedCodec.Message observed,byte[] digest) {}
    private final Context context;
    private final ClassLoader loader;
    private final Gate gate;
    private final NotificationManager manager;
    private final Object processor,presenter;
    private final Method process,cancel,padEnabled,appId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final Map<String,Owned> owned = new HashMap<>();
    private final Map<String,CompletableFuture<Effect>> active = new HashMap<>();
    private final Semaphore capacity = new Semaphore(8);
    private volatile boolean closed;
    private volatile long generation;
    private volatile String peer="",session="";
    XiaomiNotificationDistributedSink(Context nativeHost,ClassLoader loader,Gate gate)throws ReflectiveOperationException {
        context=nativeHost.getApplicationContext();this.loader=loader;this.gate=gate;
        if(!"com.milink.service".equals(context.getPackageName())||!"com.milink.crossdeviceservice".equals(Application.getProcessName()))throw fail("native_process_required");
        manager=context.getSystemService(NotificationManager.class);
        if(manager==null||gate==null)throw fail("native_notification_unavailable");
        Class<?> type=Class.forName("com.xiaomi.dist.notification.trans.client.receive.NotificationProcessor",false,loader);
        processor=type.getMethod("getInstance",Context.class).invoke(null,context);
        Class<?> message=Class.forName("com.xiaomi.dist.notification.common.data.NotificationMessage",false,loader);
        process=type.getMethod("processorNotification",message);
        var field=type.getDeclaredField("mPresenter");field.setAccessible(true);presenter=field.get(processor);
        cancel=presenter.getClass().getMethod("cancelNotification",String.class,int.class);
        padEnabled=Class.forName("com.xiaomi.dist.notification.common.setting.NotificationTransSetting",false,loader).getMethod("isPadSwitchEnabled",Context.class);
        appId=Class.forName("com.xiaomi.dist.notification.handler.utils.AppPkgId",false,loader).getMethod("fromPackageName",Context.class,String.class);
        worker.setRemoveOnCancelPolicy(true);
    }
    void onProjection(String nextPeer,String nextSession){
        if(closed)return;
        worker.execute(()->{String p=nextPeer==null?"":nextPeer,s=nextSession==null?"":nextSession;
            if(!peer.equals(p)||!session.equals(s)){reset();peer=p;session=s;}});
    }
    void authorizationChanged(){if(!closed)worker.execute(this::reset);}
    CompletableFuture<Effect> post(String peer,String session,String key,long revision,byte[] encoded){
        final XiaomiNotificationDistributedCodec.Message message;
        final byte[] input;
        try{input=encoded.clone();message=XiaomiNotificationDistributedCodec.decode(input);}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
        CompletableFuture<Effect> result=new CompletableFuture<>();
        if(closed)return CompletableFuture.failedFuture(fail("closed"));
        if(!capacity.tryAcquire())return CompletableFuture.failedFuture(fail("capacity"));
        result.whenComplete((v,e)->capacity.release());
        result.orTimeout(4,TimeUnit.SECONDS);
        worker.execute(()->{
            try{
                require(peer,session,key,revision,POST,message);
                byte[] digest=NotificationSyncLedger.digest(input);
                Owned old=owned.get(key);
                if(old!=null&&(revision<old.revision||(revision==old.revision&&!Arrays.equals(old.digest,digest))))throw fail("stale_target");
                if(old!=null&&(old.message.notificationId()!=message.notificationId()||!old.message.senderDeviceId().equals(message.senderDeviceId())
                        ||!old.message.targetDeviceId().equals(message.targetDeviceId())||!old.message.packageName().equals(message.packageName())
                        ||!old.message.notificationKey().equals(message.notificationKey())))throw fail("native_target_changed");
                if(active.containsKey(key))throw fail("busy");
                if(old!=null&&old.revision==revision){if(!present(message))throw fail("effect_missing");result.complete(new Effect(key,revision,"native_posted"));return;}
                if(owned.size()>=128&&old==null)throw fail("capacity");
                if(owned.values().stream().anyMatch(o->!o.key.equals(key)&&o.message.notificationId()==message.notificationId()&&o.message.senderDeviceId().equals(message.senderDeviceId())))throw fail("native_target_conflict");
                if(old==null&&find(message)!=null)throw fail("native_target_conflict");
                Object nativeMessage=XiaomiNotificationDistributedCodec.build(loader,message);
                long epoch=generation;
                owned.put(key,new Owned(key,revision,message,old==null?null:old.observed,digest));active.put(key,result);
                result.whenComplete((v,e)->{if(!closed)worker.execute(()->active.remove(key,result));});
                main.post(()->{
                    try{
                        if(result.isDone())return;
                        if(epoch!=generation)throw fail("stale_session");require(peer,session,key,revision,POST,message);
                        // This retains pad switch, main-screen suppression and native app classification.
                        process.invoke(processor,nativeMessage);
                        worker.execute(()->observe(peer,session,key,revision,message,true,epoch,15,result));
                    }catch(Exception e){result.completeExceptionally(fail("native_post_failed"));}
                });
            }catch(Exception e){result.completeExceptionally(e);}
        });return result;
    }
    CompletableFuture<Effect> remove(String peer,String session,String key,long revision){
        var result=new CompletableFuture<Effect>();
        if(closed)return CompletableFuture.failedFuture(fail("closed"));
        if(!capacity.tryAcquire())return CompletableFuture.failedFuture(fail("capacity"));
        result.whenComplete((v,e)->capacity.release());
        result.orTimeout(4,TimeUnit.SECONDS);
        worker.execute(()->{try{
            Owned o=owned.get(key);if(o==null||revision<=o.revision)throw fail("stale_target");
            require(peer,session,key,revision,REMOVE,o.message);if(active.containsKey(key))throw fail("busy");
            if(find(o.message)!=null&&!present(o.message)&&(o.observed==null||!present(o.observed)))throw fail("native_target_conflict");
            owned.put(key,new Owned(key,revision,o.message,o.observed,o.digest));active.put(key,result);long epoch=generation;
            result.whenComplete((v,e)->{if(!closed)worker.execute(()->active.remove(key,result));});
            main.post(()->{try{if(result.isDone())return;if(epoch!=generation)throw fail("stale_session");require(peer,session,key,revision,REMOVE,o.message);
                cancel.invoke(presenter,o.message.senderDeviceId(),o.message.notificationId());
                worker.execute(()->observe(peer,session,key,revision,o.message,false,epoch,15,result));
            }catch(Exception e){result.completeExceptionally(fail("native_remove_failed"));}});
        }catch(Exception e){result.completeExceptionally(e);}});return result;
    }
    private void require(String p,String s,String key,long revision,String operation,XiaomiNotificationDistributedCodec.Message m)throws ReflectiveOperationException{
        if(closed||!peer.equals(p)||!session.equals(s)||!s.matches("[0-9a-f]{64}"))throw fail("stale_session");
        if(key==null||!key.matches("[A-Za-z0-9_-]{1,96}")||revision<1)throw fail("invalid_request");
        if(!gate.allowed(p,s,operation)||!gate.nativeRoute(p,s,m.senderDeviceId(),m.targetDeviceId())||!gate.allowsPackage(m.packageName()))throw fail("permission_denied");
        String app=((Enum<?>)appId.invoke(null,context,m.packageName())).name();
        // Calls/SMS have separate owners; no ANCS/iPhone identity or app-package substitution.
        if(!Set.of("MI_WECHAT","MI_QQ","MI_LARK","MI_DINGTALK","MI_WEWORK").contains(app))throw fail("native_package_filtered");
        if(!(Boolean)padEnabled.invoke(null,context)||!manager.areNotificationsEnabled())throw fail("native_policy_denied");
        var channel=manager.getNotificationChannel("ancs_channel");
        if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)throw fail("native_channel_disabled");
    }
    private StatusBarNotification find(XiaomiNotificationDistributedCodec.Message m){
        for(var n:manager.getActiveNotifications())if(m.senderDeviceId().equals(n.getTag())&&m.notificationId()==n.getId())return n;return null;
    }
    private boolean present(XiaomiNotificationDistributedCodec.Message m){
        var n=find(m);if(n==null||!"ancs_channel".equals(n.getNotification().getChannelId()))return false;
        var extras=n.getNotification().extras;
        return m.packageName().equals(extras.getString("hyperOs.packageName"))
                &&(m.title()+" | "+m.deviceName()).contentEquals(extras.getCharSequence(Notification.EXTRA_TITLE,""))
                &&m.content().contentEquals(extras.getCharSequence(Notification.EXTRA_TEXT,""));
    }
    private void observe(String p,String s,String key,long revision,XiaomiNotificationDistributedCodec.Message m,boolean posted,long epoch,int attempts,CompletableFuture<Effect> result){
        if(result.isDone())return;
        try{
            if(epoch!=generation)throw fail("stale_session");require(p,s,key,revision,posted?POST:REMOVE,m);
            if(posted?present(m):find(m)==null){
                Owned current=owned.get(key);
                if(current==null||current.revision!=revision)throw fail("stale_target");
                owned.put(key,new Owned(key,revision,m,posted?m:null,current.digest));
                result.complete(new Effect(key,revision,posted?"native_posted":"native_removed"));return;
            }
            if(attempts==0)throw fail("native_effect_unverified");
            worker.schedule(()->observe(p,s,key,revision,m,posted,epoch,attempts-1,result),200,TimeUnit.MILLISECONDS);
        }catch(Exception e){result.completeExceptionally(e);}
    }
    private void reset(){
        generation++;for(var f:active.values())f.completeExceptionally(fail("stale_session"));active.clear();
        // A notify Binder call may have returned before the OS exposes its record. Send the
        // cancellation after the queued main-thread native post even when presence is not yet
        // observable; do not touch a conflicting notification that appeared independently.
        for(Owned o:owned.values())main.post(()->{try{
            if(find(o.message)==null||oursPresent(o))cancel.invoke(presenter,o.message.senderDeviceId(),o.message.notificationId());
        }catch(Exception ignored){}});
        owned.clear();
    }
    private boolean oursPresent(Owned o){return present(o.message)||(o.observed!=null&&present(o.observed));}
    @Override public void close(){if(closed)return;closed=true;worker.execute(()->{reset();worker.shutdown();});}
    private static IllegalStateException fail(String code){return new IllegalStateException(code);}
}
