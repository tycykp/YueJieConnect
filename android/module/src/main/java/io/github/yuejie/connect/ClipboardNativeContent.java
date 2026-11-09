package io.github.yuejie.connect;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Local import in the verified OEM process. No URI, Parcel or fabricated OEM peer crosses a wire.
 * Mirror has no HTML decoder: the exact authorized local conversion is adapted at z0, while T
 * still performs its real cache/counter/platform write. This is not native HTML wire support. */
final class ClipboardNativeContent implements AutoCloseable {
    private final Context context;
    private final ClipboardManager clipboard;
    private final int source;
    private Method copy, resource, receive;
    private Class<?> clipClass, messageClass;
    private HookHandle conversion;
    private Object importing;
    private ClipData local;
    private Thread importingThread;
    private boolean converted, closed;

    ClipboardNativeContent(XposedModule module, Context context, ClassLoader loader, int source, String hookId)
            throws ReflectiveOperationException {
        if (source != 1 && source != 2 || context == null
                || !(source == 1 ? "com.xiaomi.mirror" : "com.oplus.linker").equals(context.getPackageName()))
            throw new SecurityException("invalid_executor_context");
        this.context = context; this.source = source;
        clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) throw new IllegalStateException("native_clipboard_unavailable");
        if (source == 2) {
            // Unlike copyHtmlToClipBoard, this helper does not log the actual HTML/plain content.
            copy = Class.forName("com.oplus.linker.synergy.wisetransfer.clipboard.ClipBoardServiceHelper", false, loader)
                    .getMethod("copyCompleteDataToClip", ClipData.class, Context.class);
        } else {
            Class<?> manager = Class.forName("V3.V", false, loader);
            clipClass = Class.forName("com.xiaomi.mirror.message.ClipDataMessage", false, loader);
            messageClass = Class.forName("com.xiaomi.mirror.message.ClipboardMessage", false, loader);
            resource = manager.getMethod("G");
            receive = manager.getMethod("T", Class.forName("com.xiaomi.mirror.k0", false, loader), messageClass);
            Method decode = manager.getMethod("z0", clipClass, boolean.class);
            conversion = module.hook(decode).setId(hookId).intercept(chain -> {
                if (!closed && importing != null && Thread.currentThread() == importingThread
                        && chain.getArg(0) == importing && Boolean.TRUE.equals(chain.getArg(1))) {
                    converted = true;
                    return local;
                }
                return chain.proceed();
            });
            // T may inline z0; retain the actual conversion call for this scoped adapter.
            try { module.deoptimize(receive); }
            catch (RuntimeException failure) { conversion.unhook(); conversion = null; throw failure; }
        }
    }

    ClipData writeHtml(ClipboardRichContent.Html html) throws ReflectiveOperationException {
        ClipData actual = write(html.localClip());
        if (!html.matches(actual)) throw new IllegalStateException("clipboard_write_unverified");
        return actual;
    }

    /** Metadata-only native handle for an authenticated pending offer. No bytes or applied ACK.
     * Its exact local OEM-provider URI must already be registered with ClipboardUriPasteHooks. */
    ClipData stageLocalUri(Uri uri, String mime) throws ReflectiveOperationException {
        FileTransferUris.requireContent(uri);
        if (mime == null || !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
            throw new IllegalArgumentException("invalid_mime");
        ClipData desired = new ClipData(source == 1 ? "mirror" : "clipboard", new String[]{mime}, new ClipData.Item(uri));
        ClipData actual = write(desired);
        if (!matchesUri(actual, uri, mime)) throw new IllegalStateException("clipboard_write_unverified");
        return actual;
    }

    boolean hasLocalUri(Uri uri, String mime) {
        return !closed && matchesUri(clipboard.getPrimaryClip(), uri, mime);
    }

    static boolean matchesUri(ClipData clip, Uri uri, String mime) {
        if (clip == null || clip.getItemCount() != 1 || !clip.getDescription().hasMimeType(mime)) return false;
        ClipData.Item item = clip.getItemAt(0);
        return uri.equals(item.getUri()) && item.getText() == null && item.getHtmlText() == null && item.getIntent() == null;
    }

    private ClipData write(ClipData desired) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_requires_main");
        if (closed || importing != null) throw new IllegalStateException("clipboard_lifecycle");
        if (source == 2) invoke(copy, null, desired, context);
        else {
            Object manager = invoke(resource, null);
            if (manager == null) throw new IllegalStateException("native_clipboard_unavailable");
            Object clip = clipClass.getConstructor().newInstance();
            // An empty local envelope contains no private content or synthetic remote URL. The
            // same-thread exact-object conversion supplies the already materialized local data.
            clipClass.getField("clock").setLong(clip, ClipboardNativeClock.next());
            Object message = messageClass.getConstructor().newInstance();
            messageClass.getField("data").set(message, clip);
            importing = clip; local = desired; importingThread = Thread.currentThread(); converted = false;
            try {
                invoke(receive, manager, null, message);
                if (!converted) throw new IllegalStateException("native_conversion_not_observed");
            } finally { importing = null; local = null; importingThread = null; }
        }
        return clipboard.getPrimaryClip();
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof SecurityException denied) throw denied;
            throw error;
        }
    }
    @Override public void close() {
        closed = true;
        if (conversion != null) { conversion.unhook(); conversion = null; }
    }
}
