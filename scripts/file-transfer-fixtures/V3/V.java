package V3;
import android.content.*;
import android.net.Uri;
import com.xiaomi.mirror.message.ClipDataMessage;
public final class V {
    private static final V INSTANCE = new V();
    public static V G() { return INSTANCE; }
    public Uri M(String url) { return Uri.parse(url); }
    public ClipDataMessage x0(int uid, ClipData original) {
        ClipDataMessage result = new ClipDataMessage();
        for (int i = 0; i < original.getItemCount(); i++) {
            Uri uri = original.getItemAt(i).getUri();
            ClipDataMessage.FileInfo file = new ClipDataMessage.FileInfo();
            file.name = "synthetic-" + i + ".bin";
            file.url = uri.toString(); file.size = ContentResolver.DATA.get(uri).length;
            result.fileList.add(file);
        }
        return result;
    }
}
