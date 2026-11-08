package q4;
import com.xiaomi.mirror.message.*;
public final class A {
    public static int starts, finishes;
    public static void P(Message message, long session, String peer) {
        if (message instanceof DragMessage drag) {
            if (drag.action == 0) starts++;
            if (drag.action == 6) finishes++;
        }
    }
}
