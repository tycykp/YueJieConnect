package io.github.yuejie.connect;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local-only presentation in original FindMyPhone cards/fragments. Never updates account/cloud data. */
final class OplusFinderUiAdapter implements AutoCloseable {
    private static final String LOCAL_LABEL = "附近已配对 · 本地互联";
    private static final int LOCAL_HEADER = -731942;
    private final BridgeClient client;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<Object,List<?>> lists = new WeakHashMap<>();
    private final Map<Object,WeakReference<Object>> owners = new WeakHashMap<>();
    private final Map<Object,Entry> details = new IdentityHashMap<>();
    private final Class<?> adapterClass, itemClass, rowClass, headerClass, detailClass, supportClass, viewDataClass, instructionClass;
    private final Method submit, itemAt, nativeViewData, liveValue, navigateUp;
    private final Object application, loading, success, unavailable, ringInstruction;
    private final String prefix = "finder-ui-" + java.util.UUID.randomUUID() + "-";
    private final AtomicLong sequence = new AtomicLong();
    private BridgeClient.Subscription projection;
    private boolean closed;
    private final Runnable poll = new Runnable() { @Override public void run() {
        if (closed) return;
        for (Entry entry : List.copyOf(details.values())) requestState(entry);
        main.postDelayed(this, 1000);
    }};
    private static final class Entry {
        final Object model;
        final String id, session;
        WeakReference<Object> fragment;
        String request;
        boolean seen;
        Entry(Object model,String id,String session) { this.model=model;this.id=id;this.session=session; }
    }

