package io.github.yuejie.connect;

import java.lang.reflect.Method;
import org.json.JSONObject;

/** Value conversion only; caller must supply an authenticated exact-peer route and fresh state. */
final class XiaomiMediaAdapter {
    private final Class<?> media, ctrl, castVolume;
    private final Method result;
    XiaomiMediaAdapter(ClassLoader loader) throws ReflectiveOperationException {
        media = Class.forName("com.xiaomi.mirror.message.MediaSessionControlMessage", false, loader);
        ctrl = Class.forName("com.xiaomi.mirror.message.CtrlMessage", false, loader);
        castVolume = Class.forName("com.xiaomi.mirror.message.VolumeValueMessage", false, loader);
        result = media.getMethod("generateResultMsg", long.class, boolean.class);
    }
    /** Only for an exact peer's already-authorized phone/pad cast session. Value is percent. */
    JSONObject castVolumeRequest(Object nativeMessage, JSONObject remoteState) throws Exception {
        if (nativeMessage == null || nativeMessage.getClass() != castVolume) throw new IllegalArgumentException("invalid_message");
        int level = MediaControlSession.volumeFromPercent(castVolume.getField("value").getInt(nativeMessage),
                remoteState.getInt("max_volume"));
        if (level < remoteState.getInt("min_volume")) throw new IllegalArgumentException("volume_out_of_range");
        return new JSONObject().put("action", "SET_VOLUME").put("value", level);
    }
    JSONObject request(Object nativeMessage, JSONObject remoteState) throws Exception {
        if (nativeMessage != null && nativeMessage.getClass() == ctrl) {
            if (ctrl.getField("ctrlType").getInt(nativeMessage) != 1)
                throw new IllegalArgumentException("unsupported_control");
            int level = MediaControlSession.volumeFromBasisPoints(ctrl.getField("value").getInt(nativeMessage),
                    remoteState.getInt("min_volume"), remoteState.getInt("max_volume"));
            return new JSONObject().put("action", "SET_VOLUME").put("value", level);
        }
        if (nativeMessage == null || nativeMessage.getClass() != media) throw new IllegalArgumentException("invalid_message");
        Object action = media.getField("action").get(nativeMessage);
        if (!(action instanceof Enum<?>)) throw new IllegalArgumentException("invalid_action");
        String name = ((Enum<?>) action).name();
        name = switch (name) {
            case "PLAY", "PAUSE", "STOP", "SEEK", "NEXT" -> name;
            case "PRE" -> "PREVIOUS";
            case "FORWARD" -> "FAST_FORWARD";
            case "REWIND" -> "REWIND";
            default -> throw new IllegalArgumentException("unsupported_control");
        };
        JSONObject payload = new JSONObject().put("action", name)
                .put("package", media.getField("packageName").get(nativeMessage))
                .put("media_token", remoteState.getString("media_token"));
        if ("SEEK".equals(name)) payload.put("value", media.getField("position").getLong(nativeMessage));
        MediaControlEndpoint.decode(payload);
        return payload;
    }
    Object result(long nativeSessionId, JSONObject response) throws ReflectiveOperationException {
        boolean ok = MediaControlWire.confirmed(response);
        return result.invoke(null, nativeSessionId, ok);
    }
}
