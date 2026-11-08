package io.github.qiangjian.connect;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Shell diagnostic: synthetic EGL pattern -> production encoder/packet/decoder -> GPU pixel readback.
 * No screen/camera capture, UI, private content, or claimed OEM/native business completion. */
public final class ScreenCastCodecProbe {
    public static void main(String[] args) {
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace(System.err));
            android.os.Looper.prepareMainLooper(); run(); System.exit(0);
        }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }
    private static void run() throws Exception {
        int width = 320, height = 240;
        ScreenCastFormat format = new ScreenCastFormat(width, height, 30, 1_000_000);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger packets = new AtomicInteger(), images = new AtomicInteger(), rendered = new AtomicInteger();
        AtomicBoolean keyRequested = new AtomicBoolean(), recoveryKey = new AtomicBoolean();
        AtomicInteger recoveryConfigs = new AtomicInteger();
        int patterns = 0;
        HandlerThread imageThread = new HandlerThread("qj-probe-image"); imageThread.start();
        AtomicBoolean available = new AtomicBoolean();
        ScreenCastVideoEncoder[] encoderRef = new ScreenCastVideoEncoder[1];
        ScreenCastVideoDecoder decoder = null;
        ScreenCastVideoEncoder encoder = null;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY; EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE, readback = EGL14.EGL_NO_SURFACE;
        SurfaceTexture texture = null; Surface outputSurface = null;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            check(EGL14.eglInitialize(display, version, 0, version, 1), "egl_initialize");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, 0x3142, 1, EGL14.EGL_NONE}, 0,
                    configs, 0, 1, count, 0) && count[0] > 0, "egl_config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            readback = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
            check(EGL14.eglMakeCurrent(display, readback, readback, context), "egl_readback");
            int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textures[0]); texture.setDefaultBufferSize(width, height);
            texture.setOnFrameAvailableListener(t -> available.set(true), new Handler(imageThread.getLooper()));
            outputSurface = new Surface(texture);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 p; varying vec2 uv; uniform mat4 tx; void main(){gl_Position=vec4(p,0.,1.);uv=(tx*vec4((p+1.)*.5,0.,1.)).xy;}"));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                    "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 uv; uniform samplerExternalOES s; void main(){gl_FragColor=texture2D(s,uv);}"));
            GLES20.glLinkProgram(program); int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            check(linked[0] == 1, "gl_link");
            FloatBuffer vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            ByteBuffer pixel = ByteBuffer.allocateDirect(4); float[] transform = new float[16];
            System.out.println("QJ_SCREEN_STAGE decoder");
            decoder = new ScreenCastVideoDecoder(1, format, outputSurface, new ScreenCastVideoDecoder.Listener() {
                public void onFrameRendered(long pts, long nano) { rendered.incrementAndGet(); }
                public void onKeyFrameNeeded() { if (encoderRef[0] != null) encoderRef[0].requestKeyFrame(); }
                public void onFailure(String code) { failure.compareAndSet(null, code); }
                public void onEnd() { }
            });
            ScreenCastVideoDecoder receiver = decoder;
            System.out.println("QJ_SCREEN_STAGE encoder");
            encoder = new ScreenCastVideoEncoder(1, format, new ScreenCastVideoEncoder.Listener() {
                public boolean offer(ScreenCastPacket packet) {
                    packets.incrementAndGet();
                    if (keyRequested.get()) {
                        if (packet.isConfig()) recoveryConfigs.incrementAndGet();
                        if (packet.isKey() && recoveryConfigs.get() > 0) recoveryKey.set(true);
                    }
                    ScreenCastFrameQueue.Offer result = receiver.offer(ScreenCastPacket.decode(packet.encode()));
                    return result == ScreenCastFrameQueue.Offer.ACCEPTED;
                }
                public void onFailure(String code) { failure.compareAndSet(null, code); }
            });
            encoderRef[0] = encoder;
            System.out.println("QJ_SCREEN_STAGE egl");
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoder.inputSurface(), new int[]{EGL14.EGL_NONE}, 0);
            check(EGL14.eglMakeCurrent(display, surface, surface, context), "egl_current");
            GLES20.glViewport(0, 0, width, height);
            System.out.println("QJ_SCREEN_STAGE frames");
            for (int frame = 0; frame < 60 && failure.get() == null; frame++) {
                if (frame == 20) { keyRequested.set(true); encoder.requestKeyFrame(); }
                check(EGL14.eglMakeCurrent(display, surface, surface, context), "egl_write");
                float dark = frame < 30 ? 0.05f : 0.95f, light = 1f - dark;
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glClearColor(dark, dark, dark, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(width / 2, 0, width / 2, height);
                GLES20.glClearColor(light, light, light, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                EGLExt.eglPresentationTimeANDROID(display, surface, (frame + 1L) * 33_333_333L);
                check(EGL14.eglSwapBuffers(display, surface), "egl_swap");
                Thread.sleep(34);
                if (available.getAndSet(false)) {
                    check(EGL14.eglMakeCurrent(display, readback, readback, context), "egl_read");
                    texture.updateTexImage(); texture.getTransformMatrix(transform); images.incrementAndGet();
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glUseProgram(program);
                    int p = GLES20.glGetAttribLocation(program, "p");
                    GLES20.glEnableVertexAttribArray(p); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, vertices);
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "tx"), 1, false, transform, 0);
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glReadPixels(width / 4, height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
                    int a = Byte.toUnsignedInt(pixel.get(0)); pixel.position(0);
                    GLES20.glReadPixels(width * 3 / 4, height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
                    int b = Byte.toUnsignedInt(pixel.get(0)); pixel.position(0);
                    patterns |= a < 80 && b > 160 ? 1 : b < 80 && a > 160 ? 2 : 0;
                    check(GLES20.glGetError() == GLES20.GL_NO_ERROR, "gl_read_pixels");
                }
            }
            if (failure.get() != null || patterns != 3 || rendered.get() == 0 || !recoveryKey.get())
                throw new IllegalStateException("codec_probe_failed code=" + failure.get() + " patterns=" + patterns + " rendered=" + rendered.get());
            System.out.println("QJ_SCREEN_PROBE {\"status\":\"passed\",\"packets\":" + packets.get()
                    + ",\"decodedImages\":" + images.get() + ",\"renderCallbacks\":" + rendered.get()
                    + ",\"keyRequestConfigResent\":" + recoveryConfigs.get() + ",\"keyAfterConfig\":" + recoveryKey.get()
                    + ",\"syntheticPatterns\":2,\"width\":320,\"height\":240,\"nativeBusinessVerified\":false}");
        } finally {
            if (encoder != null) encoder.close();
            if (decoder != null) decoder.close();
            if (outputSurface != null) outputSurface.release();
            if (texture != null) texture.release();
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (readback != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, readback);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            imageThread.quitSafely(); imageThread.join(1500);
        }
    }
    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        check(compiled[0] == 1, "gl_compile"); return shader;
    }
    private static void check(boolean ok, String code) { if (!ok) throw new IllegalStateException(code); }
}
