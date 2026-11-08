import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.MotionEvent;
import android.view.InputDevice;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlSerializer;

/** Temporary shell UI observer. No idle wait, notification APIs, PendingIntents or app IPC. */
public final class NotificationSyncUiProbe extends com.android.uiautomator.testrunner.UiAutomatorTestCase {
    private static boolean title(AccessibilityNodeInfo n, String target) {
        if (target.contentEquals(n.getText() == null ? "" : n.getText())) return true;
        for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null&&title(c,target))return true; }
        return false;
    }
    private static String safe(CharSequence value, String target) {
        String s=value==null?"":value.toString();
        if(s.startsWith("展开")||s.startsWith("Expand "))return "展开";
        if(s.startsWith("收起")||s.startsWith("Collapse "))return "收起";
        return List.of(target,"测试动作","测试回复","在来源设备移除","展开","Expand","收起","Collapse","人工回复","QJ-test-reply","发送","Send").contains(s)?s:"";
    }
    private static void write(XmlSerializer xml, AccessibilityNodeInfo n, String target) throws Exception {
        xml.startTag(null,"node");
        Rect r=new Rect();n.getBoundsInScreen(r);
        xml.attribute(null,"text",safe(n.getText(),target));
        xml.attribute(null,"content-desc",safe(n.getContentDescription(),target));
        xml.attribute(null,"resource-id",n.getViewIdResourceName()==null?"":n.getViewIdResourceName());
        xml.attribute(null,"class",n.getClassName()==null?"":n.getClassName().toString());
        xml.attribute(null,"bounds","["+r.left+","+r.top+"]["+r.right+","+r.bottom+"]");
        xml.attribute(null,"clickable",Boolean.toString(n.isClickable()));
        xml.attribute(null,"focused",Boolean.toString(n.isFocused()));
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)write(xml,c,target);}
        xml.endTag(null,"node");
    }
    private static AccessibilityNodeInfo row(AccessibilityNodeInfo n, String target) {
        if(!title(n,target))return null;
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo c=n.getChild(i);
            if(c!=null&&title(c,target)){
                AccessibilityNodeInfo selected=row(c,target);
                if(selected!=null)return selected;
            }
        }
        String id=n.getViewIdResourceName();
        return id!=null&&id.endsWith("expandableNotificationRow")&&controls(n)?n:null;
    }
    private static boolean controls(AccessibilityNodeInfo n){
        String id=n.getViewIdResourceName();
        if((id!=null&&(id.contains("expand_button")||id.contains("remote_input")))
                ||"测试动作".contentEquals(n.getText()==null?"":n.getText())||"测试回复".contentEquals(n.getText()==null?"":n.getText()))return true;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null&&controls(c))return true;}
        return false;
    }
    private static void inputs(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> result){
        if(n.isFocused()&&"android.widget.EditText".contentEquals(n.getClassName()))result.add(n);
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)inputs(c,result);}
    }
    private static AccessibilityNodeInfo findTitle(AccessibilityNodeInfo n,String target){
        if(target.contentEquals(n.getText()==null?"":n.getText()))return n;
        for(int i=0;i<n.getChildCount();i++){var c=n.getChild(i);if(c!=null){var result=findTitle(c,target);if(result!=null)return result;}}return null;
    }
    private static void expandCard(UiAutomation ui,AccessibilityNodeInfo title){
        Rect bounds=new Rect();title.getBoundsInScreen(bounds);float x=bounds.centerX(),y=bounds.centerY();
        var properties=new MotionEvent.PointerProperties[2];var coords=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=x+(i==0?-24:24);coords[i].y=y+(i==0?-20:20);coords[i].pressure=1;coords[i].size=1;}
        long down=android.os.SystemClock.uptimeMillis();boolean finished=false;
        try{
            inject(ui,down,MotionEvent.ACTION_DOWN,1,properties,coords);
            inject(ui,down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords);
            for(int step=1;step<=12;step++){coords[0].y=y-20-step*2;coords[1].y=y+20+step*18;inject(ui,down,MotionEvent.ACTION_MOVE,2,properties,coords);android.os.SystemClock.sleep(25);}
            inject(ui,down,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords);
            inject(ui,down,MotionEvent.ACTION_UP,1,properties,coords);finished=true;
        }finally{if(!finished)try{inject(ui,down,MotionEvent.ACTION_CANCEL,2,properties,coords);}catch(RuntimeException ignored){}}
    }
    private static void inject(UiAutomation ui,long down,int action,int count,MotionEvent.PointerProperties[] properties,MotionEvent.PointerCoords[] coords){
        MotionEvent event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{if(!ui.injectInputEvent(event,true))throw new IllegalStateException("synthetic_expand_input_rejected");}finally{event.recycle();}
    }
    private static Object invoke(Object owner,String name)throws Exception{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{
            var method=type.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(owner);
        }catch(NoSuchMethodException absent){}
        throw new NoSuchMethodException(name);
    }
    private static UiAutomation automation(Object bridge)throws Exception{
        for(Class<?> type=bridge.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields())
            if(UiAutomation.class.isAssignableFrom(field.getType())){
                field.setAccessible(true);return (UiAutomation)field.get(bridge);
            }
        throw new NoSuchFieldException("platform_ui_automation");
    }
    public void testSynthetic() throws Exception {
            String id=getParams().getString("id",""),operation=getParams().getString("operation","");
            if(!id.matches("90000[0-7]")||!List.of("smoke","snapshot","reply-text","expand-card").contains(operation))throw new IllegalArgumentException("invalid_synthetic_ui_request");
            String target="强兼合成通知 "+id;
            // The platform's own UiAutomator runner owns connection and cleanup. Do not create
            // a second UiAutomation connection from an untracked app_process.
            UiAutomation ui=automation(invoke(getUiDevice(),"getAutomatorBridge"));
            android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();
            info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    |android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            for(int attempt=0;root==null&&attempt<15;attempt++){Thread.sleep(100);root=ui.getRootInActiveWindow();}
            if("smoke".equals(operation)){
                int windows=ui.getWindows().size(),roots=0;for(var window:ui.getWindows())if(window.getRoot()!=null)roots++;
                System.out.println("qj_ui_runner_ready:"+(root!=null)+",windows="+windows+",roots="+roots);return;
            }
            if(root==null||!title(root,target))for(var window:ui.getWindows()){
                AccessibilityNodeInfo candidate=window.getRoot();if(candidate!=null&&title(candidate,target)){root=candidate;break;}
            }
            if(root==null||!title(root,target))throw new IllegalStateException("synthetic_card_unavailable");
            if("expand-card".equals(operation)){expandCard(ui,findTitle(root,target));System.out.println("native_expand_gesture_sent");return;}
            AccessibilityNodeInfo selected=row(root,target);
            // OEMs need not expose AOSP's row resource ID. A snapshot may include the
            // sanitized window to discover its real container; input still requires a row.
            if(selected==null){if("reply-text".equals(operation))throw new IllegalStateException("synthetic_row_unavailable");selected=root;}
            if("reply-text".equals(operation)){
                List<AccessibilityNodeInfo> fields=new ArrayList<>();inputs(selected,fields);
                if(fields.size()!=1)throw new IllegalStateException("synthetic_reply_field_unavailable");
                Bundle input=new Bundle();input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"人工回复");
                if(!fields.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,input))throw new IllegalStateException("synthetic_reply_input_rejected");
                System.out.println("reply_text_set");
            }else{
                StringWriter out=new StringWriter();XmlSerializer xml=Xml.newSerializer();xml.setOutput(out);
                xml.startDocument("UTF-8",true);xml.startTag(null,"hierarchy");write(xml,selected,target);xml.endTag(null,"hierarchy");xml.endDocument();
                System.out.println(out);
            }
    }
}
