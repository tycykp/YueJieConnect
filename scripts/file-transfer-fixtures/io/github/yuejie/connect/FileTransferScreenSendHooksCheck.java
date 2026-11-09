package io.github.yuejie.connect;
import android.content.ClipData;
import android.net.Uri;
import android.view.*;
import com.xiaomi.mirror.message.*;
import com.xiaomi.mirror.sink.SinkView;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class FileTransferScreenSendHooksCheck {
    XiaomiScreenFileSendHooks hooks;
    k4.R0 owner=new k4.R0();
    ClipData clip=new ClipData(Uri.parse("content://fixture/local"));
    List<String> events=new ArrayList<>();
    boolean current=true;
    @Before public void setup()throws Exception{
        hooks=new XiaomiScreenFileSendHooks(FileTransferHookHarness.module(),getClass().getClassLoader(),new XiaomiScreenFileSendHooks.Routes(){
            public boolean current(Object view){return current&&view==owner.a;}
            public void started(Object view,long session,int screen,ClipData original,Object translated){
                assertSame(clip,original);assertNotNull(translated);events.add("start:"+session);
            }
            public void dropped(Object view,long session){events.add("drop:"+session);}
            public void cancelled(Object view,long session){events.add("cancel:"+session);}
        });
    }
    void event(int action,Object local)throws Exception{
        FileTransferHookHarness.call(k4.R0.class.getMethod("onDrag",View.class,DragEvent.class),owner,owner.a,new DragEvent(action,clip,local));
    }
    void message(int action,long session)throws Exception{
        DragMessage msg=new DragMessage();msg.action=action;msg.clipData=new ClipDataMessage();
        FileTransferHookHarness.call(SinkView.class.getMethod("m1",Message.class,long.class),owner.a,msg,session);
    }
    @After public void close(){hooks.close();assertEquals(0,FileTransferHookHarness.hooks());}
    @Test public void asynchronousConversionRetainsOriginalClipAndFinishDoesNotCancelDrop()throws Exception{
        event(1,null);assertTrue(events.isEmpty());message(0,owner.b);message(4,owner.b);message(6,owner.b);event(4,null);
        assertEquals(List.of("start:1","drop:1"),events);assertEquals(3,owner.a.messages);
    }
    @Test public void remoteDragDoesNotEchoAsNewSource()throws Exception{
        event(1,owner);message(0,5);assertTrue(events.isEmpty());
    }
    @Test public void lateConversionCannotAttachToNextDrag()throws Exception{
        event(1,null);long old=owner.b;event(1,null);events.clear();message(0,old);assertTrue(events.isEmpty());
        message(0,owner.b);assertEquals(List.of("start:2"),events);
    }
    @Test public void rejectedAndUnboundSourcesNeverStart()throws Exception{
        owner.accept=false;event(1,null);events.clear();message(0,1);assertTrue(events.isEmpty());
        owner.accept=true;current=false;event(1,null);message(0,owner.b);assertTrue(events.isEmpty());
    }
    @Test public void cancelledDragReleasesOnceAndCannotBeResurrected()throws Exception{
        event(1,null);message(0,owner.b);message(5,owner.b);message(0,owner.b);event(4,null);
        assertEquals(List.of("start:1","cancel:1"),events);
    }
    @Test public void nativePermissionReleaseMustMatchSession()throws Exception{
        event(1,null);message(0,owner.b);DragMessage release=new DragMessage();release.action=9;
        var method=k4.R0.class.getMethod("a",DragMessage.class,long.class);
        FileTransferHookHarness.call(method,owner,release,99L);assertEquals(1,events.size());
        FileTransferHookHarness.call(method,owner,release,owner.b);assertEquals(List.of("start:1","cancel:1"),events);
    }
    @Test public void sessionLossAndWindowCloseCancelSources()throws Exception{
        event(1,null);message(0,owner.b);current=false;hooks.sessionChanged();assertEquals(List.of("start:1","cancel:1"),events);
        current=true;event(1,null);message(0,owner.b);FileTransferHookHarness.call(k4.R0.class.getMethod("e"),owner);
        assertEquals("cancel:2",events.get(events.size()-1));
    }
    @Test public void dropBeforeAsyncConversionIsRetainedUntilSourceIsReady()throws Exception{
        event(1,null);message(4,owner.b);message(6,owner.b);event(4,null);assertTrue(events.isEmpty());
        message(0,owner.b);message(0,owner.b);message(4,owner.b);
        assertEquals(List.of("start:1","drop:1"),events);
    }
    @Test public void unrelatedWindowMessagesKeepOriginalNativePath()throws Exception{
        event(1,null);
        FileTransferHookHarness.call(SinkView.class.getMethod("m1",Message.class,long.class),owner.a,new Message(),owner.b);
        assertEquals(1,owner.a.messages);assertTrue(events.isEmpty());
    }
    @Test public void nativeSessionResetCannotResurrectLateConversion()throws Exception{
        event(1,null);message(4,owner.b);owner.b=0;hooks.sessionChanged();events.clear();
        message(0,1);assertTrue(events.isEmpty());
    }
    @Test public void repeatedStartFromTouchChildKeepsOriginalSource()throws Exception{
        event(1,null);long session=owner.b;
        FileTransferHookHarness.call(k4.R0.class.getMethod("onDrag",View.class,DragEvent.class),owner,new View(),new DragEvent(1,clip,null));
        assertEquals(session,owner.b);message(0,session);message(4,session);event(4,null);
        assertEquals(List.of("start:1","drop:1"),events);
    }
    @Test public void nextCompletedGestureOfSameFileGetsNewSession()throws Exception{
        event(1,null);message(0,owner.b);message(4,owner.b);event(4,null);
        FileTransferHookHarness.call(k4.R0.class.getMethod("onDrag",View.class,DragEvent.class),owner,new View(),new DragEvent(1,clip,null));
        assertEquals(2,owner.b);message(0,owner.b);
        assertEquals("start:2",events.get(events.size()-1));
    }
}
