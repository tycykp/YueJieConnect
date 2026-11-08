package io.github.qiangjian.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Authenticates a shared-UID process by binding the pinned APK's exact exported service.
 * The unpredictable challenge returns over a Binder callback; its PID comes from the kernel.
 * No package string, process-name claim or blanket UID 1000 grant is accepted as proof. */
final class VendorProcessProof implements AutoCloseable {
    static final String ACTION="io.github.qiangjian.connect.PROVE_MIRROR_PROCESS_V1";
    static final String SERVICE="com.xiaomi.mirror.SynergyService";
    private static final String DESCRIPTOR="io.github.qiangjian.connect.ProcessProof.v1";
    private static final String CALLBACK="io.github.qiangjian.connect.ProcessProofCallback.v1";
    private final Context context;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile IBinder remote;
    private boolean bound,closed;
    private int verifiedUid=-1,verifiedPid=-1;
    private String challenge;
    private long deadline;
    private long nextBind;
    VendorProcessProof(Context context){this.context=context;}
    synchronized boolean verifies(int uid,int pid){return !closed&&remote!=null&&remote.isBinderAlive()&&uid==verifiedUid&&pid==verifiedPid;}
    void start(){
        if(closed||bound)return;
        if(SystemClock.elapsedRealtime()<nextBind){requestVerification();return;}
        nextBind=SystemClock.elapsedRealtime()+5000;
        if(CallerVerifier.mirrorUid(context.getPackageManager())<0)return;
        try{bound=context.bindService(new Intent(ACTION).setComponent(new ComponentName(CallerPolicy.MIRROR,SERVICE)),connection,Context.BIND_AUTO_CREATE);}
        catch(SecurityException denied){android.util.Log.w("QiangJianBridge","mirror_process_proof_bind_denied");}
    }
    // A newly started pinned Mirror process can ask for a fresh OS bind/challenge. Merely
    // sharing its UID never passes verifies(), and an already verified Binder is left intact.
    void requestVerification(){main.post(()->{if(closed||remote!=null)return;main.removeCallbacks(retry);main.postDelayed(retry,Math.max(0,nextBind-SystemClock.elapsedRealtime()));});}
    private final Runnable retry=()->{if(closed||remote!=null)return;releaseBinding();start();};
    private void releaseBinding(){if(bound){bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}}
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(!CallerPolicy.MIRROR.equals(name.getPackageName())||!SERVICE.equals(name.getClassName()))return;
            synchronized(VendorProcessProof.this){if(closed)return;remote=binder;verifiedUid=-1;verifiedPid=-1;
                byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);StringBuilder text=new StringBuilder();for(byte value:nonce)text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));challenge=text.toString();deadline=SystemClock.elapsedRealtime()+5000;}
            try{binder.linkToDeath(()->invalidate(binder),0);}catch(RemoteException dead){invalidate(binder);return;}
            Thread worker=new Thread(()->prove(binder),"qj-process-proof");worker.setDaemon(true);worker.start();
        }
        @Override public void onServiceDisconnected(ComponentName name){invalidate(remote);}
        @Override public void onBindingDied(ComponentName name){invalidate(remote);releaseBinding();}
        @Override public void onNullBinding(ComponentName name){invalidate(remote);releaseBinding();}
    };
    private synchronized void invalidate(IBinder expected){if(remote!=expected)return;remote=null;verifiedUid=-1;verifiedPid=-1;challenge=null;}
    private void prove(IBinder binder){
        String nonce;synchronized(this){if(remote!=binder||closed)return;nonce=challenge;}
        IBinder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);String echoed=data.readString();int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();
            boolean accepted=false;
            synchronized(VendorProcessProof.this){
                if(!closed&&remote==binder&&nonce!=null&&nonce.equals(challenge)&&nonce.equals(echoed)&&data.dataAvail()==0
                        &&SystemClock.elapsedRealtime()<deadline&&uid==CallerVerifier.mirrorUid(context.getPackageManager())&&pid>0){
                    verifiedUid=uid;verifiedPid=pid;challenge=null;accepted=true;
                }
            }
            if(reply!=null){reply.writeNoException();reply.writeInt(accepted?1:0);}
            if(accepted)android.util.Log.i("QiangJianBridge","mirror_process_proof_verified");
            return true;
        }};
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(DESCRIPTOR);data.writeString(nonce);data.writeStrongBinder(callback);
            if(!binder.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0)){invalidate(binder);return;}reply.readException();}
        catch(RemoteException|RuntimeException failure){invalidate(binder);main.post(this::releaseBinding);android.util.Log.w("QiangJianBridge","mirror_process_proof_unavailable "+failure.getClass().getSimpleName());}
        finally{reply.recycle();data.recycle();}
    }
    /** Returned only for the private proof action by the injected, pinned Mirror service. */
    static IBinder endpoint(Supplier<Context> host,BooleanSupplier allowed){
        return new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);Context context=host.get();
            if(!allowed.getAsBoolean()||context==null)throw new SecurityException("proof_not_ready");
            try{if(Binder.getCallingUid()!=context.getPackageManager().getApplicationInfo("io.github.qiangjian.connect",0).uid)throw new SecurityException("proof_caller_rejected");}
            catch(android.content.pm.PackageManager.NameNotFoundException absent){throw new SecurityException("proof_caller_rejected");}
            String nonce=data.readString();IBinder callback=data.readStrongBinder();
            if(nonce==null||!nonce.matches("[0-9a-f]{64}")||callback==null||data.dataAvail()!=0||reply==null)throw new SecurityException("invalid_proof");
            Parcel request=Parcel.obtain(),response=Parcel.obtain();long identity=Binder.clearCallingIdentity();
            try{request.writeInterfaceToken(CALLBACK);request.writeString(nonce);if(!callback.transact(IBinder.FIRST_CALL_TRANSACTION,request,response,0))throw new RemoteException("proof_callback_unavailable");response.readException();if(response.readInt()!=1)throw new SecurityException("proof_rejected");}
            finally{Binder.restoreCallingIdentity(identity);response.recycle();request.recycle();}
            reply.writeNoException();return true;
        }};
    }
    @Override public void close(){synchronized(this){closed=true;invalidate(remote);}main.removeCallbacksAndMessages(null);releaseBinding();}
}
