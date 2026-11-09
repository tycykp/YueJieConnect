package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/** Audio resources belong to one consented screen stream and are released by their IO workers. */
final class ScreenCastAudio {
    private static final String TAG = "YueJieScreenAudio";
    private static final int RATE = 48_000;
    private static final int BYTES = ScreenCastPacket.AUDIO_BYTES;
    private static final int SAMPLES = BYTES / Short.BYTES;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    interface Sender { void send(long presentationTimeUs, byte[] pcm); }

    static final class Capture implements AutoCloseable {
        private final Thread worker;
        private volatile boolean closed;

        Capture(Context host, Sender sender) {
            worker = new Thread(() -> record(host, sender), "yj-screen-audio-capture");
            worker.setDaemon(true);
            worker.start();
        }

        // The OEM cast process holds the system-only grant for the remote submix source.
        @SuppressLint({"MissingPermission", "WrongConstant"})
        private void record(Context host, Sender sender) {
            AudioRecord input = null;
            try {
                if (closed) return;
                if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        || host.checkSelfPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("screen_audio_permission_denied");
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int channels = AudioFormat.CHANNEL_IN_STEREO;
                // The native remote submix routes media away from the phone while recording.
                // Releasing this recorder restores routing without touching stream volume.
                input = new AudioRecord.Builder().setContext(host).setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        .setAudioFormat(format(channels))
                        .setBufferSizeInBytes(bufferSize(AudioRecord.getMinBufferSize(RATE, channels, ENCODING))).build();
                if (closed) return;
                input.startRecording();
                if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("screen_audio_recording_unavailable");
                ScreenCastLog.i(TAG, "capture_started");
                short[] samples = new short[SAMPLES];
                byte[] pcm = new byte[BYTES];
                ShortBuffer littleEndian = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                int filled = 0;
                while (!closed) {
                    int count = input.read(samples, filled, SAMPLES - filled, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("screen_audio_read_" + count);
                    if (count == 0) { Thread.sleep(4); continue; }
                    filled += count;
                    if (filled == SAMPLES) {
                        littleEndian.clear();
                        littleEndian.put(samples);
                        if (!closed) sender.send(Math.max(0, System.nanoTime() / 1_000), pcm);
                        filled = 0;
                    }
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                if (!closed) ScreenCastLog.e(TAG, "capture_failed", error);
            } finally {
                if (input != null) {
                    try { input.release(); }
                    finally { ScreenCastLog.i(TAG, "capture_released"); }
                }
            }
        }

        @Override public void close() {
            closed = true;
            worker.interrupt();
        }
    }

    static final class Playback implements AutoCloseable {
        private final ArrayBlockingQueue<ScreenCastPacket> queue = new ArrayBlockingQueue<>(4);
        private Thread worker;
        private volatile boolean closed;

        synchronized void start(Runnable onReady, Runnable onFailure) {
            if (closed || worker != null) return;
            worker = new Thread(() -> play(onReady, onFailure), "yj-screen-audio-playback");
            worker.setDaemon(true);
            worker.start();
        }

        synchronized ScreenCastFrameQueue.Offer offer(ScreenCastPacket packet) {
            if (closed || worker == null) return ScreenCastFrameQueue.Offer.CLOSED;
            if (!queue.offer(packet)) {
                queue.poll();
                queue.offer(packet);
            }
            return ScreenCastFrameQueue.Offer.ACCEPTED;
        }

        private void play(Runnable onReady, Runnable onFailure) {
            AudioTrack output = null;
            boolean ready = false;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int channels = AudioFormat.CHANNEL_OUT_STEREO;
                output = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(format(channels)).setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(bufferSize(AudioTrack.getMinBufferSize(RATE, channels, ENCODING)))
                        .build();
                if (closed) return;
                output.setStartThresholdInFrames(RATE / 25);
                output.play();
                ready = true;
                onReady.run();
                ScreenCastLog.i(TAG, "playback_started");
                ByteBuffer pcm = ByteBuffer.allocateDirect(BYTES);
                while (!closed) {
                    pcm.clear();
                    queue.take().putPayload(pcm);
                    pcm.flip();
                    while (!closed && pcm.hasRemaining()) {
                        int count = output.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                        if (count < 0) throw new IllegalStateException("screen_audio_write_" + count);
                        if (count == 0) Thread.sleep(4);
                    }
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                if (!closed) {
                    ScreenCastLog.e(TAG, "playback_failed", error);
                    if (ready) onFailure.run();
                }
            } finally {
                close();
                if (output != null) {
                    try {
                        output.pause();
                        output.flush();
                    } catch (IllegalStateException ignored) {
                        // The track may have failed during setup.
                    } finally {
                        output.release();
                        ScreenCastLog.i(TAG, "playback_released");
                    }
                }
            }
        }

        @Override public synchronized void close() {
            closed = true;
            queue.clear();
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        }
    }

    private static AudioFormat format(int channels) {
        return new AudioFormat.Builder().setSampleRate(RATE).setEncoding(ENCODING).setChannelMask(channels).build();
    }

    private static int bufferSize(int minimum) {
        if (minimum <= 0) throw new IllegalStateException("screen_audio_format_unavailable");
        return Math.max(minimum, 3 * BYTES);
    }

    private ScreenCastAudio() { }
}
