package com.xiaomi.mirror;
import android.content.ClipData;
import com.xiaomi.mirror.message.*;
import com.xiaomi.mirror.connection.G;
import io.github.qiangjian.connect.FileTransferHookHarness;
public final class d0 {
    public long b;
    public ClipData c;
    public int e;
    public String overridePeer;
    public boolean replaceSource;
    public ClipDataMessage last;
    private static long nextSession = 5;
    public void onDragStart(ClipData clip, int uid, int pid, int flags) {
        if (pid == android.os.Process.myPid()) return;
        b = nextSession++; c = clip; e = uid;
        DragMessage message = new DragMessage();
        message.action = 0; message.screenId = 7;
        last = V3.V.G().x0(uid, clip);
        if (replaceSource) last.fileList.get(0).url = "content://synthetic/foreign";
        message.clipData = last;
        FileTransferHookHarness.send(message, b, overridePeer == null ? G.J().J0(7) : overridePeer);
    }
    public void a(DragMessage message, long session) {}
    public void onDelegatePermissionReleased(java.util.List<?> uris) {}
}
