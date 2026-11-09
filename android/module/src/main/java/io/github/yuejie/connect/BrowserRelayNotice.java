package io.github.yuejie.connect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

/** A local click lease. The notification carries a session and offer ID, never the URL. */
final class BrowserRelayNotice {
    private static final int NOTIFICATION = 2;
    private record Entry(String session, String pkg, long expires) {}
    private final Context context;
    private final NotificationManager notifications;
    private Entry entry;
    private boolean clicked;

    BrowserRelayNotice(Context context) {
        this.context = context;
        notifications = context.getSystemService(NotificationManager.class);
    }

    synchronized void show(String session, String pkg, String peerName, String appLabel) {
        AppRelayRequest.packageName(pkg);
        boolean browser = BrowserRelayOffer.ownsPackage(pkg);
        String channel = browser ? "browser_relay" : "app_relay";
        String title = context.getString(browser ? R.string.browser_relay : R.string.app_relay_notice);
        String text = browser ? context.getString(R.string.browser_relay_received, peerName)
                : context.getString(R.string.app_relay_received, peerName, AppRelayCatalog.text(appLabel, 256));
        notifications.createNotificationChannel(new NotificationChannel(channel, title, NotificationManager.IMPORTANCE_HIGH));
        if (!notifications.areNotificationsEnabled()
                || notifications.getNotificationChannel(channel).getImportance() == NotificationManager.IMPORTANCE_NONE)
            throw new IllegalStateException("notification_unavailable");
        Intent open = new Intent(context, BrowserRelayOpenActivity.class).setData(Uri.fromParts("yj-browser", pkg, null))
                .putExtra("session", session).putExtra("package", pkg);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(pending).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setTimeoutAfter(BrowserRelayOffer.LIFETIME_MS).setAutoCancel(true).build();
        notifications.notify(NOTIFICATION, note);
        entry = new Entry(session, pkg, SystemClock.elapsedRealtime() + BrowserRelayOffer.LIFETIME_MS);
        clicked = false;
    }

    synchronized boolean click(String session, String pkg) {
        if (!live(session) || clicked || !entry.pkg.equals(pkg)) return false;
        clicked = true;
        return true;
    }

    synchronized String takeClick(String session) {
        if (!live(session) || !clicked) return null;
        String pkg = entry.pkg;
        clear();
        return pkg;
    }

    synchronized void hide(String session, String pkg) {
        if (entry != null && entry.session.equals(session) && entry.pkg.equals(pkg)) clear();
    }
    synchronized void session(String session) {
        if (entry != null && !live(session)) clear();
    }
    private boolean live(String session) {
        return entry != null && entry.session.equals(session) && SystemClock.elapsedRealtime() < entry.expires;
    }
    private void clear() {
        entry = null;
        clicked = false;
        notifications.cancel(NOTIFICATION);
    }
}
