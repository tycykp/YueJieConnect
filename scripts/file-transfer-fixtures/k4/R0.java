package k4;
import android.view.*;
import com.xiaomi.mirror.sink.SinkView;
import com.xiaomi.mirror.message.DragMessage;
public class R0 {
    public SinkView a = new SinkView();
    public long b;
    public boolean accept=true;
    public boolean onDrag(View view,DragEvent event){
        if(event.getAction()==1&&event.getLocalState()!=this&&accept)b++;
        return accept;
    }
    public void a(DragMessage message,long session){}
    public void e(){a=null;b=0;}
}
