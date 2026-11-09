package io.github.yuejie.connect;

/** A peer may request only the exact actions advertised for this source notification revision. */
final class NotificationSyncActionPolicy {
    static void requireAdvertised(NotificationSyncCodec.Post post, NotificationSyncCodec.Action action) {
        boolean permitted;
        if ("open".equals(action.button())) permitted = post.openable() && action.reply().isEmpty();
        else if ("dismiss".equals(action.button())) permitted = !post.ongoing() && action.reply().isEmpty();
        else permitted = post.buttons().stream().anyMatch(b -> b.id().equals(action.button())
                && (b.reply() ? !action.reply().isEmpty() : action.reply().isEmpty()));
        if (!permitted) throw new IllegalArgumentException("notification_action_not_advertised");
    }
}
