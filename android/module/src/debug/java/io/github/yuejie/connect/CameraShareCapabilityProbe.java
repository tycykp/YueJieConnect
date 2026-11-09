package io.github.yuejie.connect;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.os.Looper;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.util.Range;
import android.util.Size;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only shell metadata probe. Never calls openCamera, changes permissions, or produces pixels. */
public final class CameraShareCapabilityProbe {
    public static void main(String[] args) {
        try { inspect(); }
        catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("YJ_CAMERA_METADATA_FAILURE " + cause.getClass().getName() + ": " + cause.getMessage());
            StackTraceElement[] trace = cause.getStackTrace();
            for (int i = 0; i < Math.min(8, trace.length); i++) System.out.println("  at " + trace[i]);
            System.exit(1);
        }
    }

    private static void inspect() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        JSONObject result = new JSONObject().put("scope", "camera_metadata_only_no_capture");
        JSONObject grants = new JSONObject();
        for (String pkg : new String[]{"com.milink.service", "com.oplus.linker", "io.github.yuejie.connect"}) {
            try {
                context.getPackageManager().getApplicationInfo(pkg, 0);
                grants.put(pkg, context.getPackageManager().checkPermission(Manifest.permission.CAMERA, pkg)
                        == PackageManager.PERMISSION_GRANTED);
            } catch (PackageManager.NameNotFoundException absent) { grants.put(pkg, "absent"); }
        }
        result.put("camera_runtime_grants", grants);
        CameraManager manager = context.getSystemService(CameraManager.class);
        JSONArray cameras = new JSONArray();
        if (manager != null) for (String id : manager.getCameraIdList()) {
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            JSONObject camera = new JSONObject().put("id", id)
                    .put("facing", info.get(CameraCharacteristics.LENS_FACING))
                    .put("sensor_orientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION));
            StreamConfigurationMap map = null;
            try { map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); }
            catch (RuntimeException error) { camera.put("stream_map_error", error.getClass().getSimpleName()); }
            JSONArray sizes = new JSONArray(), fps = new JSONArray();
            if (map != null) {
                Size[] outputs = map.getOutputSizes(MediaCodec.class);
                if (outputs != null) for (Size size : outputs) {
                    if ((size.getWidth() == 640 && size.getHeight() == 480)
                            || (size.getWidth() == 1280 && size.getHeight() == 720))
                        sizes.put(new JSONObject().put("width", size.getWidth()).put("height", size.getHeight())
                                .put("min_duration_ns", map.getOutputMinFrameDuration(MediaCodec.class, size)));
                }
            }
            Range<Integer>[] ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) for (Range<Integer> range : ranges) {
                if (range.getUpper() <= 30) fps.put(new JSONArray().put(range.getLower()).put(range.getUpper()));
            }
            cameras.put(camera.put("avc_surface_sizes", sizes).put("ae_fps", fps));
        }
        result.put("cameras", cameras);
        CameraShareSource source = new CameraShareSource(context, (session, stream, camera) -> false,
                new CameraShareSource.Listener() {
                    public void captured(CameraShareLease lease, long pts) { throw new AssertionError("capture_forbidden_in_metadata_probe"); }
                    public void closed(CameraShareLease lease, String reason) { }
                });
        SurfaceTexture texture = new SurfaceTexture(false);
        Surface surface = new Surface(texture);
        try {
            try {
                JSONArray physical = new JSONArray();
                for (CameraShareConfig.Camera camera : source.cameras())
                    physical.put(new JSONObject().put("id", camera.id()).put("mode_count", camera.modes().size()));
                result.put("production_physical_catalogue", physical);
            } catch (SecurityException denied) { result.put("production_catalogue_denied", denied.getMessage()); }
            try {
                source.start("metadata-probe", "1", new CameraShareConfig("0", 640, 480, 30, 1_000_000), surface);
                throw new AssertionError("camera_open_without_consent");
            } catch (SecurityException denied) { result.put("production_start_denied", denied.getMessage()); }
        } finally {
            source.close(); surface.release(); texture.release();
        }
        for (int i = 0; i < 20; i++) {
            boolean alive = false;
            for (Thread candidate : Thread.getAllStackTraces().keySet())
                if (candidate.getName().equals("YjCameraSource") && candidate.isAlive()) alive = true;
            if (!alive) { result.put("production_source_thread_released", true); break; }
            Thread.sleep(50);
        }
        if (!result.optBoolean("production_source_thread_released")) throw new AssertionError("source_thread_leaked");
        System.out.println("YJ_CAMERA_METADATA " + result);
        System.exit(0);
    }
}
