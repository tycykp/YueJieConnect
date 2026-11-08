package io.github.qiangjian.connect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Debug-only artificial notifications. No enumeration, copying, or logging of personal notifications. */
public final class NotificationSyncFixture extends BroadcastReceiver {
    private static final String CHANNEL = "qj_notification_test", TAG = "qj.synthetic.notification";
    // Both are fixed artificial inputs. ASCII can be entered with the platform shell keyboard,
    // without changing an IME or touching the user's clipboard.
    private static final List<String> REPLIES = List.of("人工回复", "QJ-test-reply");
    private static final AtomicInteger actions = new AtomicInteger(), replies = new AtomicInteger();
    private static final Map<Integer, List<PendingIntent>> intents = new HashMap<>();
    private static final Map<Integer, Integer> revisions = new HashMap<>();
    static synchronized void post(Context context, int id, int revision) {
        if (id < 900000 || id > 900007 || revision < 1 || revision > 5) throw new IllegalArgumentException("invalid_synthetic_notification");
        cancelIntents(id);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "合成通知验证", NotificationManager.IMPORTANCE_DEFAULT));
        Intent action = new Intent(context, NotificationSyncFixture.class).setAction("qj.synthetic.action")
                .setData(Uri.parse("qj-fixture://action/" + id + "/" + revision));
        Intent reply = new Intent(context, NotificationSyncFixture.class).setAction("qj.synthetic.reply")
                .setData(Uri.parse("qj-fixture://reply/" + id + "/" + revision));
        PendingIntent button = PendingIntent.getBroadcast(context, 0, action, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent replyButton = PendingIntent.getBroadcast(context, 0, reply, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        intents.put(id, List.of(button, replyButton)); revisions.put(id, revision);
        Bitmap picture = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); picture.eraseColor(Color.BLUE);
        Bundle extras = new Bundle(); extras.putInt("qj.synthetic.revision", revision);
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle("强兼合成通知 " + id).setContentText("人工测试版本 " + revision)
                .addExtras(extras)
                .setStyle(new Notification.BigPictureStyle().bigPicture(picture))
                .addAction(new Notification.Action.Builder((Icon) null, "测试动作", button).build())
                .addAction(new Notification.Action.Builder((Icon) null, "测试回复", replyButton)
                        .addRemoteInput(new RemoteInput.Builder("synthetic_reply").setLabel("仅输入人工测试文本").build()).build())
                .build();
        manager.notify(TAG, id, notification);
    }
    static synchronized void remove(Context context, int id) {
        if (id < 900000 || id > 900007) throw new IllegalArgumentException("invalid_synthetic_notification");
        cancelIntents(id); revisions.remove(id); context.getSystemService(NotificationManager.class).cancel(TAG, id);
    }
    static int actionCount() { return actions.get(); }
    static int replyCount() { return replies.get(); }
    static synchronized void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (var n : manager.getActiveNotifications()) if (TAG.equals(n.getTag())) manager.cancel(TAG, n.getId());
        for (int id : new java.util.ArrayList<>(intents.keySet())) cancelIntents(id);
        revisions.clear();
        actions.set(0); replies.set(0);
    }
    private static void cancelIntents(int id) {
        var old = intents.remove(id); if (old != null) for (PendingIntent value : old) value.cancel();
    }
    /** Debug owner-only IPC may call by reflection; release APK has no such class. No free text/URI inputs. */
    public static synchronized JSONObject execute(Context context, JSONObject request) throws JSONException {
        if (request == null || !"notification_fixture".equals(request.opt("op"))) throw new IllegalArgumentException("invalid_synthetic_notification");
        Object action = request.opt("action");
        if (!(action instanceof String name)) throw new IllegalArgumentException("invalid_synthetic_notification");
        switch (name) {
            case "post" -> {
                if (request.length() != 4) throw new IllegalArgumentException("invalid_synthetic_notification");
                post(context, integer(request, "id"), integer(request, "revision"));
            }
            case "remove" -> {
                if (request.length() != 3) throw new IllegalArgumentException("invalid_synthetic_notification");
                remove(context, integer(request, "id"));
            }
            case "clear" -> {
                if (request.length() != 2) throw new IllegalArgumentException("invalid_synthetic_notification");
                clear(context);
            }
            case "state" -> { if (request.length() != 2) throw new IllegalArgumentException("invalid_synthetic_notification"); }
            default -> throw new IllegalArgumentException("invalid_synthetic_notification");
        }
        // The caller polls to observe actual OS presence/removal; notify() returning is not delivery proof.
        JSONArray local = new JSONArray(), mirrors = new JSONArray(), actionState = new JSONArray();
        for (var n : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (TAG.equals(n.getTag()) && n.getId() >= 900000 && n.getId() <= 900007) {
                local.put(new JSONObject().put("id", n.getId()).put("revision", n.getNotification().extras.getInt("qj.synthetic.revision")));
                actionState.put(actionState(n.getNotification(), n.getId(), false));
            }
            if (n.getTag() == null || !n.getTag().startsWith("qj.nsync.") || !n.getNotification().extras.getBoolean("qj.notification.mirrored")) continue;
            Bundle extras = n.getNotification().extras;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE), text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null || text == null || !title.toString().matches("强兼合成通知 90000[0-7]") || !text.toString().matches("人工测试版本 [1-5]")) continue;
            int id = Integer.parseInt(title.toString().substring("强兼合成通知 ".length()));
            mirrors.put(new JSONObject().put("id", id)
                    .put("revision", Integer.parseInt(text.toString().substring("人工测试版本 ".length()))));
            actionState.put(actionState(n.getNotification(), id, true));
        }
        NotificationSyncRuntime runtime = NotificationSyncRuntime.active;
        return new JSONObject().put("status", "ok").put("synthetic", local).put("synthetic_mirrors", mirrors)
                .put("synthetic_actions", actionState)
                .put("action_count", actions.get()).put("reply_count", replies.get())
                .put("access_granted", runtime != null && runtime.accessGranted()).put("posting_granted", runtime != null && runtime.postingGranted());
    }
    private static JSONObject actionState(Notification notification, int id, boolean mirror) throws JSONException {
        JSONArray buttons = new JSONArray();
        if (notification.actions != null) for (Notification.Action action : notification.actions) {
            String title = action.title == null ? "" : action.title.toString();
            // Even for a synthetic marker, never return arbitrary labels or PendingIntent identity.
            if (!List.of("测试动作", "测试回复", "在来源设备移除").contains(title)) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            buttons.put(new JSONObject().put("title", title).put("pending_intent", action.actionIntent != null)
                    .put("free_form_reply", inputs != null && inputs.length == 1 && inputs[0].getAllowFreeFormInput()));
        }
        return new JSONObject().put("id", id).put("mirror", mirror).put("buttons", buttons);
    }
    private static int integer(JSONObject request, String key) {
        Object value = request.opt(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException("invalid_synthetic_notification"); return (Integer) value;
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        var path = intent.getData().getPathSegments();
        if (!"qj-fixture".equals(intent.getData().getScheme()) || path.size() != 2) return;
        try {
            int id = Integer.parseInt(path.get(0)), revision = Integer.parseInt(path.get(1));
            synchronized (NotificationSyncFixture.class) {
                if (!Integer.valueOf(revision).equals(revisions.get(id))) return;
                if ("action".equals(intent.getData().getHost()) && "qj.synthetic.action".equals(intent.getAction())) actions.incrementAndGet();
                if ("reply".equals(intent.getData().getHost()) && "qj.synthetic.reply".equals(intent.getAction())) {
                    Bundle values = RemoteInput.getResultsFromIntent(intent);
                    if (values != null && REPLIES.contains(values.getCharSequence("synthetic_reply", "").toString())) replies.incrementAndGet();
                }
            }
        } catch (NumberFormatException invalid) { return; }
    }
}
