package io.github.yuejie.connect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Converter for the inspected HyperOS Mirror message 26/27 schema. Does not invent a receiver/provider. */
final class XiaomiNotificationCodec {
    private static final String POST = "com.xiaomi.mirror.message.NotificationPostMessage";
    private static final String INTERACTION = "com.xiaomi.mirror.message.NotificationInteractionMessage";
    /** key is an opaque token bound to an exact StatusBarNotification key, never OEM package~id alone. */
    static NotificationSyncCodec.Frame post(Object message, String key, long revision) throws ReflectiveOperationException {
        return post(message, key, revision, (bytes, limit) -> bytes);
    }
    static NotificationSyncCodec.Frame post(Object message, String key, long revision,
            BiFunction<byte[], Integer, byte[]> normalizeImage) throws ReflectiveOperationException {
        check(message, POST);
        int style = number(message, "style");
        if (style < 1 || style > 5) throw new IllegalArgumentException("xiaomi_notification_style_unavailable");
        List<NotificationSyncCodec.Button> buttons = new ArrayList<>();
        Object rawButtons = field(message, "buttons");
        if (rawButtons instanceof List<?> list) {
            if (list.size() > 8) throw new IllegalArgumentException("notification_invalid_payload");
            for (Object b : list) {
                String id = string(b, "id");
                if (!id.matches("[0-7]")) throw new IllegalArgumentException("notification_invalid_action");
                int type = number(b, "type");
                if (type != 1 && type != 2) throw new IllegalArgumentException("notification_invalid_action");
                String title = string(b, "text");
                if (!title.isEmpty()) buttons.add(new NotificationSyncCodec.Button(id, title, type == 2));
            }
        }
        String bigText = string(message, "bigText");
        if (style == 4 && field(message, "lines") instanceof List<?> lines) {
            if (lines.size() > 64) throw new IllegalArgumentException("notification_invalid_payload");
            List<String> text = new ArrayList<>();
            for (Object line : lines) { if (!(line instanceof String)) throw new IllegalArgumentException("notification_invalid_payload"); text.add((String) line); }
            bigText = String.join("\n", text);
        }
        if (style == 5 && field(message, "messages") instanceof List<?> messages) {
            if (messages.size() > 64) throw new IllegalArgumentException("notification_invalid_payload");
            List<String> text = new ArrayList<>();
            for (Object m : messages) text.add(string(m, "sender") + ": " + string(m, "text"));
            bigText = String.join("\n", text);
        }
        byte[] icon = bytes(field(message, "largeIcon"));
        if (icon.length == 0) icon = bytes(field(message, "smallIcon"));
        return NotificationSyncCodec.posted(key, revision, new NotificationSyncCodec.Post(string(message, "appName"),
                string(message, "title"), string(message, "text"), bigText, (Long) field(message, "when"),
                (Boolean) field(message, "ongoing"), (Boolean) field(message, "openable"), normalizeImage.apply(icon, 2048),
                normalizeImage.apply(bytes(field(message, "bigPicture")), 4096), buttons));
    }
    static NotificationSyncCodec.Frame removed(Object message, String key, long revision) throws ReflectiveOperationException {
        check(message, INTERACTION);
        if (number(message, "type") != 3) throw new IllegalArgumentException("notification_requires_exact_target");
        return NotificationSyncCodec.removed(key, revision);
    }
    static NotificationSyncCodec.Frame interaction(Object message, String key, long revision) throws ReflectiveOperationException {
        check(message, INTERACTION);
        int type = number(message, "type");
        String action = switch (type) {
            case 1, 2 -> string(message, "buttonId"); case 3 -> "dismiss"; case 6 -> "open";
            default -> throw new IllegalArgumentException("notification_requires_exact_target");
        };
        if ((type == 1 || type == 2) && !action.matches("[0-7]")) throw new IllegalArgumentException("notification_invalid_action");
        return NotificationSyncCodec.action(key, revision, action, type == 1 ? string(message, "replyText") : "");
    }
    static String nativeId(Object message) throws ReflectiveOperationException {
        if (message == null) throw new IllegalArgumentException("notification_invalid_payload");
        boolean post = POST.equals(message.getClass().getName());
        check(message, post ? POST : INTERACTION);
        return string(message, post ? "id" : "messageId");
    }
    private static byte[] bytes(Object value) throws ReflectiveOperationException {
        if (value == null) return new byte[0];
        int size = (Integer) value.getClass().getMethod("size").invoke(value);
        if (size < 0 || size > 512 * 1024) throw new IllegalArgumentException("notification_invalid_payload");
        // Captured protobuf AbstractC0552i.C(): final byte[] serialization, not a guessed toByteArray name.
        Object result = value.getClass().getMethod("C").invoke(value);
        if (!(result instanceof byte[])) throw new IllegalArgumentException("notification_invalid_payload");
        return (byte[]) result;
    }
    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getField(name); return field.get(instance);
    }
    private static int number(Object o, String field) throws ReflectiveOperationException { return (Integer) field(o, field); }
    private static String string(Object o, String field) throws ReflectiveOperationException {
        Object value = field(o, field); return value == null ? "" : (String) value;
    }
    private static void check(Object message, String name) {
        if (message == null || !name.equals(message.getClass().getName())) throw new IllegalArgumentException("notification_wrong_native_type");
    }
}