    OplusFinderUiAdapter(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed) throws Exception {
        this.client=client;this.allowed=allowed;
        if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("finder_main_thread_required");
        adapterClass=Class.forName("wc.e",false,loader);
        itemClass=Class.forName("com.oplus.findphone.client.device.DeviceListItem",false,loader);
        rowClass=Class.forName("hc.i$b",false,loader);headerClass=Class.forName("hc.i$e",false,loader);
        detailClass=Class.forName("com.oplus.findphone.client.device.devicedetail.DeviceDetailData",false,loader);
        supportClass=Class.forName("com.oplus.findphone.client.device.devicedetail.SupportInfo",false,loader);
        viewDataClass=Class.forName("com.oplus.findphone.client.device.devicedetail.b",false,loader);
        instructionClass=Class.forName("s3.e",false,loader);
        ringInstruction=instructionClass.getMethod("a",int.class).invoke(null,15);
        if(!(ringInstruction instanceof Enum<?> instruction)||!"RING".equals(instruction.name()))
            throw new IllegalStateException("finder_instruction_signature_unavailable");
        Class<?> listAdapter=Class.forName("androidx.recyclerview.widget.z",false,loader);
        Class<?> listFragment=Class.forName("com.oplus.findphone.client.map.D",false,loader);
        Class<?> detailFragment=Class.forName("com.oplus.findphone.client.map.t",false,loader);
        Class<?> model=Class.forName("com.oplus.findphone.client.ViewModel.DeviceDetailViewModel",false,loader);
        Class<?> app=Class.forName("com.oplus.FindMyPhoneApplication",false,loader);
        Class<?> local=Class.forName("com.oplus.findphone.client.device.devicedetail.a",false,loader);
        Class<?> state=Class.forName("wc.l",false,loader);
        Class<?> fragment=Class.forName("androidx.fragment.app.Fragment",false,loader);
        Class<?> helper=Class.forName("com.oplus.findphone.client.map.e",false,loader);
        application=app.getMethod("a").invoke(null);
        loading=field(state,"a").get(null);success=field(state,"g").get(null);unavailable=field(state,"e").get(null);
        submit=listAdapter.getMethod("d",List.class);itemAt=listAdapter.getMethod("c",int.class);
        nativeViewData=Class.forName("com.oplus.findphone.client.map.B",false,loader).getMethod("a",app,detailClass,local,boolean.class);
        liveValue=Class.forName("androidx.lifecycle.MutableLiveData",false,loader).getMethod("setValue",Object.class);
        navigateUp=helper.getMethod("d",fragment);
        try {
            hooks.add(module.hook(submit).setId("yj.finder.oplus.local_rows").intercept(chain->{
                if (!adapterClass.isInstance(chain.getThisObject())) return chain.proceed();
                Object adapter=chain.getThisObject();
                List<?> original=chain.getArg(0)==null?List.of():(List<?>)chain.getArg(0);
                List<Object> base=withoutLocal(original);lists.put(adapter,base);
                return chain.proceed(new Object[]{withLocal(base)});
            }));
            hooks.add(module.hook(listFragment.getMethod("onCreateView",LayoutInflater.class,ViewGroup.class,Bundle.class))
                    .setId("yj.finder.oplus.local_list").intercept(chain->{
                        Object result=chain.proceed();Object fragmentOwner=chain.getThisObject();
                        Object adapter=field(listFragment,"c").get(fragmentOwner);
                        if (adapter!=null) { owners.put(adapter,new WeakReference<>(fragmentOwner));
                            submit.invoke(adapter,lists.getOrDefault(adapter,List.of())); }
                        return result;
                    }));
            Class<?> holder=Class.forName("androidx.recyclerview.widget.RecyclerView$C",false,loader);
            hooks.add(module.hook(adapterClass.getMethod("onBindViewHolder",holder,int.class)).setId("yj.finder.oplus.local_header").intercept(chain->{
                Object row=itemAt.invoke(chain.getThisObject(),chain.getArg(1));
                if (!headerClass.isInstance(row)||field(headerClass,"a").getInt(row)!=LOCAL_HEADER) {
                    Object result=chain.proceed();
                    if(rowClass.isInstance(row)){
                        Object item=field(rowClass,"a").get(row);
                        if(item!=null&&OplusFinderSender.bridgeId((String)field(itemClass,"a").get(item))){
                            ImageView icon=(ImageView)field(chain.getArg(0).getClass(),"a").get(chain.getArg(0));
                            int fallback=client.context().getResources().getIdentifier("ic_device_list_default_bg","drawable","com.coloros.findmyphone");
                            icon.setImageResource(fallback);
                        }
                    }
                    return result;
                }
                Object viewHolder=chain.getArg(0);
                ((TextView)field(viewHolder.getClass(),"a").get(viewHolder)).setText("附近已配对的设备");
                View more=(View)field(viewHolder.getClass(),"b").get(viewHolder);
                more.setOnClickListener(null);more.setVisibility(View.GONE);return null;
            }));
            Class<?> click=Class.forName("com.oplus.findphone.client.map.N",false,loader);
            hooks.add(module.hook(click.getMethod("onClick",View.class)).setId("yj.finder.oplus.local_select").intercept(chain->{
                Object listener=chain.getThisObject();
                if (field(click,"a").getInt(listener)!=4) return chain.proceed();
                Object item=field(click,"c").get(listener);
                if (!itemClass.isInstance(item)||!OplusFinderSender.bridgeId((String)field(itemClass,"a").get(item)))return chain.proceed();
                Object adapter=field(click,"b").get(listener);
                WeakReference<Object> owner=owners.get(adapter);Object originalOwner=owner==null?null:owner.get();
                if (originalOwner!=null&&owns((String)field(itemClass,"a").get(item),null))
                    listFragment.getMethod("n",itemClass).invoke(originalOwner,item);
                return null;
            }));
            hooks.add(module.hook(model.getMethod("e")).setId("yj.finder.oplus.local_detail").intercept(chain->{
                Object vm=chain.getThisObject();String id=(String)field(model,"h").get(vm);
                if (!OplusFinderSender.bridgeId(id)) return chain.proceed();
                main.post(()->load(vm,id));return null; // A local yj id must never enter the account/cloud repository.
            }));
            hooks.add(module.hook(detailFragment.getMethod("onViewCreated",View.class,Bundle.class)).setId("yj.finder.oplus.detail_owner").intercept(chain->{
                Object result=chain.proceed();Object fragmentOwner=chain.getThisObject();Object vm=field(detailFragment,"m").get(fragmentOwner);
                Entry entry=details.get(vm);if(entry!=null)entry.fragment=new WeakReference<>(fragmentOwner);
                main.post(()->{Entry later=details.get(vm);if(later!=null)later.fragment=new WeakReference<>(fragmentOwner);});return result;
            }));
            hooks.add(module.hook(detailFragment.getMethod("onDestroyView")).setId("yj.finder.oplus.detail_release").intercept(chain->{
                Object vm=field(detailFragment,"m").get(chain.getThisObject());release(vm,true);return chain.proceed();
            }));
            hooks.add(module.hook(model.getMethod("onCleared")).setId("yj.finder.oplus.model_release").intercept(chain->{
                release(chain.getThisObject(),true);return chain.proceed();
            }));
            hooks.add(module.hook(nativeViewData).setId("yj.finder.oplus.local_functions").intercept(chain->{
                Object result=chain.proceed();Object data=chain.getArg(1);
                if(data!=null&&OplusFinderSender.bridgeId((String)detailClass.getMethod("getDeviceId").invoke(data))&&result!=null){
                    // This local capability supports ringing only. No account history, erase, lock, rename or cloud removal.
                    for(String name:new String[]{"h","i","j"})field(viewDataClass,name).set(result,new ArrayList<>());
                }
                return result;
            }));
            projection=client.addProjectionListener(ignored->refresh());
            main.postDelayed(poll,1000);
        } catch(Exception failure){close();throw failure;}
    }

