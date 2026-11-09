package io.github.yuejie.connect;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Android binds this only after real notification-access consent. No shell/system-service registration bypass. */
public final class NotificationSyncListener extends NotificationListenerService {
    static volatile NotificationSyncListener connected;
    @Override public void onListenerConnected() { connected = this; }
    @Override public void onListenerDisconnected() {
        if (connected == this) connected = null;
        NotificationSyncRuntime r = NotificationSyncRuntime.active;
        if (r != null) r.accessLost();
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        NotificationSyncRuntime r = NotificationSyncRuntime.active;
        if (connected == this && r != null) r.posted(notification);
    }
    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        NotificationSyncRuntime r = NotificationSyncRuntime.active;
        if (connected == this && r != null) r.removed(notification);
    }
    @Override public void onDestroy() { onListenerDisconnected(); super.onDestroy(); }
}
