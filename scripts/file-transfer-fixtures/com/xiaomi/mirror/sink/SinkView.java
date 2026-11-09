package com.xiaomi.mirror.sink;
import android.view.View;
import com.xiaomi.mirror.message.Message;
public class SinkView extends View {
    public int messages;
    public String getDeviceId(){return "native-synthetic-pad";}
    public void m1(Message message,long session){messages++;}
}
