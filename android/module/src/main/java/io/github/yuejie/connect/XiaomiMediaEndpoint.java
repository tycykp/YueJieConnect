package io.github.yuejie.connect;

import android.content.Context;
import android.media.AudioManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Must be instantiated inside verified com.xiaomi.mirror, not com.milink.service. */
final class XiaomiMediaEndpoint extends MediaControlEndpoint {
    private final Method volume;
    XiaomiMediaEndpoint(Context context, ClassLoader loader) throws ReflectiveOperationException {
        super(context, "com.xiaomi.mirror");
        volume = Class.forName("com.xiaomi.mirror.audio.AudioUtils", false, loader)
                .getMethod("setStreamVolume", int.class, int.class);
    }
    @Override protected void setMusicVolume(int value) throws Exception {
        try { volume.invoke(null, AudioManager.STREAM_MUSIC, value); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof SecurityException denied) throw denied;
            throw failed;
        }
        // OEM method catches some errors itself. MediaControlSession still requires readback.
    }
}
