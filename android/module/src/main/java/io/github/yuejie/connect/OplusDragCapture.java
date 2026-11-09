package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.function.BiConsumer;

/** Receives the real Android DROP and its URI permission before source bytes are read.
 * Exists only during a selected screen gesture; no storage permission is widened. */
final class OplusDragCapture implements AutoCloseable {
    private final WindowManager windows;
    private final View view;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ClipData expected;
    private final Runnable ready,failed;
    private final BiConsumer<ClipData,Runnable> dropped;
    private boolean attached,requested,received,closed;
    private DragAndDropPermissions permission;
    OplusDragCapture(Context context,ClipData expected,Runnable ready,BiConsumer<ClipData,Runnable> dropped,Runnable failed){
        this.expected=expected;this.ready=ready;this.dropped=dropped;this.failed=failed;
        windows=context.getSystemService(WindowManager.class);view=new View(context);
        view.setOnDragListener(this::drag);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.setTitle("YueJie drag handoff");
        windows.addView(view,params);attached=true;
        main.postDelayed(()->{if(!received){failed.run();close();}},5000);
    }
    private boolean drag(View ignored,DragEvent event){
        if(closed)return false;
        switch(event.getAction()){
            case DragEvent.ACTION_DRAG_STARTED:return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                if(!requested){requested=true;main.post(ready);}return true;
            case DragEvent.ACTION_DROP:
                try{
                    ClipData clip=event.getClipData();if(!same(expected,clip))throw new IllegalStateException("source_clip_changed");
                    permission=(DragAndDropPermissions)DragAndDropPermissions.class.getMethod("obtain",DragEvent.class).invoke(null,event);
                    if(permission==null)throw new IllegalStateException("source_grant_missing");
                    Object result=DragAndDropPermissions.class.getMethod("takeTransient").invoke(permission);
                    if(Boolean.FALSE.equals(result))throw new IllegalStateException("source_grant_denied");
                    received=true;dropped.accept(new ClipData(clip),()->main.post(this::close));
                    main.post(this::hide);return true;
                }catch(Exception failure){failed.run();close();return false;}
            case DragEvent.ACTION_DRAG_ENDED:
                if(!received){failed.run();close();}return true;
            default:return true;
        }
    }
    private static boolean same(ClipData a,ClipData b){
        if(a==null||b==null||a.getItemCount()!=b.getItemCount()||a.getItemCount()<1||a.getItemCount()>4)return false;
        for(int i=0;i<a.getItemCount();i++)if(a.getItemAt(i).getUri()==null||!a.getItemAt(i).getUri().equals(b.getItemAt(i).getUri()))return false;
        return true;
    }
    private void hide(){if(attached){attached=false;windows.removeViewImmediate(view);}}
    public void close(){if(closed)return;closed=true;hide();if(permission!=null){permission.release();permission=null;}}
}
