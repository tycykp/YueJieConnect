package io.github.yuejie.connect;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import io.github.libxposed.api.XposedModule;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.*;

/** A drag's URI is usable only through Android's real READ grant. No wire URI is accepted. */
final class ScreenDragProvider {
    interface Access {
        boolean current(FileTransferNegotiation.Prepared item);
        CompletableFuture<Uri> receive(FileTransferNegotiation.Prepared item);
    }
    private record Slot(Uri uri,FileTransferNegotiation.Prepared item){}
    private final Map<Uri,Slot> slots=new ConcurrentHashMap<>();
    private final Context context;
    private final Access access;
    private final Semaphore readers=new Semaphore(4);
    private static final String AUTH="com.xiaomi.mirror.remoteprovider",PATH="yj-screen-drag";
    ScreenDragProvider(XposedModule module,Context context,ClassLoader loader,Access access)throws Exception{
        this.context=context;this.access=access;
        Class<?> type=Class.forName("com.xiaomi.mirror.provider.RemoteProvider",false,loader);
        for(boolean cancellation:new boolean[]{false,true}){
            var method=cancellation?type.getMethod("openFile",Uri.class,String.class,CancellationSignal.class):type.getMethod("openFile",Uri.class,String.class);
            module.hook(method).setId("yj.drag.provider.open."+cancellation).intercept(chain->{
                Uri uri=(Uri)chain.getArg(0);if(!owned(uri))return chain.proceed();
                return open(uri,(String)chain.getArg(1),cancellation?(CancellationSignal)chain.getArg(2):null);
            });
            var query=cancellation?type.getMethod("query",Uri.class,String[].class,String.class,String[].class,String.class,CancellationSignal.class)
                :type.getMethod("query",Uri.class,String[].class,String.class,String[].class,String.class);
            module.hook(query).setId("yj.drag.provider.query."+cancellation).intercept(chain->{
                Uri uri=(Uri)chain.getArg(0);if(!owned(uri))return chain.proceed();
                Slot slot=slots.get(uri);String[] columns=(String[])chain.getArg(1);
                if(columns==null)columns=new String[]{"_display_name","_size"};
                if(columns.length>64)throw denied();
                MatrixCursor cursor=new MatrixCursor(columns);
                if(slot!=null&&access.current(slot.item)){
                    var offer=slot.item.metadata().offer();Object[] values=new Object[columns.length];
                    for(int i=0;i<columns.length;i++)values[i]=switch(columns[i]){case "_display_name"->offer.name();case "_size"->offer.size();case "mime_type"->offer.mime();default->null;};
                    cursor.addRow(values);
                }return cursor;
            });
        }
        module.hook(type.getMethod("getType",Uri.class)).setId("yj.drag.provider.mime").intercept(chain->{
            Uri uri=(Uri)chain.getArg(0);if(!owned(uri))return chain.proceed();Slot slot=slots.get(uri);
            return slot!=null&&access.current(slot.item)?slot.item.metadata().offer().mime():null;
        });
    }
    Uri stage(FileTransferNegotiation.Prepared item){
        Uri uri=new Uri.Builder().scheme("content").authority(AUTH).appendPath(PATH).appendPath(item.metadata().nativeOwnerToken()).build();
        slots.put(uri,new Slot(uri,item));return uri;
    }
    void revoke(FileTransferNegotiation.Prepared item){slots.entrySet().removeIf(e->{if(e.getValue().item!=item)return false;
        context.revokeUriPermission(e.getKey(),Intent.FLAG_GRANT_READ_URI_PERMISSION);return true;});}
    private static boolean owned(Uri uri){return uri!=null&&AUTH.equals(uri.getAuthority())&&!uri.getPathSegments().isEmpty()&&PATH.equals(uri.getPathSegments().get(0));}
    private ParcelFileDescriptor open(Uri uri,String mode,CancellationSignal signal)throws Exception{
        Slot slot=slots.get(uri);int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();
        if(!"r".equals(mode)||slot==null||Looper.myLooper()==Looper.getMainLooper()||!access.current(slot.item)
            ||context.checkUriPermission(uri,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED||!readers.tryAcquire())throw denied();
        try{
            CompletableFuture<Uri> data;long identity=Binder.clearCallingIdentity();
            try{data=access.receive(slot.item);}finally{Binder.restoreCallingIdentity(identity);}
            long deadline=SystemClock.elapsedRealtime()+120000;
            while(access.current(slot.item)&&SystemClock.elapsedRealtime()<deadline&&(signal==null||!signal.isCanceled())){
                try{
                    Uri local=data.get(100,TimeUnit.MILLISECONDS);
                    if(context.checkUriPermission(uri,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw denied();
                    identity=Binder.clearCallingIdentity();
                    try{ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(local,"r",signal);if(fd==null)throw denied();
                        if(!access.current(slot.item)||(signal!=null&&signal.isCanceled())
                            ||context.checkUriPermission(uri,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED){fd.close();throw denied();}
                        ScreenCastLog.i("YueJieFiles","drag_provider_open transfer="+slot.item.key().transfer());return fd;
                    }finally{Binder.restoreCallingIdentity(identity);}
                }catch(TimeoutException waiting){}
            }throw denied();
        }finally{readers.release();}
    }
    private static FileNotFoundException denied(){return new FileNotFoundException("drag_file_unavailable");}
}