    private List<Object> withoutLocal(List<?> original) throws Exception {
        List<Object> result=new ArrayList<>();
        for(Object row:original){
            if(headerClass.isInstance(row)&&field(headerClass,"a").getInt(row)==LOCAL_HEADER)continue;
            if(rowClass.isInstance(row)){
                Object item=field(rowClass,"a").get(row);
                if(item!=null&&OplusFinderSender.bridgeId((String)field(itemClass,"a").get(item)))continue;
            }
            result.add(row);
        }
        return result;
    }
    private List<Object> withLocal(List<Object> base) throws Exception {
        List<Object> result=new ArrayList<>(base);JSONObject p=client.projection();if(!ready(p))return result;
        Object header=headerClass.getConstructor().newInstance();field(headerClass,"a").setInt(header,LOCAL_HEADER);result.add(header);
        Object item=itemClass.getConstructor().newInstance();
        field(itemClass,"a").set(item,p.optString("id"));field(itemClass,"b").set(item,name(p));
        field(itemClass,"d").setInt(item,type(p));field(itemClass,"f").setInt(item,1);
        field(itemClass,"h").setInt(item,1);field(itemClass,"i").setInt(item,-1);field(itemClass,"j").set(item,LOCAL_LABEL);
        Object row=rowClass.getConstructor().newInstance();field(rowClass,"a").set(row,item);result.add(row);return result;
    }
    private void refresh() {
        if(closed)return;
        for(Entry entry:List.copyOf(details.values()))if(!owns(entry.id,entry.session)){
            if(entry.fragment!=null&&entry.fragment.get()!=null)try{navigateUp.invoke(null,entry.fragment.get());}catch(Exception ignored){}
            release(entry.model,false);
        }
        for(Object adapter:List.copyOf(lists.keySet()))try{submit.invoke(adapter,lists.get(adapter));}
        catch(Exception failure){android.util.Log.w("YueJieFinder","native_local_list_unavailable");}
    }
    private void load(Object model,String id) {
        if(closed)return;
        if(!owns(id,null)){setState(model,unavailable);return;}
        Entry entry=details.get(model);JSONObject p=client.projection();
        if(entry==null){entry=new Entry(model,id,p.optString("session_id"));details.put(model,entry);setState(model,loading);}
        requestState(entry);
    }
    private void requestState(Entry entry) {
        if(closed||entry.request!=null||details.get(entry.model)!=entry||!owns(entry.id,entry.session))return;
        String request=prefix+sequence.incrementAndGet();entry.request=request;
        try{client.request(entry.id,entry.session,"device_finder",request,new JSONObject().put("action","state"),reply->{
            if(!request.equals(entry.request))return;entry.request=null;
            if(details.get(entry.model)!=entry||!owns(entry.id,entry.session))return;
            JSONObject result=reply==null?null:reply.optJSONObject("result");JSONObject data=result==null?null:result.optJSONObject("data");
            if(reply==null||!"ok".equals(reply.optString("status"))||!request.equals(reply.optString("request_id"))
                    ||!entry.session.equals(reply.optString("session_id"))||result==null||!"device_finder".equals(result.optString("operation"))
                    ||data==null||!(data.opt("ringing") instanceof Boolean)){setState(entry.model,unavailable);return;}
            try{publish(entry,data.optBoolean("ringing"));entry.seen=true;}
            catch(Exception failure){setState(entry.model,unavailable);android.util.Log.w("YueJieFinder","native_local_detail_unavailable");}
        });}catch(Exception failure){entry.request=null;setState(entry.model,unavailable);}
    }
    private void publish(Entry entry,boolean ringing) throws Exception {
        JSONObject p=client.projection();if(!owns(entry.id,entry.session))return;
        Object data=detailClass.getConstructor().newInstance();
        set(data,"setDeviceId",String.class,entry.id);set(data,"setDeviceName",String.class,name(p));
        set(data,"setDeviceType",int.class,type(p));set(data,"setDeviceStatus",int.class,1);set(data,"setOnlineStatus",int.class,1);
        Object support=supportClass.getConstructor().newInstance();set(support,"setRing",boolean.class,true);set(support,"setStopRing",boolean.class,true);
        set(data,"setSupportInfo",supportClass,support);
        Map<Object,Integer> statuses=new HashMap<>();statuses.put(ringInstruction,ringing?4:-1);
        set(data,"setInstructionStatusMap",Map.class,statuses);
        Object helper=field(entry.model.getClass(),"d").get(entry.model);Object local=field(helper.getClass(),"a").get(helper);
        Field nativeRing=field(local.getClass(),"b");
        if(nativeRing.getInt(local)!=0)nativeRing.setInt(local,-1); // Preserve original UI's request-in-flight state until its callback.
        Object view=nativeViewData.invoke(null,application,data,local,false);
        liveValue.invoke(field(entry.model.getClass(),"b").get(entry.model),data);
        liveValue.invoke(field(entry.model.getClass(),"a").get(entry.model),view);setState(entry.model,success);
    }
    private void setState(Object model,Object state) {
        try{liveValue.invoke(field(model.getClass(),"c").get(model),state);}catch(Exception failure){android.util.Log.w("YueJieFinder","native_local_state_unavailable");}
    }
    private void release(Object model,boolean stop) {
        Entry entry=details.remove(model);if(entry==null)return;
        if(entry.request!=null)client.cancel(entry.session,entry.request,ignored->{});
        if(stop&&owns(entry.id,entry.session)&&details.values().stream().noneMatch(other->entry.id.equals(other.id)&&entry.session.equals(other.session)))
            try{client.request(entry.id,entry.session,"device_finder",prefix+sequence.incrementAndGet(),new JSONObject().put("action","stop"),ignored->{});}catch(Exception ignored){}
    }
    private boolean owns(String id,String session){JSONObject p=client.projection();return ready(p)&&id.equals(p.optString("id"))&&(session==null||session.equals(p.optString("session_id")));}
    private boolean ready(JSONObject p){if(closed||!allowed.getAsBoolean()||p==null||!OplusFinderSender.bridgeId(p.optString("id"))||p.optString("session_id").isEmpty()||type(p)<0)return false;
        JSONArray ops=p.optJSONArray("operations");if(ops!=null)for(int i=0;i<ops.length();i++)if("device_finder".equals(ops.optString(i)))return true;return false;}
    private static int type(JSONObject p){JSONObject real=p==null?null:p.optJSONObject("real");String kind=real==null?"":real.optString("kind");return "phone".equals(kind)?2:"tablet".equals(kind)?3:-1;}
    private static String name(JSONObject p){JSONObject real=p.optJSONObject("real");String name=real==null?"":real.optString("name").replaceAll("[\\p{Cntrl}]","");return name.isEmpty()?"附近已配对设备":name.substring(0,Math.min(name.length(),80));}
    private static void set(Object target,String method,Class<?> type,Object value)throws Exception{target.getClass().getMethod(method,type).invoke(target,value);}
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    @Override public void close(){if(closed)return;closed=true;main.removeCallbacks(poll);
        if(projection!=null){projection.close();projection=null;}
        for(Entry entry:List.copyOf(details.values()))release(entry.model,false);
        // Remove our display rows before unhooking; never mutate an account list or persist a peer.
        for(Object adapter:List.copyOf(lists.keySet()))try{submit.invoke(adapter,lists.get(adapter));}catch(Exception ignored){}
        lists.clear();owners.clear();for(HookHandle hook:hooks)try{hook.unhook();}catch(RuntimeException ignored){}hooks.clear();
    }
}
