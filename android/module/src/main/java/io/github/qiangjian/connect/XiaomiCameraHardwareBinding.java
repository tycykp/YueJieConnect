package io.github.qiangjian.connect;

import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Binds the actual OEM selection to an authenticated peer and its separately negotiated Camera2 id. */
final class XiaomiCameraHardwareBinding {
    static final String HARDWARE = "com.xiaomi.dist.hardware.data.HardwareInfo";
    static final String CAMERA = "com.xiaomi.dist.camera.kit.VirtualCamera";
    interface PeerPermit {
        // Attest the explicit remote source mapping as well as the peer. Pairing alone is insufficient.
        boolean valid(String sessionId, String peerId, String nativeDeviceId, String dhId,
                      int nativeCameraId, String sourceCameraId);
    }

    record Selection(String sessionId, String peerId, String nativeDeviceId, String dhId,
                     int nativeCameraId, String nativeFacing, CameraShareConfig.Camera source) {
        Selection {
            new CameraShareLease(sessionId, peerId);
            new CameraShareLease(nativeDeviceId, dhId);
            Objects.requireNonNull(source);
            if (nativeCameraId < 0 || nativeFacing == null || nativeFacing.isBlank()
                    || source.id().isBlank() || source.modes().isEmpty()
                    || (source.facing() != 0 && source.facing() != 1)
                    || source.sensorOrientation() < 0 || source.sensorOrientation() >= 360
                    || source.sensorOrientation() % 90 != 0)
                throw new IllegalArgumentException("invalid_camera_hardware_binding");
        }

        void validate(String session, String peer, String device, String hardwareId, int camera,
                      PeerPermit permit) {
            if (!sessionId.equals(session) || !peerId.equals(peer) || !nativeDeviceId.equals(device)
                    || !dhId.equals(hardwareId) || nativeCameraId != camera
                    || !permit.valid(session, peer, device, dhId, nativeCameraId, source.id()))
                throw new SecurityException("camera_hardware_peer_mismatch");
        }

        CameraShareConfig preview(int width, int height, int fps, int bitrate) {
            CameraShareConfig config = new CameraShareConfig(source.id(), width, height, fps, bitrate);
            config.negotiate(List.of(source));
            return config;
        }
    }

    /** source is a current authenticated remote catalog entry; never derive it by parsing nativeCameraId. */
    static Selection bind(ClassLoader loader, String sessionId, String peerId, Object hardware,
                          Object selectedCamera, CameraShareConfig.Camera source, PeerPermit permit)
            throws ReflectiveOperationException, JSONException {
        Class<?> hardwareType = Class.forName(HARDWARE, false, loader);
        Class<?> cameraType = Class.forName(CAMERA, false, loader);
        if (!hardwareType.isInstance(hardware) || !cameraType.isInstance(selectedCamera))
            throw new IllegalArgumentException("invalid_native_camera_hardware");
        String device = (String) hardwareType.getMethod("getDeviceId").invoke(hardware);
        String dhId = (String) hardwareType.getMethod("getDhId").invoke(hardware);
        Object dhType = hardwareType.getMethod("getDhType").invoke(hardware);
        if (!(dhType instanceof Enum<?> type) || !"CAMERA".equals(type.name()))
            throw new IllegalArgumentException("native_hardware_is_not_camera");
        int cameraId = (int) cameraType.getMethod("getCameraId").invoke(selectedCamera);
        String facing = (String) cameraType.getMethod("getFacing").invoke(selectedCamera);
        Selection selection = new Selection(sessionId, peerId, device, dhId, cameraId, facing, source);
        selection.validate(sessionId, peerId, (String) cameraType.getMethod("getDeviceId").invoke(selectedCamera),
                (String) cameraType.getMethod("getDhId").invoke(selectedCamera), cameraId, permit);
        if (!hardwareType.getMethod("getDeviceType").invoke(hardware)
                .equals(cameraType.getMethod("getDeviceType").invoke(selectedCamera)))
            throw new SecurityException("camera_hardware_device_type_mismatch");
        String attr = (String) hardwareType.getMethod("getDhAttr").invoke(hardware);
        if (attr == null || attr.length() > 65536) throw new IllegalArgumentException("invalid_camera_hardware_attributes");
        JSONArray cameras = new JSONObject(attr).getJSONArray("attr");
        if (cameras.length() < 1 || cameras.length() > 16) throw new IllegalArgumentException("invalid_camera_hardware_attributes");
        int matches = 0;
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            if (camera.getInt("cameraId") == cameraId) {
                if (!facing.equals(camera.getString("facing"))) throw new SecurityException("camera_hardware_facing_mismatch");
                matches++;
            }
        }
        if (matches != 1) throw new SecurityException("camera_hardware_selection_missing_or_ambiguous");
        return selection;
    }
}
