package io.github.qiangjian.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Mirror's actual remote clipboard path. Construct only with the Mirror process ClassLoader. */
final class XiaomiClipboardEndpoint {
    private final Method resource, remote, mirror, clipboard, byteString, bytes, size;
    private final Constructor<?> messageConstructor, clipConstructor, dataConstructor;
    private final Field messageData, dataList, fileList, content, type, url, textType, clock;
    private final Class<?> messageType;

    XiaomiClipboardEndpoint(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("V3.V", false, loader);
        Class<?> peer = Class.forName("com.xiaomi.mirror.k0", false, loader);
        Class<?> app = Class.forName("com.xiaomi.mirror.Mirror", false, loader);
        messageType = Class.forName("com.xiaomi.mirror.message.ClipboardMessage", false, loader);
        Class<?> clip = Class.forName("com.xiaomi.mirror.message.ClipDataMessage", false, loader);
        Class<?> data = Class.forName("com.xiaomi.mirror.message.ClipDataMessage$Data", false, loader);
        resource = manager.getMethod("G");
        remote = manager.getMethod("T", peer, messageType);
        mirror = app.getMethod("C");
        clipboard = app.getMethod("t");
        messageConstructor = messageType.getConstructor();
        clipConstructor = clip.getConstructor();
        dataConstructor = data.getConstructor();
        messageData = messageType.getField("data");
        dataList = clip.getField("dataList"); fileList = clip.getField("fileList");
        clock = clip.getField("clock");
        content = data.getField("content"); type = data.getField("type");
        url = data.getField("url"); textType = data.getField("textType");
        byteString = content.getType().getMethod("m", String.class, Charset.class);
        bytes = content.getType().getMethod("C");
        size = content.getType().getMethod("size");
    }

    boolean isClipboardMessage(Object value) { return messageType.isInstance(value); }

    boolean available() {
        try {
            Object app = invoke(mirror, null);
            return app != null && invoke(resource, null) != null && invoke(clipboard, app) != null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    String readMessage(Object message) throws ReflectiveOperationException {
        if (!isClipboardMessage(message)) throw new IllegalArgumentException("unsupported_clipboard_format");
        Object clip = messageData.get(message);
        if (clip == null || !(fileList.get(clip) instanceof List<?> files) || !files.isEmpty()
                || !(dataList.get(clip) instanceof List<?> items) || items.size() != 1)
            throw new IllegalArgumentException("unsupported_clipboard_format");
        Object item = items.get(0);
        if (item == null || type.getInt(item) != 0 || (url.get(item) != null && !"".equals(url.get(item))))
            throw new IllegalArgumentException("unsupported_clipboard_format");
        Object nativeBytes = content.get(item);
        if (nativeBytes == null) throw new IllegalArgumentException("invalid_payload");
        int length = (int) invoke(size, nativeBytes);
        if (length < 1 || length > OplusClipboardCodec.MAX_BYTES) throw new IllegalArgumentException("invalid_payload");
        return OplusClipboardCodec.decode(0, 0, (byte[]) invoke(bytes, nativeBytes));
    }

    String readSourceMessage(Object message) throws ReflectiveOperationException {
        String text = readMessage(message);
        Object app = invoke(mirror, null);
        ClipboardManager manager = app == null ? null : (ClipboardManager) invoke(clipboard, app);
        ClipData actual = manager == null ? null : manager.getPrimaryClip();
        // A0 silently discards HTML/URI and extra items before broadcastAll. Do not advertise
        // that lossy native conversion as this module's plain-text capability.
        if (actual == null || actual.getItemCount() != 1 || !actual.getDescription().hasMimeType("text/plain")
                || actual.getItemAt(0).getHtmlText() != null || actual.getItemAt(0).getUri() != null
                || actual.getItemAt(0).getIntent() != null || actual.getItemAt(0).getText() == null)
            throw new IllegalArgumentException("unsupported_clipboard_format");
        if (!text.contentEquals(actual.getItemAt(0).getText())) throw new IllegalStateException("clipboard_source_changed");
        return text;
    }

    void writeText(Context context, String value) throws ReflectiveOperationException {
        if (context == null || !"com.xiaomi.mirror".equals(context.getPackageName()))
            throw new SecurityException("invalid_executor_context");
        byte[] encoded = OplusClipboardCodec.encode(value);
        Object manager = invoke(resource, null);
        Object app = invoke(mirror, null);
        if (manager == null || app == null) throw new IllegalStateException("native_clipboard_unavailable");
        ClipboardManager sink = (ClipboardManager) invoke(clipboard, app);
        if (sink == null) throw new IllegalStateException("native_clipboard_unavailable");
        Object data = dataConstructor.newInstance();
        type.setInt(data, 0); textType.set(data, "UNKNOWN");
        content.set(data, invoke(byteString, null, value, StandardCharsets.UTF_8));
        Object clip = clipConstructor.newInstance();
        dataList.set(clip, new java.util.ArrayList<>(List.of(data)));
        // Native cache rejects <= its previous clock. Two queued writes may run in the same
        // millisecond, or follow A0 at exactly this uptime; give each local native message a tick.
        clock.setLong(clip, ClipboardNativeClock.next());
        Object message = messageConstructor.newInstance();
        messageData.set(message, clip);
        // O2.k also uses null peer. No fabricated Xiaomi transport/session identity is needed:
        // the authenticated Noise session is checked by the receiver before this invocation.
        // T updates Mirror cache and its receive counter before the real clipboard write.
        invoke(remote, manager, null, message);
        ClipData actual = sink.getPrimaryClip();
        if (actual == null || actual.getItemCount() != 1 || actual.getItemAt(0).getText() == null
                || !Arrays.equals(encoded, OplusClipboardCodec.encode(actual.getItemAt(0).getText().toString())))
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
