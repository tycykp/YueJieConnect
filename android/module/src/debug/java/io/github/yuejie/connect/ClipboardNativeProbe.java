package io.github.yuejie.connect;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shell/DUMP-only synthetic clipboard stimulus. No direct bridge request or fabricated receipt. */
final class ClipboardNativeProbe implements ClipboardBridge.Probe {
    private static final String ACTION = "io.github.yuejie.connect.CLIPBOARD_NATIVE_PROBE";
    private static final String URI_ACTION = "io.github.yuejie.connect.CLIPBOARD_URI_NATIVE_PROBE";
    private static final String TAG = "YJClipboardProbe";
    private final Context context;
    private final ClassLoader loader;
    private final BridgeClient client;
    private final int source;
    private final Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>> lastTransfer;
    private final Supplier<CompletableFuture<ClipboardBridge.RichReceipt>> richTransfers;
    private final ClipboardManager clipboard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Application application;
    private WeakReference<Activity> focused = new WeakReference<>(null);
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { command(intent); }
    };
    private String run, session;
    private ClipData original, restored;
    private boolean armed, restoring, guardRestored, closed;
    private boolean htmlMode;
    private boolean uriMode;
    private boolean uriGuard, uriRegistered;
    private Supplier<? extends CompletableFuture<?>> uriTransfers;
    private final Map<Uri, Integer> testUris = new HashMap<>();
    private final Map<Uri, File> sourceFiles = new HashMap<>();
    private final boolean[] uriApplied = new boolean[3], uriConsumed = new boolean[3];
    private long generation;
    private int pending;
    private Method oplusRestore, mirrorGet, mirrorReceive;
    private Class<?> mirrorClip, mirrorMessage;
    private Object restoreMessage;
    private Thread restoreThread;
    private boolean restoreConverted;
    private HookHandle mirrorRestoreHook;
    private final Runnable expiry = () -> finish("expired");
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity activity) { focused = new WeakReference<>(activity); }
        @Override public void onActivityPaused(Activity activity) { if (focused.get() == activity) focused.clear(); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) { if (focused.get() == activity) focused.clear(); }
    };

    static ClipboardBridge.Probe install(Context context, XposedModule module, ClassLoader loader,
            BridgeClient client, int source, Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>> transfers)
            throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        return install(context, module, loader, client, source, transfers, () -> null);
    }

    static ClipboardBridge.Probe install(Context context, XposedModule module, ClassLoader loader,
            BridgeClient client, int source, Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>> transfers,
            Supplier<CompletableFuture<ClipboardBridge.RichReceipt>> richTransfers)
            throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        return install(context, module, loader, client, source, transfers, richTransfers, false);
    }
    static ClipboardBridge.Probe installUri(Context context, XposedModule module, ClassLoader loader,
            BridgeClient client, int source) throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        return install(context, module, loader, client, source, () -> null, () -> null, true);
    }
    private static ClipboardBridge.Probe install(Context context, XposedModule module, ClassLoader loader,
            BridgeClient client, int source, Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>> transfers,
            Supplier<CompletableFuture<ClipboardBridge.RichReceipt>> richTransfers, boolean uriOnly)
            throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo("io.github.yuejie.connect", 0);
        if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        ClipboardNativeProbe probe = new ClipboardNativeProbe(context, loader, client, source, transfers, richTransfers);
        try {
            probe.prepareRestore(module);
            context.registerReceiver(probe.receiver, new IntentFilter(uriOnly ? URI_ACTION : ACTION), android.Manifest.permission.DUMP,
                    probe.main, Context.RECEIVER_EXPORTED);
            probe.uriRegistered = uriOnly;
            probe.application.registerActivityLifecycleCallbacks(probe.lifecycle);
            probe.event("installed", 0, "ready");
            return probe;
        } catch (ReflectiveOperationException | RuntimeException error) {
            probe.close(); throw error;
        }
    }

    private ClipboardNativeProbe(Context context, ClassLoader loader, BridgeClient client, int source,
            Supplier<CompletableFuture<OplusClipboardAdapter.Receipt>> transfers,
            Supplier<CompletableFuture<ClipboardBridge.RichReceipt>> richTransfers) {
        this.context = context; this.loader = loader; this.client = client; this.source = source; this.lastTransfer = transfers;
        this.richTransfers = richTransfers;
        clipboard = context.getSystemService(ClipboardManager.class);
        application = (Application) context.getApplicationContext();
        if (clipboard == null || application == null) throw new IllegalStateException("clipboard_unavailable");
    }

    private void prepareRestore(XposedModule module) throws ReflectiveOperationException {
        if (source == 2) {
            oplusRestore = Class.forName("com.oplus.linker.synergy.wisetransfer.clipboard.ClipBoardServiceHelper", false, loader)
                    .getMethod("copyCompleteDataToClip", ClipData.class, Context.class);
            return;
        }
        Class<?> resource = Class.forName("V3.V", false, loader);
        mirrorClip = Class.forName("com.xiaomi.mirror.message.ClipDataMessage", false, loader);
        mirrorMessage = Class.forName("com.xiaomi.mirror.message.ClipboardMessage", false, loader);
        mirrorGet = resource.getMethod("G");
        mirrorReceive = resource.getMethod("T", Class.forName("com.xiaomi.mirror.k0", false, loader), mirrorMessage);
        // T performs the original OEM cache/counter/write sequence. Only its conversion of our
        // exact local restoration object is replaced, on this thread, to retain arbitrary original
        // ClipData (HTML/URI/Intent included) without serializing private content into a message.
        mirrorRestoreHook = module.hook(resource.getMethod("z0", mirrorClip, boolean.class))
                .setId("yj.clipboard.debug.restore").intercept(chain -> {
                    if (restoring && Thread.currentThread() == restoreThread && chain.getArg(0) == restoreMessage) {
                        restoreConverted = true;
                        return original;
                    }
                    return chain.proceed();
                });
    }

    private void command(Intent intent) {
        if (closed || !(ACTION.equals(intent.getAction()) || URI_ACTION.equals(intent.getAction()))) return;
        String nextRun = intent.getStringExtra("run");
        if (nextRun == null || !nextRun.matches("[0-9a-f]{32}")) return;
        String op = intent.getStringExtra("op");
        if ("prepare".equals(op)) {
            String format = intent.getStringExtra("format");
            if (format == null || "plain".equals(format) || "html".equals(format) || "uri".equals(format) || "uri_guard".equals(format))
                prepare(nextRun, "html".equals(format), "uri".equals(format), "uri_guard".equals(format));
            return;
        }
        if (!nextRun.equals(run)) return;
        if ("copy".equals(op)) copy(intent.getIntExtra("leg", 0));
        else if ("check".equals(op)) check(intent.getIntExtra("leg", 0));
        else if ("finish".equals(op)) finish("requested");
    }

    private boolean readable() {
        Activity activity = focused.get();
        return context.checkSelfPermission("android.permission.READ_CLIPBOARD_IN_BACKGROUND") == PackageManager.PERMISSION_GRANTED
                || activity != null && activity.hasWindowFocus();
    }

    private void prepare(String nextRun, boolean html, boolean uri, boolean guard) {
        if (armed) { event("prepare", 0, "busy"); return; }
        run = nextRun;
        htmlMode = html;
        uriMode = uri;
        uriGuard = guard;
        if (uriMode && uriTransfers == null) { event("prepare", 0, "uri_probe_unavailable"); return; }
        JSONObject p = client.projection();
        if (!ready(p)) { event("prepare", 0, "peer_unavailable"); return; }
        if (!readable()) { event("prepare", 0, "requires_focused_native_activity"); return; }
        try {
            ClipData saved = clipboard.getPrimaryClip();
            if (saved == null && clipboard.hasPrimaryClip()) throw new SecurityException();
            original = saved == null ? null : new ClipData(saved);
            session = p.optString("session_id");
            armed = true; generation++; pending = 0;
            testUris.clear(); sourceFiles.clear(); java.util.Arrays.fill(uriApplied, false); java.util.Arrays.fill(uriConsumed, false);
            main.postDelayed(expiry, uriMode || uriGuard ? 300_000 : 90_000);
            event("prepare", 0, uriGuard ? "armed_uri_guard" : uriMode ? "armed_uri" : htmlMode ? "armed_html" : "armed");
        } catch (RuntimeException error) { event("prepare", 0, "snapshot_denied"); }
    }

    private boolean ready(JSONObject projection) {
        if (projection == null || !projection.optString("session_id").matches("[0-9a-f]{64}")) return false;
        JSONArray operations = projection.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ((uriMode ? "clipboard_uri_write" : htmlMode ? "clipboard_rich_write" : "clipboard_write").equals(operations.optString(i))) return true;
        return false;
    }
    private boolean current() {
        JSONObject p = client.projection();
        return armed && ready(p) && session.equals(p.optString("session_id"));
    }
    private String marker(int leg) { return (htmlMode ? "YJ_HTML_" : "YJ_CLIP_") + run + "_" + leg + "_中文🙂\n\t"; }
    private String html(int leg) { return "<b>" + marker(leg) + "</b><i>synthetic</i>"; }
    private int htmlBytes(int leg) { return OplusClipboardCodec.encode(marker(leg)).length + OplusClipboardCodec.encode(html(leg)).length; }
    private int leg(String text) { return marker(1).equals(text) ? 1 : marker(2).equals(text) ? 2 : 0; }

    private void copy(int leg) {
        if (!current() || leg < 1 || leg > 2) { event("source", leg, "stale_or_invalid"); return; }
        if (!readable()) { event("source", leg, "clipboard_read_denied"); return; }
        if (pending != 0) { event("source", leg, "busy"); return; }
        if (uriGuard) { event("source", leg, "guard_only"); return; }
        if (uriMode) { copyUri(leg); return; }
        try {
            ClipData now = clipboard.getPrimaryClip();
            if (!sameContent(original, now) && syntheticLeg(now) == 0) {
                event("source", leg, "clipboard_changed"); return;
            }
            CompletableFuture<OplusClipboardAdapter.Receipt> previous = lastTransfer.get();
            CompletableFuture<ClipboardBridge.RichReceipt> previousRich = richTransfers.get();
            pending = leg;
            // Creates a real platform clipboard-change event. The OEM listener and its normal
            // native send entry point must run; this probe never calls adapter.send or bridge.request.
            clipboard.setPrimaryClip(htmlMode ? ClipData.newHtmlText("YJ synthetic HTML test", marker(leg), html(leg))
                    : ClipData.newPlainText("YJ synthetic clipboard test", marker(leg)));
            event("source", leg, "platform_copy");
            if (htmlMode) observeRich(previousRich, leg, generation, SystemClock.elapsedRealtime() + 15_000);
            else observe(previous, leg, generation, SystemClock.elapsedRealtime() + 15_000);
        } catch (RuntimeException error) { pending = 0; event("source", leg, "copy_failed"); }
    }

    private String uriName(int leg) { return "YJ_URI_" + run + "_" + leg + ".bin"; }
    private int uriLeg(String name) { return uriName(1).equals(name) ? 1 : uriName(2).equals(name) ? 2 : 0; }
    private byte[] uriBytes(int leg) {
        byte[] result = new byte[8193]; byte[] prefix = uriName(leg).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < result.length; i++) result[i] = i < prefix.length ? prefix[i] : (byte) (i * 31 + leg);
        return result;
    }
    private String uriHash(int leg) {
        try { byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(uriBytes(leg));
            StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void copyUri(int leg) {
        final CompletableFuture<?> previous = uriTransfers.get(); final long gen = generation;
        pending = leg;
        new Thread(() -> {
            File file = null; Uri uri = null;
            try {
                file = File.createTempFile("yj-uri-" + run + "-", ".bin", context.getCacheDir());
                Files.write(file.toPath(), uriBytes(leg));
                String authority = source == 1 ? "com.xiaomi.mirror.fileprovider" : "com.oplus.linker.devicemanager.fileprovider";
                Class<?> provider = Class.forName("androidx.core.content.FileProvider", false, loader);
                uri = (Uri) provider.getMethod("getUriForFile", Context.class, String.class, File.class, String.class)
                        .invoke(null, context, authority, file, uriName(leg));
                final File owned = file; final Uri local = uri;
                main.post(() -> {
                    sourceFiles.put(local, owned); testUris.put(local, leg);
                    try {
                        if (!armed || gen != generation || !current()) throw new IllegalStateException();
                        ClipData now = clipboard.getPrimaryClip();
                        if (!sameContent(original, now) && syntheticLeg(now) == 0) throw new IllegalStateException();
                        clipboard.setPrimaryClip(new ClipData("YJ synthetic URI", new String[]{"application/octet-stream"}, new ClipData.Item(local)));
                        event("source", leg, "platform_copy");
                        observeUri(previous, leg, gen, SystemClock.elapsedRealtime() + 240_000);
                    } catch (RuntimeException failure) { pending = 0; event("source", leg, "copy_failed"); }
                });
            } catch (Exception failure) {
                if (file != null) try { Files.deleteIfExists(file.toPath()); } catch (java.io.IOException ignored) {}
                main.post(() -> { pending = 0; event("source", leg, "copy_failed"); });
            }
        }, "yj-uri-debug-fixture").start();
    }
    private void observeUri(CompletableFuture<?> previous, int leg, long gen, long deadline) {
        if (!armed || gen != generation) return;
        if (!current()) { pending = 0; event("receipt", leg, "stale_session"); return; }
        CompletableFuture<?> next = uriTransfers.get();
        if (next != null && next != previous) next.whenComplete((value, error) -> main.post(() -> {
            if (!armed || gen != generation) return;
            pending = 0; JSONObject p = client.projection();
            event("receipt", leg, current() && error == null && value instanceof ClipboardUriBridge.Receipt r
                    && p.optString("id").equals(r.peer()) && session.equals(r.session()) && r.size() == 8193 && uriHash(leg).equals(r.sha256())
                    ? "remote_ack" : "failed");
        }));
        else if (SystemClock.elapsedRealtime() >= deadline) { pending = 0; event("receipt", leg, "native_source_not_observed"); }
        else main.postDelayed(() -> observeUri(previous, leg, gen, deadline), 100);
    }
    @Override public void attachUriTransfers(Supplier<? extends CompletableFuture<?>> transfers) {
        if (!uriRegistered) try {
            context.registerReceiver(receiver, new IntentFilter(URI_ACTION), android.Manifest.permission.DUMP, main, Context.RECEIVER_EXPORTED);
            uriRegistered = true;
        } catch (RuntimeException unavailable) { uriTransfers = null; return; }
        uriTransfers = transfers;
    }
    @Override public boolean permitUriOutbound(ClipData clip) {
        if (restoring) return false;
        if (armed) return uriMode && current() && clip != null && clip.getItemCount() == 1 && sourceFiles.containsKey(clip.getItemAt(0).getUri());
        if (guardRestored) {
            if (sameContent(restored, clip)) return false;
            guardRestored = false; restored = null;
        }
        return true;
    }
    @Override public boolean permitUriInbound(String name, long size, String sha256) {
        int leg = uriLeg(name);
        if (restoring) return false;
        if (armed) return uriMode && current() && leg != 0 && size == 8193 && uriHash(leg).equals(sha256);
        return run == null || leg == 0;
    }
    private void rememberUri(String receivedSession, String name, ClipData clip) {
        if (armed && uriMode && current() && Objects.equals(session, receivedSession) && uriLeg(name) != 0
                && clip != null && clip.getItemCount() == 1 && clip.getItemAt(0).getUri() != null) testUris.put(clip.getItemAt(0).getUri(), uriLeg(name));
    }
    @Override public void uriStaged(String session, String name, ClipData clip) {
        rememberUri(session, name, clip);
        int leg = uriLeg(name);
        if (leg != 0 && clip != null && syntheticLeg(clip) == leg) event("stage", leg, "native_handle_ready");
    }
    @Override public void uriApplied(String session, String name, ClipData clip) {
        rememberUri(session, name, clip); int leg = uriLeg(name);
        if (armed && uriMode && current() && Objects.equals(this.session, session) && leg != 0 && !uriApplied[leg]) {
            uriApplied[leg] = true; event("sink", leg, "native_write_readback");
        }
    }
    @Override public void uriConsumed(String session, String name) {
        int leg = uriLeg(name);
        if (armed && uriMode && current() && Objects.equals(this.session, session) && leg != 0 && !uriConsumed[leg]) {
            uriConsumed[leg] = true; event("consumer", leg, "native_provider_open");
        }
    }

    private void observeRich(CompletableFuture<ClipboardBridge.RichReceipt> previous, int leg, long gen, long deadline) {
        if (!armed || gen != generation) return;
        if (!current()) { pending = 0; event("receipt", leg, "stale_session"); return; }
        CompletableFuture<ClipboardBridge.RichReceipt> next = richTransfers.get();
        if (next != null && next != previous) {
            next.whenComplete((receipt, error) -> main.post(() -> {
                if (!armed || gen != generation) return;
                pending = 0;
                JSONObject p = client.projection();
                event("receipt", leg, current() && error == null && receipt != null
                        && p.optString("id").equals(receipt.peer()) && session.equals(receipt.session())
                        && receipt.utf8Bytes() == htmlBytes(leg) ? "remote_ack" : "failed");
            }));
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            pending = 0; event("receipt", leg, "native_source_not_observed");
        } else main.postDelayed(() -> observeRich(previous, leg, gen, deadline), 100);
    }

    private void observe(CompletableFuture<OplusClipboardAdapter.Receipt> previous, int leg, long gen, long deadline) {
        if (!armed || gen != generation) return;
        if (!current()) { pending = 0; event("receipt", leg, "stale_session"); return; }
        CompletableFuture<OplusClipboardAdapter.Receipt> next = lastTransfer.get();
        if (next != null && next != previous) {
            next.whenComplete((receipt, error) -> main.post(() -> {
                if (!armed || gen != generation) return;
                pending = 0;
                event("receipt", leg, current() && error == null && receipt != null
                        && session.equals(receipt.session()) && receipt.utf8Bytes() == OplusClipboardCodec.encode(marker(leg)).length
                        ? "remote_ack" : "failed");
            }));
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            pending = 0; event("receipt", leg, "native_source_not_observed");
        } else main.postDelayed(() -> observe(previous, leg, gen, deadline), 100);
    }

    @Override public boolean permitOutbound(String text) {
        if (restoring) return false;
        if (armed && (uriMode || uriGuard)) return false;
        if (armed) return current() && leg(text) != 0;
        // Keep a local-only guard for delayed callbacks after restoration, even if an OEM version
        // fails to consume its receive counter. The first different native copy releases the guard.
        if (guardRestored) {
            if (containsText(restored, text)) return false;
            guardRestored = false; restored = null;
        }
        return true;
    }
    @Override public boolean permitInbound(String text) {
        if (restoring) return false;
        if (armed && (uriMode || uriGuard)) return false;
        if (armed) return current() && leg(text) != 0;
        // A delayed task from a finished probe must not replace the restored user's clipboard.
        return run == null || leg(text) == 0;
    }
    @Override public void received(String receivedSession, String request, String text, String error) {
        if (!armed || !Objects.equals(session, receivedSession)) return;
        int leg = leg(text);
        if (leg != 0) event("sink", leg, error == null ? "native_write_readback" : "failed");
    }
    private void check(int leg) {
        if (!current() || leg < 1 || leg > 2) { event("readback", leg, "stale_or_invalid"); return; }
        try { event("readback", leg, readable() && matches(clipboard.getPrimaryClip(), leg)
                && (!uriMode || uriApplied[leg] && uriConsumed[leg]) ? "matches" : "unverified"); }
        catch (RuntimeException error) { event("readback", leg, "unavailable"); }
    }

    private void finish(String reason) {
        if (!armed) return;
        main.removeCallbacks(expiry);
        // Receive tasks are at most 30s. Normal script finishes only after terminal observations;
        // the privacy guard remains while restoring and late private callbacks are also blocked.
        String result;
        try {
            if (!readable()) { event("restore", 0, "requires_focused_native_activity"); main.postDelayed(expiry, 5000); return; }
            ClipData now = clipboard.getPrimaryClip();
            if (syntheticLeg(now) == 0) {
                result = sameContent(original, now) ? "unchanged" : "preserved_new_clipboard";
            } else {
                restoring = true;
                if (source == 2) {
                    if (original == null) clipboard.clearPrimaryClip();
                    else oplusRestore.invoke(null, original, context);
                } else {
                    restoreMessage = mirrorClip.getConstructor().newInstance();
                    mirrorClip.getField("clock").setLong(restoreMessage, ClipboardNativeClock.next());
                    Object message = mirrorMessage.getConstructor().newInstance();
                    mirrorMessage.getField("data").set(message, restoreMessage);
                    restoreThread = Thread.currentThread(); restoreConverted = false;
                    mirrorReceive.invoke(mirrorGet.invoke(null), null, message);
                    if (!restoreConverted) throw new IllegalStateException("native_restore_not_executed");
                }
                if (!sameContent(original, clipboard.getPrimaryClip())) throw new IllegalStateException("restore_unverified");
                restored = original; guardRestored = original != null;
                result = "restored";
            }
            if (uriGuard) { restored = clipboard.getPrimaryClip(); guardRestored = restored != null; }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            event("restore", 0, "failed_retry_pending");
            main.postDelayed(expiry, 5000);
            return; // Keep the only original in memory for an explicit or timed retry.
        } finally { restoring = false; restoreMessage = null; restoreThread = null; }
        armed = false; original = null; generation++; pending = 0;
        if (uriMode) cleanupUriFiles();
        event("restore", 0, result);
    }

    private int syntheticLeg(ClipData clip) { return matches(clip, 1) ? 1 : matches(clip, 2) ? 2 : 0; }
    private boolean matches(ClipData clip, int leg) {
        if (uriMode) return clip != null && clip.getItemCount() == 1 && Integer.valueOf(leg).equals(testUris.get(clip.getItemAt(0).getUri()));
        if (!htmlMode) return plain(clip, marker(leg));
        return clip != null && clip.getItemCount() == 1 && clip.getDescription().hasMimeType("text/html")
                && clip.getItemAt(0).getUri() == null && clip.getItemAt(0).getIntent() == null
                && TextUtils.equals(clip.getItemAt(0).getText(), marker(leg)) && html(leg).equals(clip.getItemAt(0).getHtmlText());
    }
    private void cleanupUriFiles() {
        Map<Uri, File> owned = new HashMap<>(sourceFiles); sourceFiles.clear();
        new Thread(() -> {
            boolean deleted = true;
            for (var item : owned.entrySet()) try {
                context.revokeUriPermission(item.getKey(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Files.deleteIfExists(item.getValue().toPath());
            } catch (java.io.IOException | RuntimeException failure) { deleted = false; main.postDelayed(() -> {
                sourceFiles.put(item.getKey(), item.getValue()); if (!armed) cleanupUriFiles();
            }, 1000); }
            if (deleted) main.post(() -> event("fixture_cleanup", 0, "deleted"));
        }, "yj-uri-debug-cleanup").start();
    }
    private static boolean containsText(ClipData clip, String text) {
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++)
            if (TextUtils.equals(clip.getItemAt(i).getText(), text)) return true;
        return false;
    }
    private static boolean plain(ClipData clip, String expected) {
        return clip != null && clip.getItemCount() == 1 && clip.getItemAt(0).getUri() == null
                && clip.getItemAt(0).getIntent() == null && clip.getItemAt(0).getHtmlText() == null
                && TextUtils.equals(clip.getItemAt(0).getText(), expected);
    }
    private static boolean sameContent(ClipData left, ClipData right) {
        if (left == null || right == null) return left == right;
        if (left.getItemCount() != right.getItemCount()) return false;
        for (int i = 0; i < left.getItemCount(); i++) {
            ClipData.Item a = left.getItemAt(i), b = right.getItemAt(i);
            if (!TextUtils.equals(a.getText(), b.getText()) || !Objects.equals(a.getHtmlText(), b.getHtmlText())
                    || !Objects.equals(a.getUri(), b.getUri())) return false;
            if (a.getIntent() != null || b.getIntent() != null)
                if (a.getIntent() == null || b.getIntent() == null || !a.getIntent().filterEquals(b.getIntent())) return false;
        }
        return true;
    }
    private void event(String stage, int leg, String result) {
        try {
            Log.i(TAG, new JSONObject().put("run", run == null ? "" : run).put("source", source)
                    .put("session", session == null ? "" : session).put("stage", stage).put("leg", leg)
                    .put("result", result).toString());
        } catch (org.json.JSONException ignored) { /* No payload, exception message, or original clipboard is logged. */ }
    }
    @Override public void close() {
        if (closed) return;
        finish("closed"); closed = true;
        try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        application.unregisterActivityLifecycleCallbacks(lifecycle);
        if (mirrorRestoreHook != null && !armed) { mirrorRestoreHook.unhook(); mirrorRestoreHook = null; }
        // On a failed restoration retain the in-process retry; never silently discard the original.
    }
}
