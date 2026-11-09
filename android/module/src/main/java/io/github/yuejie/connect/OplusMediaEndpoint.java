package io.github.yuejie.connect;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Uses the same native VolumeController as MUSIC/LONG_VIDEO/SHORT_VIDEO actions. */
final class OplusMediaEndpoint extends MediaControlEndpoint {
    private final Object volumeController;
    private final Method volume;
    OplusMediaEndpoint(Context context, ClassLoader loader) throws ReflectiveOperationException {
        super(context, "com.oplus.linker");
        Class<?> type = Class.forName("com.oplus.synergy.scene.platform.executor.VolumeController", false, loader);
        volumeController = type.getField("INSTANCE").get(null);
        volume = type.getMethod("setVolume", int.class, boolean.class);
    }
    @Override protected void setMusicVolume(int value) throws Exception {
        try {
            if (!Boolean.TRUE.equals(volume.invoke(volumeController, value, false)))
                throw new IllegalStateException("native_rejected");
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof SecurityException denied) throw denied;
            throw failed;
        }
    }
}
