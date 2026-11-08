package io.github.qiangjian.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import java.lang.reflect.Method;
import java.util.UUID;

/** Debug native sink check. The existing clipboard remains in this process and is restored. */
final class OplusClipboardSelfTest {
    private OplusClipboardSelfTest() {}
    static String run(Context context, ClassLoader loader) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return "clipboard_unavailable";
        final ClipData original;
        final OplusClipboardEndpoint endpoint;
        final Method restore;
        try {
            original = clipboard.getPrimaryClip();
            // hasPrimaryClip=true plus a denied read must never be mistaken for an empty clipboard.
            if (clipboard.hasPrimaryClip() && original == null) return "snapshot_denied";
            endpoint = new OplusClipboardEndpoint(loader);
            restore = Class.forName("com.oplus.linker.synergy.wisetransfer.clipboard.ClipBoardServiceHelper", false, loader)
                    .getMethod("copyCompleteDataToClip", ClipData.class, Context.class);
        } catch (ReflectiveOperationException | RuntimeException failure) { return "snapshot_or_sink_unavailable"; }
        String marker = "QJ一加原生剪贴板验证🙂-" + UUID.randomUUID();
        String result;
        try {
            endpoint.writeText(context, marker);
            result = marker.equals(endpoint.readText()) ? "native_write_readback_ok" : "native_readback_mismatch";
        } catch (ReflectiveOperationException | RuntimeException failure) { result = "native_write_readback_failed"; }
        try {
            ClipData now = clipboard.getPrimaryClip();
            if (plain(now, marker)) {
                if (original == null) clipboard.clearPrimaryClip();
                else restore.invoke(null, original, context);
                result += sameContent(original, clipboard.getPrimaryClip()) ? " restored" : " restore_unverified";
            } else {
                // Another application/user won the clipboard race; never overwrite their new data.
                result += " no_restore_needed_or_clipboard_changed";
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { result += " restore_failed"; }
        return result;
    }
    private static boolean plain(ClipData data, String expected) {
        return data != null && data.getItemCount() == 1 && data.getItemAt(0).getUri() == null
                && data.getItemAt(0).getIntent() == null && data.getItemAt(0).getHtmlText() == null
                && TextUtils.equals(data.getItemAt(0).getText(), expected);
    }
    private static boolean sameContent(ClipData left, ClipData right) {
        if (left == null || right == null) return left == right;
        if (left.getItemCount() != right.getItemCount()) return false;
        for (int i = 0; i < left.getItemCount(); i++) {
            ClipData.Item a = left.getItemAt(i), b = right.getItemAt(i);
            if (!TextUtils.equals(a.getText(), b.getText()) || !java.util.Objects.equals(a.getHtmlText(), b.getHtmlText())
                    || !java.util.Objects.equals(a.getUri(), b.getUri())) return false;
            if (a.getIntent() != null || b.getIntent() != null)
                if (a.getIntent() == null || b.getIntent() == null || !a.getIntent().filterEquals(b.getIntent())) return false;
        }
        return true;
    }
}
