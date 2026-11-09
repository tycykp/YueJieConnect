package io.github.yuejie.connect;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Executes only in an OEM process, after the bridge has checked peer, session and app_relay grant. */
class AppRelayEndpoint implements AppRelayTasks.Endpoint {
    interface Documents {
        // Token lookup MUST be scoped to the authenticated peer/session by the implementing closure.
        // It returns a fully received and hash-verified local content URI, never a sender's URI.
        Document acquire(String token, String mime) throws Exception;
    }
    interface Document extends AutoCloseable {
        Uri uri();
        @Override void close();
    }
    static final class Launch implements AppRelayTasks.Prepared {
        final Intent intent;
        final AppRelayRequest request;
        final String target;
        final Document document;
        final List<Task> before;
        long dispatched;
        boolean launched;
        Launch(AppRelayRequest request, Intent intent, String target, Document document, List<Task> before) {
            this.request = request; this.intent = intent; this.target = target; this.document = document; this.before = before;
        }
        @Override public String targetPackage() { return target; }
    }
    private record Task(int id, ComponentName top, long active) {}
    final Context context;
    final ClassLoader loader;
    java.util.function.BooleanSupplier dispatchAllowed = () -> true;
    private final Documents documents;
    private final java.util.concurrent.ScheduledExecutorService cleanupWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yj-relay-document-cleanup"); t.setDaemon(true); return t;
    });
    private final java.util.Map<Document, java.util.concurrent.ScheduledFuture<?>> retained = new java.util.HashMap<>();

    AppRelayEndpoint(Context context, ClassLoader loader, Documents documents) {
        if (!List.of("com.xiaomi.mirror", "com.oplus.linker").contains(context.getPackageName()))
            throw new SecurityException("invalid_executor_context");
        this.context = context; this.loader = loader; this.documents = documents;
    }

    @Override public AppRelayTasks.Prepared prepare(AppRelayRequest request) throws Exception {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isDeviceLocked()) throw new IllegalStateException("device_locked");
        String failure = "target_app_missing";
        ArrayList<String> candidates = new ArrayList<>(request.targets());
        if (request.kind() == AppRelayRequest.Kind.BROWSER) {
            String preferred = browserPackage(context, Uri.parse(request.uri()));
            if (preferred != null && !candidates.contains(preferred)) candidates.add(preferred);
        }
        for (String target : candidates) {
            Document document = null;
            try {
                var app = context.getPackageManager().getApplicationInfo(target, 0);
                if (!app.enabled || (app.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0) {
                    failure = "target_app_disabled"; continue;
                }
                if (!request.signerSha256().isEmpty() && !signers(context, target).contains(request.signerSha256())) {
                    failure = "target_signature_mismatch"; continue;
                }
                checkSupported(request, target);
                Uri local = null;
                if (request.kind() == AppRelayRequest.Kind.DOCUMENT) {
                    if (documents == null) throw new IllegalStateException("file_transfer_required");
                    document = documents.acquire(request.fileToken(), request.mime());
                    local = document.uri();
                    if (local == null || !"content".equals(local.getScheme())) throw new SecurityException("invalid_document_uri");
                    try (var fd = context.getContentResolver().openFileDescriptor(local, "r")) {
                        if (fd == null) throw new IllegalStateException("document_unavailable");
                    }
                }
                Intent intent = intent(request, target, local);
                ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null || resolved.activityInfo == null) { failure = "target_activity_missing"; continue; }
                ActivityInfo activity = resolved.activityInfo;
                if (!target.equals(activity.packageName) || !activity.exported || !activity.enabled)
                    throw new SecurityException("target_not_exported");
                if (activity.permission != null && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("target_permission_denied");
                intent.setComponent(new ComponentName(activity.packageName, activity.name));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (local != null) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("Relay document", local));
                }
                Launch launch = new Launch(request, intent, target, document, tasks());
                document = null; // ownership transferred
                return launch;
            } catch (PackageManager.NameNotFoundException absent) { failure = "target_app_missing"; }
            finally { if (document != null) document.close(); }
        }
        throw new IllegalStateException(failure);
    }

    void checkSupported(AppRelayRequest request, String target) throws Exception {}

    static String directoryTarget(Context context, AppRelayCatalog.Candidate candidate) {
        if (candidate.kind() != AppRelayCatalog.Kind.BROWSER) return candidate.packageName();
        String browser = browserPackage(context, Uri.parse("https://example.invalid/"));
        if (browser == null) throw new IllegalStateException("target_app_missing");
        return browser;
    }

    private static String browserPackage(Context context, Uri uri) {
        Intent browser = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo preferred = context.getPackageManager().resolveActivity(browser, PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred == null || preferred.activityInfo == null || !preferred.activityInfo.exported
                || !preferred.activityInfo.enabled || "android".equals(preferred.activityInfo.packageName)) return null;
        return preferred.activityInfo.packageName;
    }

    Intent intent(AppRelayRequest request, String target, Uri local) throws Exception {
        Intent intent;
        if (request.kind() == AppRelayRequest.Kind.RICH_TEXT) {
            OplusRelayPayload profile = OplusRelayPayload.richText(request.nativePayload(), request.sourcePackage());
            intent = new Intent("com.example.richtext.action.application.relay")
                    .setComponent(new ComponentName(target, profile.activity())).putExtra("ExtraContentData", profile.json());
        } else if (request.kind() == AppRelayRequest.Kind.HOME) {
            intent = context.getPackageManager().getLaunchIntentForPackage(target);
            if (intent == null) throw new IllegalStateException("target_activity_missing");
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            if (local != null) intent.setDataAndType(local, request.mime());
            else intent.setData(Uri.parse(request.uri()));
            if (request.kind() == AppRelayRequest.Kind.BROWSER) intent.addCategory(Intent.CATEGORY_BROWSABLE);
        }
        return intent.setPackage(target);
    }

    @Override public void launch(AppRelayTasks.Prepared prepared) throws Exception {
        Launch launch = (Launch) prepared;
        checkSupported(launch.request, launch.target);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isDeviceLocked()) throw new IllegalStateException("device_locked");
        if (!dispatchAllowed.getAsBoolean()) throw new IllegalStateException("execution_revoked");
        launch.dispatched = SystemClock.elapsedRealtime();
        context.startActivity(launch.intent);
        launch.launched = true;
    }

    @Override public int observe(AppRelayTasks.Prepared prepared) {
        Launch launch = (Launch) prepared;
        List<Task> current = tasks();
        if (!current.isEmpty()) {
            Task top = current.get(0);
            if (matches(launch, top)) {
                Task before = launch.before.stream().filter(task -> task.id == top.id).findFirst().orElse(null);
                boolean wasForeground = !launch.before.isEmpty() && matches(launch, launch.before.get(0));
                if (before == null || !wasForeground || (top.active > 0 && top.active > before.active)) return top.id;
            }
        }
        if (SystemClock.elapsedRealtime() - launch.dispatched > 10000) throw new IllegalStateException("launch_unverified");
        return -1;
    }

    private static boolean matches(Launch launch, Task task) {
        if (task.top == null) return false;
        // URI handlers can route from an exported trampoline to another activity in the verified app.
        boolean routesWithinApp = launch.request.kind() == AppRelayRequest.Kind.BROWSER
                || launch.request.kind() == AppRelayRequest.Kind.APP;
        return routesWithinApp ? launch.target.equals(task.top.getPackageName())
                : launch.intent.getComponent().equals(task.top);
    }

    @Override public synchronized void release(AppRelayTasks.Prepared prepared) {
        Launch launch = (Launch) prepared;
        if (launch.document == null) return;
        // Activity-owned read grants live until the target activity finishes. Keep the staged file
        // available for a bounded reading window after dispatch; no global/persistable grants.
        if (launch.launched) {
            Runnable cleanup = () -> { synchronized (AppRelayEndpoint.this) { retained.remove(launch.document); launch.document.close(); } };
            retained.put(launch.document, cleanupWorker.schedule(cleanup, 300000, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        else launch.document.close();
    }
    @Override public synchronized void close() {
        for (var entry : retained.entrySet()) { entry.getValue().cancel(false); entry.getKey().close(); }
        retained.clear();
        cleanupWorker.shutdown();
    }

    @SuppressWarnings("deprecation") private List<Task> tasks() {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IllegalStateException("task_observation_unavailable");
        ArrayList<Task> tasks = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(16)) {
            long active = 0;
            try { active = task.getClass().getField("lastActiveTime").getLong(task); }
            catch (ReflectiveOperationException ignored) { /* Existing foreground task cannot prove a fresh launch. */ }
            tasks.add(new Task(task.taskId, task.topActivity, active));
        }
        return tasks;
    }

    static List<String> signers(Context context, String target) throws Exception {
        var info = context.getPackageManager().getPackageInfo(target, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
        if (info == null) throw new IllegalStateException("target_signature_unavailable");
        Signature[] signatures = info.getApkContentsSigners();
        ArrayList<String> results = new ArrayList<>();
        for (Signature signature : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            results.add(hex.toString());
        }
        return results;
    }
}
