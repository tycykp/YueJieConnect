package io.github.yuejie.connect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Scene MUSIC conversion; PLAY=toggle, FORWARD=+30s, BACKWARD=-15s in this sample. */
final class OplusMediaAdapter {
    private final Method parseVolume, getVolume;
    private final Constructor<?> result;
    OplusMediaAdapter(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> volume = Class.forName("com.oplus.synergy.scene.common.proto.SceneSenseProto$VolumeControlPayload", false, loader);
        parseVolume = volume.getMethod("parseFrom", byte[].class);
        getVolume = volume.getMethod("getVolume");
        result = Class.forName("com.oplus.synergy.scene.common.model.ControlResult", false, loader)
                .getConstructor(boolean.class, String.class, int.class);
    }
    JSONObject request(String scene, int command, byte[] payload, String targetPackage, JSONObject remoteState) throws Exception {
        if (!"MUSIC".equals(scene)) throw new IllegalArgumentException("unsupported_scene");
        if (payload == null || payload.length > 1024) throw new IllegalArgumentException("invalid_payload");
        if (command == 103) {
            int level = ((Number) getVolume.invoke(parseVolume.invoke(null, (Object) payload))).intValue();
            if (level < remoteState.getInt("min_volume") || level > remoteState.getInt("max_volume"))
                throw new IllegalArgumentException("volume_out_of_range");
            return new JSONObject().put("action", "SET_VOLUME").put("value", level);
        }
        String action = switch (command) {
            case 101 -> "NEXT";
            case 102 -> "PREVIOUS";
            case 106 -> "TOGGLE";
            case 107 -> "PAUSE";
            case 104 -> "SEEK_FORWARD";
            case 105 -> "SEEK_BACKWARD";
            default -> throw new IllegalArgumentException("unsupported_control");
        };
        JSONObject request = new JSONObject().put("action", action).put("package", targetPackage)
                .put("media_token", remoteState.getString("media_token"));
        MediaControlEndpoint.decode(request);
        return request;
    }
    Object result(JSONObject response) throws ReflectiveOperationException {
        boolean ok = MediaControlWire.confirmed(response);
        return result.newInstance(ok, ok ? "" : "remote_media_unconfirmed", ok ? 0 : 105);
    }
}
