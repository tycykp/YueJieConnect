package io.github.qiangjian.connect;

import java.util.List;
import java.util.Objects;

/** Camera source configuration. A screen/display id is never a camera id. */
final class CameraShareConfig {
    static final String MIME = "video/avc";
    final String cameraId;
    final int width, height, fps, bitrate;

    CameraShareConfig(String cameraId, int width, int height, int fps, int bitrate) {
        if (cameraId == null || cameraId.isBlank() || cameraId.length() > 128
                || width < 160 || height < 120 || width > 1920 || height > 1920
                || (long) width * height > 1920L * 1080 || (width & 1) != 0 || (height & 1) != 0
                || fps < 1 || fps > 30 || bitrate < 64_000 || bitrate > 12_000_000)
            throw new IllegalArgumentException("invalid_camera_configuration");
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }

    record Mode(int width, int height, int minFps, int maxFps) {
        Mode {
            if (width < 1 || height < 1 || minFps < 1 || maxFps < minFps)
                throw new IllegalArgumentException("invalid_camera_mode");
        }
        boolean supports(CameraShareConfig config) {
            return width == config.width && height == config.height
                    && minFps <= config.fps && maxFps == config.fps;
        }
    }

    record Camera(String id, int facing, int sensorOrientation, List<Mode> modes) {
        Camera {
            Objects.requireNonNull(id);
            modes = List.copyOf(modes);
        }
    }

    Mode negotiate(List<Camera> cameras) {
        for (Camera camera : cameras) {
            if (!cameraId.equals(camera.id)) continue;
            return camera.modes.stream().filter(mode -> mode.supports(this))
                    .min(java.util.Comparator.comparingInt(mode -> mode.maxFps - mode.minFps))
                    .orElseThrow(() -> new IllegalArgumentException("camera_configuration_unavailable"));
        }
        throw new IllegalArgumentException("camera_unavailable");
    }
}
