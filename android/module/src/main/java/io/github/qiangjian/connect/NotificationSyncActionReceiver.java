package io.github.qiangjian.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-exported; each PendingIntent names an opaque runtime binding, never a caller-selected source key. */
public final class NotificationSyncActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationSyncRuntime r = NotificationSyncRuntime.active;
        if (r == null || intent == null) return;
        PendingResult pending = goAsync();
        r.tapped(intent).whenComplete((ignored, error) -> pending.finish());
    }
}
