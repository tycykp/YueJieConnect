import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Temporary shell probe. Original ClipData remains in memory and is never printed. */
public final class YjHtmlClipboard {
    private static String plain(String run, int leg) { return "YJ_HTML_" + run + "_" + leg; }
    private static String html(String run, int leg) { return "<b>" + plain(run, leg) + "</b><i>synthetic</i>"; }
    private static boolean matches(ClipData clip, String run, int leg) {
        return clip != null && clip.getItemCount() == 1 && clip.getDescription().hasMimeType("text/html")
            && plain(run,leg).contentEquals(clip.getItemAt(0).getText() == null ? "" : clip.getItemAt(0).getText())
            && html(run,leg).equals(clip.getItemAt(0).getHtmlText());
    }
    private static void emit(String value) { System.out.println(value); System.out.flush(); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[a-f0-9]{32}")) return;
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        if (context.checkSelfPermission("android.permission.READ_CLIPBOARD_IN_BACKGROUND") != PackageManager.PERMISSION_GRANTED) {
            emit("read_permission_unavailable"); return;
        }
        ClipboardManager manager = context.getSystemService(ClipboardManager.class);
        ClipData original = manager.getPrimaryClip();
        if (original == null && manager.hasPrimaryClip()) { emit("snapshot_denied"); return; }
        original = original == null ? null : new ClipData(original);
        emit("armed");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        for (String op; (op = input.readLine()) != null;) {
            if (op.equals("copy1") || op.equals("copy2")) {
                int leg = op.endsWith("1") ? 1 : 2;
                manager.setPrimaryClip(ClipData.newHtmlText("YJ synthetic HTML", plain(args[0],leg), html(args[0],leg)));
                emit("copied");
            } else if (op.equals("check1") || op.equals("check2")) {
                emit(matches(manager.getPrimaryClip(),args[0],op.endsWith("1")?1:2) ? "matches" : "not_matched");
            } else if (op.equals("finish_after_disconnect")) {
                ClipData current = manager.getPrimaryClip();
                if (matches(current,args[0],1) || matches(current,args[0],2)) {
                    if (original == null) manager.clearPrimaryClip(); else manager.setPrimaryClip(original);
                    emit("restored");
                } else emit("unchanged_or_new_content_preserved");
                return;
            }
        }
    }
}
