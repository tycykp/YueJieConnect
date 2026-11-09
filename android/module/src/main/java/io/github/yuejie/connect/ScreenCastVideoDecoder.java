package io.github.yuejie.connect;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes onto a caller-owned native window. A start return value is never a frame receipt. */
public final class ScreenCastVideoDecoder implements AutoCloseable {
    public interface Listener {
        void onFrameRendered(long presentationTimeUs, long nanoTime);
        void onKeyFrameNeeded();
        void onFailure(String code);
        void onEnd();
    }
    private final MediaCodec codec;
    private final Surface surface;
    private final ScreenCastFrameQueue queue;
    private final Listener listener;
    private final HandlerThread callbacks = new HandlerThread("yj-avc-rendered");
    private final AtomicBoolean closed = new AtomicBoolean(), rendered = new AtomicBoolean();
    private final Thread worker;
    private volatile long lastKeyRequestNs;

    public ScreenCastVideoDecoder(long streamId, ScreenCastFormat format, Surface output, Listener listener) throws IOException {
        if (output == null || !output.isValid()) throw new IllegalArgumentException("native_surface_missing");
        surface = output; this.listener = Objects.requireNonNull(listener); queue = new ScreenCastFrameQueue(streamId);
        MediaFormat media = MediaFormat.createVideoFormat(ScreenCastFormat.MIME, format.width(), format.height());
        media.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ScreenCastPacket.MAX_PAYLOAD);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(media);
        if (name == null) throw new IOException("video_decoder_unavailable");
        codec = MediaCodec.createByCodecName(name); callbacks.start();
        try {
            codec.configure(media, output, null, 0);
            codec.setOnFrameRenderedListener((c, pts, nano) -> {
                if (!closed.get()) { rendered.set(true); this.listener.onFrameRendered(pts, nano); }
            }, new Handler(callbacks.getLooper()));
            codec.start();
        } catch (RuntimeException error) { codec.release(); callbacks.quitSafely(); throw error; }
        worker = new Thread(this::drain, "yj-avc-decoder"); worker.setDaemon(true); worker.start();
    }
    public ScreenCastFrameQueue.Offer offer(ScreenCastPacket packet) {
        ScreenCastFrameQueue.Offer result = queue.offer(packet);
        if (result == ScreenCastFrameQueue.Offer.NEED_KEY_FRAME) {
            long now = System.nanoTime();
            if (now - lastKeyRequestNs > 500_000_000L) { lastKeyRequestNs = now; listener.onKeyFrameNeeded(); }
        }
        return result;
    }
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.nanoTime() + 5_000_000_000L;
        ScreenCastPacket pending = null;
        try {
            while (!closed.get()) {
                if (!surface.isValid()) throw new IllegalStateException("native_surface_destroyed");
                if (!rendered.get() && System.nanoTime() > deadline) throw new IllegalStateException("first_frame_timeout");
                if (pending == null) pending = queue.poll();
                if (pending != null) {
                    if (pending.isEnd()) { listener.onEnd(); break; }
                    int input = codec.dequeueInputBuffer(2_000);
                    if (input >= 0) {
                        ByteBuffer buffer = Objects.requireNonNull(codec.getInputBuffer(input)); buffer.clear();
                        if (pending.size() > buffer.remaining()) throw new IllegalStateException("decoder_buffer_too_small");
                        pending.putPayload(buffer);
                        codec.queueInputBuffer(input, 0, pending.size(), pending.presentationTimeUs,
                                pending.isConfig() ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
                        pending = null;
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 5_000);
                if (output >= 0) codec.releaseOutputBuffer(output, true);
            }
        } catch (Exception error) {
            if (!closed.getAndSet(true)) listener.onFailure("video_decoder_failed");
        } finally {
            closed.set(true); queue.close();
            try { codec.stop(); } catch (RuntimeException ignored) { }
            try { codec.release(); } finally { callbacks.quitSafely(); }
        }
    }
    @Override public void close() {
        closed.set(true); queue.close();
        if (Thread.currentThread() != worker) {
            try { worker.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
