package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs in com.oplus.linker after caller/session/operation authorization by the bridge. */
final class OplusClipboardEndpoint {
    record Snapshot(String text, long timestamp) {}
    private static final String BASE = "com.oplus.linker.synergy.wisetransfer.clipboard.";
    private final Method copy, read, clipboardSwitch;

    OplusClipboardEndpoint(ClassLoader loader) throws ReflectiveOperationException {
        copy = Class.forName(BASE + "ClipBoardServiceHelper", false, loader)
                .getMethod("copyPureTextToClipBoard", byte[].class, Context.class);
        read = Class.forName(BASE + "util.ClipBoardUtil", false, loader).getMethod("getSystemClipData");
        clipboardSwitch = Class.forName("com.oplus.linker.synergy.common.utils.ConnectPCUtil", false, loader)
                .getMethod("getClipboardSwitchState", Context.class);
    }

    boolean enabled(Context context) {
        try { return context != null && Boolean.TRUE.equals(invoke(clipboardSwitch, null, context)); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    String readText() throws ReflectiveOperationException {
        return snapshot().text();
    }

    Snapshot snapshot() throws ReflectiveOperationException {
        ClipData clip = (ClipData) invoke(read, null);
        if (clip == null || clip.getItemCount() != 1) throw new IllegalStateException("clipboard_unavailable");
        if (!clip.getDescription().hasMimeType("text/plain")) throw new IllegalArgumentException("unsupported_clipboard_format");
        ClipData.Item item = clip.getItemAt(0);
        if (item.getHtmlText() != null || item.getUri() != null || item.getIntent() != null || item.getText() == null)
            throw new IllegalArgumentException("unsupported_clipboard_format");
        byte[] bytes = OplusClipboardCodec.encode(item.getText().toString());
        return new Snapshot(OplusClipboardCodec.decode(0, 0, bytes), clip.getDescription().getTimestamp());
    }

    void writeText(Context context, String text) throws ReflectiveOperationException {
        if (context == null || !"com.oplus.linker".equals(context.getPackageName()))
            throw new SecurityException("invalid_executor_context");
        byte[] bytes = OplusClipboardCodec.encode(text);
        ClipboardManager manager = context.getSystemService(ClipboardManager.class);
        if (manager == null) throw new IllegalStateException("clipboard_unavailable");
        // This is the same OEM sink used by ClipBoardServiceManager.processPureTextClipData;
        // it also updates the OEM loop-suppression state before writing the actual clipboard.
        invoke(copy, null, bytes, context);
        ClipData actual = manager.getPrimaryClip();
        if (actual == null || actual.getItemCount() != 1 || actual.getItemAt(0).getText() == null
                || !Arrays.equals(bytes, OplusClipboardCodec.encode(actual.getItemAt(0).getText().toString())))
            throw new IllegalStateException("clipboard_write_unverified");
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof SecurityException denied) throw denied;
            throw error;
        }
    }
}
