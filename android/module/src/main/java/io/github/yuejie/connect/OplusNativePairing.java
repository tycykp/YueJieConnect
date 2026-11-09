package io.github.yuejie.connect;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses AccessoryFramework's real private Activity and its own accept/reject callbacks. */
final class OplusNativePairing implements Application.ActivityLifecycleCallbacks {
    private static final String PAGE="com.oplus.pantaconnect.appcore.connect.ui.DeviceConnectDialogActivity";
    private static final String IPC="com.oplus.pantaconnect.appcore.dialog.IInnerIpcInterface";
    private static final String PREFIX="yj-pairing:";
    private final BridgeClient client;
    private final ClassLoader loader;
    private final boolean launcher;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Context context;
    private String shown="",opening="";
    private long channelDeadline;
    private WeakReference<Activity> page=new WeakReference<>(null);
    private Method stateChange;
    private Class<?> stateType;
    static void install(XposedModule module,BridgeClient client,ClassLoader loader,String process)throws ReflectiveOperationException{
        OplusNativePairing host=new OplusNativePairing(client,loader,CallerPolicy.OPLUS_PAIRING.equals(process));
        Class<?> data=Class.forName("com.oplus.pantaconnect.model.ConnectViewData",false,loader);
        Class<?> command=Class.forName("com.oplus.pantaconnect.common.DialogSendCommand",false,loader);
        host.stateType=Class.forName("com.oplus.pantaconnect.appcore.connect.constants.ConnectState",false,loader);
        host.stateChange=Class.forName(PAGE,false,loader).getMethod("l",host.stateType);
        Class<?> utility=Class.forName("com.oplus.pantaconnect.appcore.utils.t",false,loader);
        module.hook(utility.getDeclaredMethod("a",Context.class,data,command)).setId("yj.native.pairing.oplus.result").intercept(chain->{
            Parcelable value=(Parcelable)chain.getArg(1);String id=parcelId(value);
            if(!id.startsWith(PREFIX))return chain.proceed();
            String session=id.substring(PREFIX.length()),action=((Enum<?>)chain.getArg(2)).name();
            host.main.post(()->host.answer(session,action));return null;
        });
        module.hook(Application.class.getDeclaredMethod("attach",Context.class)).setId("yj.native.pairing.oplus.context").intercept(chain->{
            Object result=chain.proceed();Context context=(Context)chain.getArg(0);
            if(CallerPolicy.OPLUS_PAIRING.equals(context.getPackageName())&&CallerVerifier.verify(context.getPackageManager(),Process.myUid())==2){
                host.context=context;client.attach(context);Application app=(Application)chain.getThisObject();app.registerActivityLifecycleCallbacks(host);
                client.nativePairing().listen(host::update);client.nativePairing().watch();
            }
            return result;
        });
    }
    private OplusNativePairing(BridgeClient client,ClassLoader loader,boolean launcher){this.client=client;this.loader=loader;this.launcher=launcher;}
    private static String parcelId(Parcelable value){if(value==null)return "";Parcel parcel=Parcel.obtain();try{value.writeToParcel(parcel,0);parcel.setDataPosition(0);String id=parcel.readString();return id==null?"":id;}finally{parcel.recycle();}}
    private void update(JSONObject snapshot){
        JSONObject state=snapshot.optJSONObject("pairing");if(state==null)return;
        String session=state.optString("session"),phase=state.optString("phase");
        if(launcher&&"awaiting_confirmation".equals(phase)&&state.has("real")&&!state.optBoolean("local_confirmed")&&!session.equals(shown)){
            shown=session;launch(state);
        }
        Activity activity=page.get();if(activity==null)return;
        String selected=activity.getIntent().getStringExtra("yj_pairing_session");
        if(!session.equals(selected)||"disconnected".equals(phase)||"stopped".equals(phase)){change(activity,"CONNECT_FAILED");return;}
        if("active".equals(phase)&&!session.equals(opening)){
            opening=session;channelDeadline=android.os.SystemClock.elapsedRealtime()+15_000;checkChannel(activity,session);
        }
    }
    private boolean current(Activity activity,String session){JSONObject state=client.nativePairing().state();return page.get()==activity&&!activity.isFinishing()
            &&state!=null&&session.equals(state.optString("session"))&&"active".equals(state.optString("phase"));}
    private void retryChannel(Activity activity,String session){
        if(!current(activity,session))return;
        if(android.os.SystemClock.elapsedRealtime()<channelDeadline)main.postDelayed(()->checkChannel(activity,session),500);
        else change(activity,"CONNECT_FAILED");
    }
    private void checkChannel(Activity activity,String session){
        if(!current(activity,session))return;
        JSONObject projection=client.projection();
        if(projection==null||!session.equals(projection.optString("session_id"))){
            retryChannel(activity,session);return;
        }
        try{NativeLogicalClient logical=new NativeLogicalClient(client);var lease=logical.allocate(projection.getString("id"),session);
            logical.open(lease).whenComplete((ok,error)->{
                logical.close(lease);if(!current(activity,session))return;
                if(error==null&&Boolean.TRUE.equals(ok)){android.util.Log.i("YueJiePairing","native_channel_remote_ack");change(activity,"CONNECT_SUCCESS");}
                else{android.util.Log.i("YueJiePairing","native_channel_retry "+(error==null?"closed":error.getMessage()));retryChannel(activity,session);}
            });
        }catch(Exception unavailable){android.util.Log.i("YueJiePairing","native_channel_wait "+unavailable.getClass().getSimpleName());retryChannel(activity,session);}
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private void change(Activity activity,String state){
        try{stateChange.invoke(activity,Enum.valueOf((Class)stateType,state));}catch(ReflectiveOperationException ignored){}
    }
    private void answer(String session,String action){
        JSONObject state=client.nativePairing().state();
        if(state==null||!session.equals(state.optString("session")))return;
        try{JSONObject request=new JSONObject().put("session",session);
            if("CONNECT_REQUEST_RESULT_ACCEPTED".equals(action))request.put("action","confirm").put("comparison_code",state.getString("comparison_code"))
                    .put("operations",new JSONArray(NativePairingPolicy.DEFAULT_GRANTS));
            else if("CONNECT_REQUEST_RESULT_REJECTED".equals(action)||"CONNECT_REQUEST_RESULT_TIME_OUT".equals(action))request.put("action","disconnect");else return;
            client.pairing(request,value->{if(!"ok".equals(value.optString("status"))&&page.get()!=null)change(page.get(),"CONNECT_FAILED");});
        }catch(org.json.JSONException ignored){}
    }
    private void launch(JSONObject state){
        try{JSONObject real=state.getJSONObject("real");String session=state.getString("session");
            Parcelable data=(Parcelable)Class.forName("com.oplus.pantaconnect.model.ConnectViewData",false,loader)
                    .getConstructor(String.class,String.class,String.class,int.class,String.class,boolean.class,boolean.class)
                    .newInstance(PREFIX+session,real.getString("model"),real.getString("name"),"tablet".equals(real.getString("kind"))?10:8,"",false,false);
            Bundle arguments=new Bundle();arguments.putParcelable("base_view_data",data);arguments.putInt("base_view_data_state",0);
            arguments.putBinder("intent_binder",new Binder(){
                @Override protected boolean onTransact(int code,Parcel input,Parcel output,int flags)throws android.os.RemoteException{
                    if(code==INTERFACE_TRANSACTION){output.writeString(IPC);return true;}
                    if(code!=1||Binder.getCallingUid()!=Process.myUid())return false;
                    input.enforceInterface(IPC);input.readString();input.readString();input.readString();
                    Bundle extras=input.readInt()!=0?Bundle.CREATOR.createFromParcel(input):null;
                    input.enforceNoDataAvail();output.writeNoException();output.writeInt(1);Bundle result=new Bundle();result.putInt("ipc_response_code",0);result.putString("ipc_response_message","");result.writeToParcel(output,0);
                    output.writeInt(extras==null?0:1);if(extras!=null)extras.writeToParcel(output,0);return true;
                }
            });
            context.startActivity(new Intent().setClassName(CallerPolicy.OPLUS_PAIRING,PAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("intent_bundle",arguments).putExtra("yj_pairing_session",session));
        }catch(Exception unavailable){android.util.Log.w("YueJiePairing","native_dialog_unavailable "+unavailable.getClass().getSimpleName());}
    }
    @Override public void onActivityResumed(Activity activity){if(PAGE.equals(activity.getClass().getName())&&NativePairingPolicy.session(activity.getIntent().getStringExtra("yj_pairing_session"))){page=new WeakReference<>(activity);opening="";}}
    @Override public void onActivityDestroyed(Activity activity){if(page.get()==activity)page.clear();}
    @Override public void onActivityCreated(Activity activity,Bundle state){}
    @Override public void onActivityStarted(Activity activity){}
    @Override public void onActivityPaused(Activity activity){}
    @Override public void onActivityStopped(Activity activity){}
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
}
