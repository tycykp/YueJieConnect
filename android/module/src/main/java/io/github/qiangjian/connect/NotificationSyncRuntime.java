package io.github.qiangjian.connect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationListenerService;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real Android notification endpoint, hosted in the module process. All bridge calls are asynchronous. */
final class NotificationSyncRuntime implements AutoCloseable {
    interface Transport {
        /** Must preserve peer/session/request identity and fail on disconnect, rejection, cancellation or timeout. */
        CompletableFuture<byte[]> send(String peer, String session, String request, String operation, byte[] data);
        void cancel(String peer, String session, String request);
    }
    interface Grants {
        boolean allows(String peer, String session, String operation);
        /** Required explicit package allow-list. Tests should allow only the synthetic fixture package. */
        boolean allowsPackage(String packageName);
    }
    /** OEM source mode observes the already registered native listener; it never registers or grants one. */
    interface SourceAccess {
        NotificationListenerService listener();
        boolean granted();
    }
    static volatile NotificationSyncRuntime active;
    private static final String CHANNEL = "qj_notification_sync", PREFIX = "qj.nsync.", MARKER = "qj.notification.mirrored";
    private final Context context;
    private final NotificationManager manager;
    private final Transport transport;
    private final Grants grants;
    private final SourceAccess nativeSource;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, Runnable> notificationEvents = new HashMap<>();
    private boolean drainScheduled;
    private final Map<String, Tap> taps = new HashMap<>();
    private final Map<String, List<PendingIntent>> intents = new HashMap<>();
    private final Set<String> incoming = new HashSet<>(), tags = new HashSet<>();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private final Map<CompletableFuture<?>, Runnable> cancellations = new HashMap<>();
    private final Map<String, Received> requests = new HashMap<>();
    private final NotificationSyncLedger ledger = new NotificationSyncLedger();
    private volatile boolean closed;
    private volatile long verifiedTransfers, rejectedTransfers;
    private volatile String peer = "", session = "";
    private volatile long generation;
    private static final class Source {
        final String token = UUID.randomUUID().toString();
        volatile StatusBarNotification notification;
        NotificationSyncCodec.Post advertised;
        volatile long revision;
        byte[] digest;
        volatile boolean removed;
        int queued;
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
    private record Tap(String key, long revision, String button, boolean reply, long generation) {}
    private record Received(byte[] digest, CompletableFuture<byte[]> result) {}

