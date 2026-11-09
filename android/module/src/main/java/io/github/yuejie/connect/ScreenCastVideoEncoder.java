package io.github.yuejie.connect;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real AVC encoder shared by authorized screen and camera sources. Owns its input Surface. */
public final class ScreenCastVideoEncoder implements AutoCloseable {
    public interface Listener {
        /** Nonblocking: false means transport is full. Never report remote rendering here. */
        boolean offer(ScreenCastPacket packet);
        void onFailure(String code);
    }
    private final MediaCodec codec;
    private final Surface surface;
    private final Listener listener;
    private final long streamId;
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean(), keyRequested = new AtomicBoolean();
    private long sequence, lastKeyRequestNs;
    private byte[] configuration;
    private final AtomicBoolean resendConfiguration = new AtomicBoolean();

    public ScreenCastVideoEncoder(long streamId, ScreenCastFormat format, Listener listener) throws IOException {
        if (streamId <= 0) throw new IllegalArgumentException("invalid_stream");
        this.streamId = streamId; this.listener = Objects.requireNonNull(listener);
        MediaFormat media = MediaFormat.createVideoFormat(ScreenCastFormat.MIME, format.width(), format.height());
        media.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        media.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate());
        media.setInteger(MediaFormat.KEY_FRAME_RATE, format.fps());
        // A stationary display must still produce a fresh IDR after preparation/backpressure.
        media.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        media.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        media.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        media.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(media);
        if (name == null) throw new IOException("video_encoder_unavailable");
        MediaCodec created = MediaCodec.createByCodecName(name);
        Surface input = null;
        try {
            created.configure(media, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = created.createInputSurface(); created.start();
        } catch (RuntimeException error) {
            if (input != null) input.release(); created.release(); throw error;
        }
        codec = created; surface = input;
        worker = new Thread(this::drain, "yj-avc-encoder"); worker.setDaemon(true); worker.start();
    }

    public Surface inputSurface() {
        if (closed.get()) throw new IllegalStateException("encoder_closed");
        return surface;
    }
    public void requestKeyFrame() { resendConfiguration.set(true); keyRequested.set(true); }
    private void emit(int flags, long pts, byte[] data) {
        if (!listener.offer(new ScreenCastPacket(streamId, ++sequence, Math.max(0, pts), flags, data))) {
            requestKeyFrame();
        }
    }
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!closed.get()) {
                long now = System.nanoTime();
                if (keyRequested.get() && now - lastKeyRequestNs > 500_000_000L) {
                    keyRequested.set(false); lastKeyRequestNs = now;
                    Bundle b = new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); codec.setParameters(b);
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    for (int n = 0; n < 2; n++) {
                        ByteBuffer csd = output.getByteBuffer("csd-" + n);
                        if (csd != null) {
                            csd = csd.duplicate(); byte[] part = new byte[csd.remaining()]; csd.get(part); bytes.write(part, 0, part.length);
                        }
                    }
                    if (bytes.size() > 0) { configuration = bytes.toByteArray(); emit(ScreenCastPacket.CONFIG, 0, configuration); }
                } else if (index >= 0) {
                    try {
                        if (info.size > ScreenCastPacket.MAX_PAYLOAD) throw new IllegalStateException("video_access_unit_too_large");
                        if (info.size > 0) {
                            ByteBuffer buffer = Objects.requireNonNull(codec.getOutputBuffer(index)).duplicate();
                            buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            byte[] data = new byte[info.size]; buffer.get(data);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (!Arrays.equals(configuration, data)) { configuration = data; emit(ScreenCastPacket.CONFIG, 0, data); }
                            } else {
                                if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0)
                                    throw new IllegalStateException("partial_access_unit");
                                if (configuration != null && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                        && resendConfiguration.getAndSet(false)) {
                                    emit(ScreenCastPacket.CONFIG, 0, configuration);
                                }
                                emit((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? ScreenCastPacket.KEY : 0,
                                        info.presentationTimeUs, data);
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
            }
        } catch (Exception error) {
            if (!closed.getAndSet(true)) listener.onFailure("video_encoder_failed");
        } finally {
            closed.set(true);
            try { codec.stop(); } catch (RuntimeException ignored) { }
            try { codec.release(); } finally { surface.release(); }
        }
    }
    @Override public void close() {
        closed.set(true);
        if (Thread.currentThread() != worker) {
            try { worker.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