    NotificationSyncRuntime(Context context, Transport transport, Grants grants) {
        this(context, transport, grants, null);
    }
    NotificationSyncRuntime(Context context, Transport transport, Grants grants, SourceAccess nativeSource) {
        this.context = context.getApplicationContext(); this.transport = transport; this.grants = grants;
        this.nativeSource = nativeSource;
        manager = this.context.getSystemService(NotificationManager.class);
        if (manager == null || transport == null || grants == null) throw new IllegalArgumentException("notification_unavailable");
        if (nativeSource == null && active != null) throw new IllegalStateException("notification_already_started");
        worker.setRemoveOnCancelPolicy(true);
        if (nativeSource == null) active = this;
        // Only remove mirrors owned by this endpoint, including leftovers after process death.
        if (nativeSource == null) worker.execute(() -> {
            for (StatusBarNotification n : manager.getActiveNotifications())
                if (n.getTag() != null && n.getTag().startsWith(PREFIX) && n.getNotification().extras.getBoolean(MARKER))
                    manager.cancel(n.getTag(), n.getId());
        });
    }
    void onProjection(String newPeer, String newSession) {
        submit(() -> {
            String p = newPeer == null ? "" : newPeer, s = newSession == null ? "" : newSession;
            if (!s.isEmpty() && (!s.matches("[0-9a-f]{64}") || p.isEmpty())) throw failure("invalid_session");
            if (!peer.equals(p) || !session.equals(s)) { reset(); peer = p; session = s; }
        });
    }
    boolean accessGranted() {
        if (nativeSource != null) return nativeSource.listener() != null && nativeSource.granted();
        return NotificationSyncListener.connected != null
                && manager.isNotificationListenerAccessGranted(new ComponentName(context, NotificationSyncListener.class));
    }
    boolean postingGranted() {
        if (nativeSource != null) return false; // No module receivers exist in the OEM package.
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    void accessLost() { submit(this::reset); }
    /** Call on any policy/consent change; previously rendered actions and mirrors must be revoked. */
    void authorizationChanged() { submit(this::reset); }
    long verifiedTransfers() { return verifiedTransfers; }
    long rejectedTransfers() { return rejectedTransfers; }

    void posted(StatusBarNotification n) {
        posted(n, null);
    }
    /** The supplied immutable Post is captured from the actual OEM send function, after its own filtering. */
    void posted(StatusBarNotification n, NotificationSyncCodec.Post nativePost) {
        if (n == null || n.getNotification() == null || closed) return;
        // Check authorization before retaining or converting any private notification content.
        if (!n.getUser().equals(android.os.Process.myUserHandle()) || !grants.allowsPackage(n.getPackageName())
                || n.getNotification().extras.getBoolean(MARKER)) return;
        notificationEvent(n.getKey(), () -> {
            require(NotificationSyncCodec.OP_POST); if (!accessGranted()) throw failure("access_denied");
            if (!grants.allowsPackage(n.getPackageName())) throw failure("package_denied");
            Source source = sources.get(n.getKey());
            if (source == null) {
                if (sources.size() >= 128) throw failure("capacity");
                source = new Source(); sources.put(n.getKey(), source);
            }
            if (source.queued >= 8) { source.digest = null; throw failure("source_busy"); }
            NotificationSyncCodec.Post post = nativePost == null ? capture(n) : nativePost;
            byte[] fingerprint = NotificationSyncLedger.digest(NotificationSyncCodec.encode(NotificationSyncCodec.posted(source.token, 1, post)));
            // Keep the newest local PendingIntents even when the visible content did not change.
            boolean duplicate = !source.removed && Arrays.equals(source.digest, fingerprint)
                    && sameActions(source.notification, n);
            source.notification = n;
            source.advertised = post;
            if (duplicate) return;
            source.digest = fingerprint; source.removed = false; source.revision++;
            enqueue(source, NotificationSyncCodec.posted(source.token, source.revision, post));
        });
    }
    void removed(StatusBarNotification n) {
        if (n == null || closed) return;
        notificationEvent(n.getKey(), () -> {
            Source s = sources.get(n.getKey()); if (s == null || s.removed) return;
            s.removed = true; s.revision++;
            enqueue(s, NotificationSyncCodec.removed(s.token, s.revision));
        });
    }
    private void enqueue(Source source, NotificationSyncCodec.Frame f) {
        long epoch = generation;
        source.queued++;
        source.tail = source.tail.handle((v, e) -> null).thenComposeAsync(v -> {
            if (generation != epoch) return CompletableFuture.failedFuture(failure("stale_session"));
            return send(f);
        }, worker);
        // A failed send is never acknowledged or logged as delivered. Subsequent revisions can retry with a new post.
        source.tail.whenCompleteAsync((v, error) -> {
            source.queued--;
            if (generation == epoch) {
                if (error != null) { rejectedTransfers++; if (source.revision == f.revision()) source.digest = null; }
                else verifiedTransfers++;
            }
        }, worker);
    }
    private CompletableFuture<Void> send(NotificationSyncCodec.Frame f) {
        require(NotificationSyncCodec.operation(f)); long epoch = generation;
        CompletableFuture<Void> result = new CompletableFuture<>(); track(result);
        String targetPeer = peer, targetSession = session, request = UUID.randomUUID().toString();
        cancellations.put(result, () -> transport.cancel(targetPeer, targetSession, request));
        try { transport.send(peer, session, request, NotificationSyncCodec.operation(f), NotificationSyncCodec.encode(f))
                .orTimeout(6, TimeUnit.SECONDS).whenCompleteAsync((bytes, error) -> {
                    if (result.isDone()) return;
                    try {
                        if (generation != epoch) throw failure("stale_session");
                        require(NotificationSyncCodec.operation(f));
                        if (error != null) { transport.cancel(targetPeer, targetSession, request); throw failure("remote_failed"); }
                        NotificationSyncCodec.checkReceipt(f, bytes); result.complete(null);
                    } catch (RuntimeException e) { result.completeExceptionally(e); }
                }, worker); }
        catch (RuntimeException error) { result.completeExceptionally(error); }
        return result;
    }

    CompletableFuture<byte[]> execute(String remotePeer, String remoteSession, String request, String operation, byte[] payload, long deadlineElapsedMs) {
        final NotificationSyncCodec.Frame frame;
        try { frame = NotificationSyncCodec.decode(payload); }
        catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(failure("closed"));
        worker.execute(() -> {
            try {
                if (request == null || request.isEmpty() || request.length() > 128) throw failure("invalid_request");
                if (!peer.equals(remotePeer) || !session.equals(remoteSession)) throw failure("stale_session");
                if (!NotificationSyncCodec.operation(frame).equals(operation)) throw failure("invalid_operation");
                if (nativeSource != null && frame.kind() != NotificationSyncCodec.ACTION) throw failure("source_only_endpoint");
                require(operation);
                byte[] digest = NotificationSyncLedger.digest(NotificationSyncCodec.encode(frame));
                Received previous = requests.get(request);
                if (previous != null) {
                    if (!Arrays.equals(digest, previous.digest)) throw failure("request_conflict");
                    previous.result.whenComplete((value, error) -> { if (error == null) result.complete(value.clone()); else result.completeExceptionally(error); });
                    return;
                }
                long remaining = deadlineElapsedMs - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) throw failure("deadline_expired");
                if (requests.size() >= 256) throw failure("request_capacity");
                track(result); requests.put(request, new Received(digest, result));
                worker.schedule(() -> result.completeExceptionally(failure("outcome_unverified")), Math.min(remaining, 6000), TimeUnit.MILLISECONDS);
                if (frame.kind() == NotificationSyncCodec.ACTION) { perform(frame, result); return; }
                if (!incoming.add(frame.key())) throw failure("busy");
                long epoch = generation;
                result.whenCompleteAsync((v, e) -> { if (epoch == generation) incoming.remove(frame.key()); }, worker);
                apply(frame, result);
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        });
        return result;
    }
    void cancel(String expectedSession, String request) {
        submit(() -> {
            if (!session.equals(expectedSession)) return;
            Received received = requests.get(request);
            // The system call may have happened already. Never claim rollback or successful cancellation.
            if (received != null) received.result.completeExceptionally(failure("outcome_unverified"));
        });
    }
    private void apply(NotificationSyncCodec.Frame f, CompletableFuture<byte[]> result) {
        NotificationSyncLedger.Decision decision = ledger.check(f);
        String tag = tag(f.key());
        if (decision == NotificationSyncLedger.Decision.DUPLICATE) {
            // A receipt still requires the effect to exist: a user may have locally dismissed the mirror.
            if ((f.kind() == NotificationSyncCodec.POST) != present(tag, f.kind() == 1 ? f.revision() : -1)) throw failure("effect_missing");
            result.complete(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(f, f.kind() == 1 ? "posted" : "removed")));
            return;
        }
        if (f.kind() == NotificationSyncCodec.POST) {
            if (!postingGranted()) throw failure("post_permission_denied");
            NotificationChannel channel = new NotificationChannel(CHANNEL, "跨设备通知", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
            if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE)
                throw failure("channel_disabled");
            Notification n = render(f);
            ledger.reserve(f);
            tags.add(tag); manager.notify(tag, 1, n);
        } else {
            ledger.reserve(f);
            cancelIntents(f.key()); manager.cancel(tag, 1);
        }
        long epoch = generation;
        awaitEffect(() -> f.kind() == 1 ? present(tag, f.revision()) : !present(tag, -1), epoch, result, () -> {
            require(NotificationSyncCodec.operation(f)); ledger.commit(f);
            result.complete(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(f, f.kind() == 1 ? "posted" : "removed")));
        }, 20);
    }
    private Notification render(NotificationSyncCodec.Frame f) {
        NotificationSyncCodec.Post p = f.post(); cancelIntents(f.key());
        Bundle extras = new Bundle(); extras.putBoolean(MARKER, true); extras.putLong("qj.revision", f.revision());
        Notification.Builder b = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(context.getApplicationInfo().icon).setSubText(p.app())
                .setContentTitle(p.title()).setContentText(p.text()).setWhen(p.when())
                .setOngoing(p.ongoing()).setOnlyAlertOnce(true).setLocalOnly(true)
                // Stable source-key groups keep the module's foreground status and unrelated
                // mirrors out of an OEM auto-summary that can hide the original action area.
                .setGroup(PREFIX + f.key())
                .setVisibility(Notification.VISIBILITY_PRIVATE).addExtras(extras);
        Bitmap icon = decodeImage(p.icon()); if (icon != null) b.setLargeIcon(icon);
        Bitmap picture = decodeImage(p.picture());
        if (picture != null) b.setStyle(new Notification.BigPictureStyle().bigPicture(picture).setSummaryText(p.text()));
        else if (!p.bigText().isEmpty()) b.setStyle(new Notification.BigTextStyle().bigText(p.bigText()));
        if (grants.allows(peer, session, NotificationSyncCodec.OP_ACTION)) {
            if (p.openable()) b.setContentIntent(tapIntent(f, "open", false));
            // Dismiss remains an explicit action; local shade dismissal never silently deletes the source notification.
            if (!p.ongoing()) b.addAction(new Notification.Action.Builder((Icon) null, "在来源设备移除", tapIntent(f, "dismiss", false)).build());
            for (NotificationSyncCodec.Button button : p.buttons()) {
                Notification.Action.Builder action = new Notification.Action.Builder((Icon) null, button.title(), tapIntent(f, button.id(), button.reply()));
                if (button.reply()) action.addRemoteInput(new RemoteInput.Builder("reply").setLabel(button.title()).build());
                b.addAction(action.build());
            }
        }
        return b.build();
    }
    private PendingIntent tapIntent(NotificationSyncCodec.Frame f, String button, boolean reply) {
        String token = UUID.randomUUID().toString();
        taps.put(token, new Tap(f.key(), f.revision(), button, reply, generation));
        Intent intent = new Intent(context, NotificationSyncActionReceiver.class).setAction("io.github.qiangjian.connect.NOTIFICATION_ACTION")
                .setData(Uri.parse("qj-notification://action/" + token));
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
                | (reply ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE));
        intents.computeIfAbsent(f.key(), ignored -> new ArrayList<>()).add(pi); return pi;
    }
    CompletableFuture<Void> tapped(Intent intent) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(failure("closed"));
        // Copy only the expected remote input, never accept fill-in extras as routing/authorization.
        Uri uri = intent.getData(); Bundle input = RemoteInput.getResultsFromIntent(intent);
        String reply = input == null || input.getCharSequence("reply") == null ? "" : input.getCharSequence("reply").toString();
        worker.execute(() -> {
            try {
                Tap tap = uri == null ? null : taps.get(uri.getLastPathSegment());
                if (tap == null || tap.generation != generation) throw failure("stale_target");
                ledger.requireActive(tap.key, tap.revision); require(NotificationSyncCodec.OP_ACTION);
                if (!tap.reply && !reply.isEmpty()) throw failure("invalid_reply");
                // One tap token is consumed once, including on uncertain timeout: never replay a side effect.
                taps.remove(uri.getLastPathSegment());
                send(NotificationSyncCodec.action(tap.key, tap.revision, tap.button, reply))
                        .whenComplete((v, e) -> { if (e == null) result.complete(null); else result.completeExceptionally(e); });
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        });
        return result;
    }
    private void perform(NotificationSyncCodec.Frame f, CompletableFuture<byte[]> result) {
        if (!accessGranted()) throw failure("access_denied");
        Source source = sources.values().stream().filter(s -> s.token.equals(f.key())).findFirst().orElseThrow(() -> failure("unknown_target"));
        if (source.removed || source.revision != f.revision()) throw failure("stale_target");
        StatusBarNotification n = source.notification;
        if (!grants.allowsPackage(n.getPackageName())) throw failure("package_denied");
        if (!n.getUser().equals(android.os.Process.myUserHandle())) throw failure("profile_denied");
        NotificationListenerService listener = nativeSource == null ? NotificationSyncListener.connected : nativeSource.listener();
        StatusBarNotification[] exact = listener.getActiveNotifications(new String[]{n.getKey()});
        if (exact == null || exact.length != 1 || exact[0].getPostTime() != n.getPostTime() || !sameActions(n, exact[0])) throw failure("stale_target");
        NotificationSyncCodec.Action action = f.action();
        NotificationSyncActionPolicy.requireAdvertised(source.advertised, action);
        long epoch = generation;
        if (action.button().equals("dismiss")) {
            if (n.isOngoing() || !action.reply().isEmpty()) throw failure("not_dismissible");
            source.revision++;
            listener.cancelNotification(n.getKey());
            awaitEffect(() -> {
                if (!accessGranted()) throw failure("access_denied");
                StatusBarNotification[] found = listener.getActiveNotifications(new String[]{n.getKey()});
                return found != null && found.length == 0;
            }, epoch, result, () -> {
                require(NotificationSyncCodec.OP_ACTION);
                result.complete(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(f, "removed")));
            }, 20);
            return;
        }
        PendingIntent target;
        Intent fill = null;
        if (action.button().equals("open")) {
            if (!action.reply().isEmpty()) throw failure("invalid_reply");
            target = n.getNotification().contentIntent;
        } else {
            int index;
            try { index = Integer.parseInt(action.button()); } catch (NumberFormatException e) { throw failure("invalid_action"); }
            Notification.Action[] buttons = n.getNotification().actions;
            if (buttons == null || index < 0 || index >= buttons.length || index >= 8) throw failure("invalid_action");
            Notification.Action button = buttons[index]; target = button.actionIntent;
            RemoteInput[] inputs = button.getRemoteInputs();
            if (inputs != null && inputs.length == 1 && inputs[0].getAllowFreeFormInput()) {
                if (action.reply().isEmpty()) throw failure("empty_reply");
                fill = new Intent(); Bundle values = new Bundle(); values.putCharSequence(inputs[0].getResultKey(), action.reply());
                RemoteInput.addResultsToIntent(inputs, fill, values);
            } else if (!action.reply().isEmpty()) throw failure("invalid_reply");
        }
        if (target == null || !n.getPackageName().equals(target.getCreatorPackage())) throw failure("invalid_pending_intent");
        Intent finalFill = fill;
        // Consume the advertised revision before dispatch; a repeated action cannot be executed twice.
        source.revision++;
        main.post(() -> {
            if (result.isDone() || closed || epoch != generation || source.removed || source.notification != n
                    || source.revision != f.revision() + 1 || !accessGranted()
                    || !grants.allowsPackage(n.getPackageName()) || !grants.allows(peer, session, NotificationSyncCodec.OP_ACTION)) {
                result.completeExceptionally(failure("stale_session")); return;
            }
            try {
                target.send(context, 0, finalFill, (pi, sent, code, data, extras) -> worker.execute(() -> {
                    if (epoch != generation) result.completeExceptionally(failure("stale_session"));
                    else if (!grants.allows(peer, session, NotificationSyncCodec.OP_ACTION)) result.completeExceptionally(failure("permission_denied"));
                    else result.complete(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(f, "pending_intent_finished")));
                }), main);
            } catch (PendingIntent.CanceledException | RuntimeException e) { result.completeExceptionally(failure("action_failed")); }
        });
        result.orTimeout(5, TimeUnit.SECONDS);
    }
    private NotificationSyncCodec.Post capture(StatusBarNotification n) {
        Notification value = n.getNotification(); Bundle extras = value.extras;
        List<NotificationSyncCodec.Button> buttons = new ArrayList<>();
        if (value.actions != null) for (int i = 0; i < Math.min(8, value.actions.length); i++) {
            Notification.Action b = value.actions[i]; RemoteInput[] inputs = b.getRemoteInputs();
            if (b.actionIntent == null || !n.getPackageName().equals(b.actionIntent.getCreatorPackage())) continue;
            boolean reply = inputs != null && inputs.length == 1 && inputs[0].getAllowFreeFormInput();
            // Multiple choice/data-only input needs a richer schema; do not expose a misleading plain button.
            if (inputs != null && inputs.length > 0 && !reply) continue;
            String title = b.title == null ? "" : b.title.toString();
            if (!title.isEmpty()) buttons.add(new NotificationSyncCodec.Button(Integer.toString(i), title, reply));
        }
        String label = n.getPackageName(); Drawable icon = null;
        try {
            var info = context.getPackageManager().getApplicationInfo(n.getPackageName(), 0);
            label = context.getPackageManager().getApplicationLabel(info).toString(); icon = info.loadIcon(context.getPackageManager());
        } catch (PackageManager.NameNotFoundException ignored) { }
        Bitmap picture = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class);
        return new NotificationSyncCodec.Post(label, text(extras, Notification.EXTRA_TITLE), text(extras, Notification.EXTRA_TEXT),
                text(extras, Notification.EXTRA_BIG_TEXT), value.when, n.isOngoing(), value.contentIntent != null,
                image(icon), image(picture, 4096), buttons);
    }
    private static String text(Bundle b, String key) { CharSequence value = b.getCharSequence(key); return value == null ? "" : value.toString(); }
    private static boolean sameActions(StatusBarNotification a, StatusBarNotification b) {
        if (a == null || b == null) return false;
        Notification x = a.getNotification(), y = b.getNotification();
        if (!java.util.Objects.equals(x.contentIntent, y.contentIntent)) return false;
        int nx = x.actions == null ? 0 : x.actions.length, ny = y.actions == null ? 0 : y.actions.length;
        if (nx != ny) return false;
        for (int i = 0; i < nx; i++) {
            if (!java.util.Objects.equals(x.actions[i].actionIntent, y.actions[i].actionIntent)) return false;
            RemoteInput[] xi = x.actions[i].getRemoteInputs(), yi = y.actions[i].getRemoteInputs();
            int lx = xi == null ? 0 : xi.length, ly = yi == null ? 0 : yi.length;
            if (lx != ly) return false;
            for (int j = 0; j < lx; j++)
                if (!xi[j].getResultKey().equals(yi[j].getResultKey()) || xi[j].getAllowFreeFormInput() != yi[j].getAllowFreeFormInput()) return false;
        }
        return true;
    }
    private static byte[] image(Drawable drawable) {
        if (drawable == null) return new byte[0];
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 64, 64); drawable.draw(new Canvas(bitmap));
        try { return image(bitmap, 2048); } finally { bitmap.recycle(); }
    }
    private static byte[] image(Bitmap bitmap, int limit) {
        if (bitmap == null) return new byte[0];
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        for (int dimension = Math.min(256, max); dimension >= 16; dimension /= 2) {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * dimension / max), Math.max(1, bitmap.getHeight() * dimension / max), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (small != bitmap) small.recycle();
            if (out.size() <= limit) return out.toByteArray();
        }
        throw failure("image_too_large");
    }
    private static Bitmap decodeImage(byte[] bytes) {
        if (bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 512 || bounds.outHeight > 512)
            throw failure("invalid_image");
        Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (result == null) throw failure("invalid_image"); return result;
    }
    static byte[] nativeImage(byte[] bytes, int limit) {
        if (bytes.length == 0) return bytes;
        if (bytes.length > 512 * 1024) throw failure("image_too_large");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 2048 || bounds.outHeight > 2048)
            throw failure("invalid_image");
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (decoded == null) throw failure("invalid_image");
        try { return image(decoded, limit); } finally { decoded.recycle(); }
    }
    private boolean present(String tag, long revision) {
        for (StatusBarNotification n : manager.getActiveNotifications())
            if (tag.equals(n.getTag()) && n.getId() == 1 && (revision < 0 || n.getNotification().extras.getLong("qj.revision") == revision)) return true;
        return false;
    }
    private void awaitEffect(BooleanSupplier check, long epoch, CompletableFuture<?> result, Runnable done, int tries) {
        if (result.isDone()) return;
        try {
            if (generation != epoch || closed) throw failure("stale_session");
            if (check.getAsBoolean()) { done.run(); return; }
            if (tries == 0) throw failure("effect_unverified");
            worker.schedule(() -> awaitEffect(check, epoch, result, done, tries - 1), 100, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { result.completeExceptionally(e); }
    }
    private void require(String operation) {
        if (closed || session.isEmpty() || !grants.allows(peer, session, operation)) throw failure("permission_denied");
    }
    private void track(CompletableFuture<?> f) {
        if (pending.size() >= 64) throw failure("capacity");
        pending.add(f); f.whenCompleteAsync((v, e) -> { pending.remove(f); cancellations.remove(f); }, worker);
    }
    private void cancelIntents(String key) {
        List<PendingIntent> old = intents.remove(key); if (old != null) for (PendingIntent pi : old) pi.cancel();
        taps.values().removeIf(t -> t.key.equals(key));
    }
    private String tag(String key) { return PREFIX + session + "." + key; }
    private void reset() {
        generation++;
        synchronized (notificationEvents) { notificationEvents.clear(); }
        for (Runnable cancel : new ArrayList<>(cancellations.values())) try { cancel.run(); } catch (RuntimeException ignored) { }
        cancellations.clear();
        for (CompletableFuture<?> f : new ArrayList<>(pending)) f.completeExceptionally(failure("stale_session"));
        pending.clear();
        for (String key : new ArrayList<>(intents.keySet())) cancelIntents(key);
        for (String tag : tags) manager.cancel(tag, 1);
        sources.clear(); incoming.clear(); tags.clear(); ledger.clear(); requests.clear();
    }
    private void submit(Runnable action) {
        if (closed) return;
        worker.execute(() -> { if (!closed) try { action.run(); } catch (RuntimeException error) {
            android.util.Log.w("QjNotification", "notification_operation_rejected"); // Never log notification content or exception messages.
        }});
    }
    private void notificationEvent(String key, Runnable action) {
        if (closed) return;
        synchronized (notificationEvents) {
            // Coalesce updates by the full local key and retain at most 128 snapshots while the worker is busy.
            if (!notificationEvents.containsKey(key) && notificationEvents.size() >= 128) return;
            notificationEvents.put(key, action);
            if (drainScheduled) return;
            drainScheduled = true;
        }
        submit(() -> {
            List<Runnable> batch;
            synchronized (notificationEvents) {
                batch = new ArrayList<>(notificationEvents.values()); notificationEvents.clear(); drainScheduled = false;
            }
            for (Runnable event : batch) try { event.run(); }
            catch (RuntimeException failure) { rejectedTransfers++; }
        });
    }
    private static IllegalStateException failure(String code) { return new IllegalStateException("notification_" + code); }
    @Override public void close() {
        if (closed) return; closed = true; if (active == this) active = null;
        worker.execute(() -> { reset(); peer = ""; session = ""; worker.shutdown(); });
    }
}
