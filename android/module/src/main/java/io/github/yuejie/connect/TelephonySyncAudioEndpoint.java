package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static io.github.yuejie.connect.TelephonySyncAudioSession.*;
import static io.github.yuejie.connect.TelephonySyncController.Session;

/** Actual Android voice source/sink, never a Bluetooth alias, video-purpose pipe, or call recorder. */
final class TelephonySyncAudioEndpoint implements AutoCloseable {
    interface Transport {
        /** Nonblocking bounded admission on the authenticated session/stream AUDIO channel; takes ownership. */
        boolean offer(byte[] packet);
        /** Withdraw this stream only; no shared/native connection teardown. */
        void close();
    }
    record Status(String code, long capturedFrames, long playedFrames) {}
    private final Context context;
    private final Role role;
    private final TelephonySyncAudioSession stream;
    private final Transport transport;
    private final Consumer<Status> events;
    private final ArrayBlockingQueue<byte[]> playback = new ArrayBlockingQueue<>(5); // At most 100 ms PCM.
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "yj-call-audio"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean flowing = new AtomicBoolean();
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private volatile AudioDeviceInfo telephone;

    /** Construction is inert. Only start() following real native per-call approval opens audio. */
    TelephonySyncAudioEndpoint(Context context, Session session, String call, String streamId, Role role,
                               Policy policy, Transport transport, Consumer<Status> events) {
        this.context = Objects.requireNonNull(context); this.role = Objects.requireNonNull(role);
        this.transport = Objects.requireNonNull(transport); this.events = Objects.requireNonNull(events);
        String host = context.getPackageName();
        if (role == Role.PAD ? !"com.miui.audiomonitor".equals(host) && !"com.android.phone".equals(host)
                : !"com.android.phone".equals(host) && !"com.android.incallui".equals(host))
            throw new IllegalArgumentException("invalid_native_audio_host");
        // Host strings restrict installation; public owner must separately prove this exact UID/PID.
        stream = new TelephonySyncAudioSession(session, call, streamId, role, policy);
    }
    synchronized void start() {
        if (ended.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("audio_lifecycle");
        workers.scheduleWithFixedDelay(() -> { if (!allowed()) stop("permission_or_call_withdrawn"); }, 0, 100, TimeUnit.MILLISECONDS);
        workers.execute(this::capture);
    }
    /** Only the already authenticated AUDIO channel for this exact session/stream may call this. */
    synchronized void receive(byte[] packet) {
        if (!started.get() || ended.get()) return;
        byte[] pcm = null;
        try {
            if (!allowed()) { stop("permission_or_call_withdrawn"); return; }
            pcm = stream.receive(packet);
            if (ended.get() || !playback.offer(pcm)) { Arrays.fill(pcm, (byte) 0); stop("audio_backpressure"); }
        } catch (RuntimeException invalid) {
            if (pcm != null) Arrays.fill(pcm, (byte) 0);
            stop("invalid_audio_packet");
        }
    }
    private boolean allowed() {
        try {
            return !ended.get() && stream.active()
                    && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    && (role == Role.PAD || context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED);
        } catch (RuntimeException failure) { return false; }
    }
    private void capture() {
        AudioRecord input = null;
        byte[] frame = new byte[FRAME_BYTES];
        try {
            if (!allowed()) { stop("permission_or_call_withdrawn"); return; }
            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) { stop("native_audio_unavailable"); return; }
            if (role == Role.PHONE) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (device.getType() != AudioDeviceInfo.TYPE_TELEPHONY || !device.isSink()) continue;
                    if (telephone != null) { stop("ambiguous_telephony_route"); return; }
                    telephone = device;
                }
                if (telephone == null) { stop("native_telephony_route_missing"); return; }
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) { stop("native_audio_format_rejected"); return; }
            input = createNativeRecorder(Math.max(minimum, FRAME_BYTES * 2));
            recorder = input;
            if (input.getState() != AudioRecord.STATE_INITIALIZED || input.getSampleRate() != SAMPLE_RATE
                    || input.getChannelCount() != 1 || input.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT
                    || input.getAudioSource() != (role == Role.PHONE ? MediaRecorder.AudioSource.VOICE_DOWNLINK
                    : MediaRecorder.AudioSource.VOICE_COMMUNICATION))
                throw new IllegalStateException();
            if (!allowed()) { stop("permission_or_call_withdrawn"); return; }
            workers.execute(this::play);
            input.startRecording();
            if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
            int filled = 0;
            long noDataDeadline = SystemClock.elapsedRealtime() + 2000;
            while (allowed()) {
                int n = input.read(frame, filled, FRAME_BYTES - filled, AudioRecord.READ_NON_BLOCKING);
                if (n < 0 || (n & 1) != 0) { stop("native_capture_failed"); break; }
                if (n == 0) {
                    if (SystemClock.elapsedRealtime() >= noDataDeadline) { stop("native_capture_stalled"); break; }
                    TimeUnit.MILLISECONDS.sleep(2); continue;
                }
                AudioRecordingConfiguration configuration = input.getActiveRecordingConfiguration();
                if (configuration == null || configuration.isClientSilenced()) { stop("native_capture_silenced"); break; }
                noDataDeadline = SystemClock.elapsedRealtime() + 2000; filled += n;
                if (filled != FRAME_BYTES) continue;
                if (!allowed()) break;
                byte[] packet = stream.capture(frame);
                if (!transport.offer(packet)) { Arrays.fill(packet, (byte) 0); stop("audio_backpressure"); break; }
                Arrays.fill(frame, (byte) 0); filled = 0; emitFlowing();
            }
        } catch (SecurityException denied) { stop("permission_denied"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException failure) { stop("native_capture_failed"); }
        finally {
            Arrays.fill(frame, (byte) 0);
            if (input != null) { try { input.stop(); } catch (RuntimeException ignored) {} input.release(); }
            recorder = null;
            if (!ended.get()) stop("permission_or_call_withdrawn");
        }
    }
    // Injected host grants differ from this module APK's manifest. Check the real host at allocation,
    // retain the SecurityException revocation path, and never add microphone permission to the module.
    @SuppressLint("MissingPermission")
    private AudioRecord createNativeRecorder(int bytes) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !allowed())
            throw new SecurityException();
        return new AudioRecord.Builder().setAudioSource(role == Role.PHONE
                ? MediaRecorder.AudioSource.VOICE_DOWNLINK : MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(bytes).build();
    }
    private void play() {
        AudioTrack output = null;
        byte[] pcm = null;
        try {
            if (!allowed()) return;
            int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) { stop("native_audio_format_rejected"); return; }
            output = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(2304).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, FRAME_BYTES * 2)).build();
            player = output;
            if (output.getState() != AudioTrack.STATE_INITIALIZED || output.getSampleRate() != SAMPLE_RATE
                    || output.getChannelCount() != 1 || output.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT)
                throw new IllegalStateException();
            if (role == Role.PHONE && !output.setPreferredDevice(telephone)) { stop("native_route_rejected"); return; }
            if (!allowed()) return;
            output.play();
            long routeDeadline = SystemClock.elapsedRealtime() + 500;
            // A preferred-device boolean is not a route. Never write phone uplink to a fallback speaker.
            while (allowed() && !routed(output)) {
                if (SystemClock.elapsedRealtime() >= routeDeadline) { stop("native_route_unconfirmed"); return; }
                TimeUnit.MILLISECONDS.sleep(5);
            }
            long dataDeadline = SystemClock.elapsedRealtime() + 2000;
            while (allowed()) {
                pcm = playback.poll(20, TimeUnit.MILLISECONDS);
                if (pcm == null) {
                    if (SystemClock.elapsedRealtime() >= dataDeadline) { stop("remote_audio_stalled"); return; }
                    continue;
                }
                int offset = 0;
                long writeDeadline = SystemClock.elapsedRealtime() + 100;
                while (offset < pcm.length && allowed()) {
                    if (!routed(output)) { stop("native_route_changed"); return; }
                    int n = output.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (n < 0 || (n & 1) != 0) { stop("native_playback_failed"); return; }
                    if (n == 0) {
                        if (SystemClock.elapsedRealtime() >= writeDeadline) { stop("native_playback_stalled"); return; }
                        TimeUnit.MILLISECONDS.sleep(2); continue;
                    }
                    offset += n;
                }
                if (!allowed()) return;
                long renderDeadline = SystemClock.elapsedRealtime() + 200;
                long requiredSamples = (stream.playedFrames() + 1) * (FRAME_BYTES / 2);
                while (allowed() && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < requiredSamples) {
                    if (!routed(output)) { stop("native_route_changed"); return; }
                    if (SystemClock.elapsedRealtime() >= renderDeadline) { stop("native_render_unconfirmed"); return; }
                    TimeUnit.MILLISECONDS.sleep(2);
                }
                if (!allowed()) return;
                stream.played(); Arrays.fill(pcm, (byte) 0); pcm = null;
                dataDeadline = SystemClock.elapsedRealtime() + 2000; emitFlowing();
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (SecurityException denied) { stop("permission_denied"); }
        catch (RuntimeException failure) { stop("native_playback_failed"); }
        finally {
            if (pcm != null) Arrays.fill(pcm, (byte) 0);
            if (output != null) { try { output.stop(); } catch (RuntimeException ignored) {} output.release(); }
            player = null;
            if (!ended.get()) stop("permission_or_call_withdrawn");
        }
    }
    private boolean routed(AudioTrack output) {
        AudioDeviceInfo route = output.getRoutedDevice();
        if (route == null || !route.isSink()) return false;
        return role == Role.PAD ? route.getType() != AudioDeviceInfo.TYPE_TELEPHONY
                : telephone != null && route.getId() == telephone.getId() && route.getType() == AudioDeviceInfo.TYPE_TELEPHONY;
    }
    private void emitFlowing() {
        if (allowed() && stream.capturedFrames() > 0 && stream.playedFrames() > 0 && flowing.compareAndSet(false, true))
            emit("native_frames_exchanged"); // Actual capture and native writes; not a claim of audible speech.
    }
    private void emit(String code) {
        try { events.accept(new Status(code, stream.capturedFrames(), stream.playedFrames())); } catch (RuntimeException ignored) {}
    }
    private synchronized void stop(String code) {
        if (!ended.compareAndSet(false, true)) return;
        stream.close();
        AudioRecord input = recorder; if (input != null) try { input.stop(); } catch (RuntimeException ignored) {}
        AudioTrack output = player; if (output != null) try { output.pause(); } catch (RuntimeException ignored) {}
        byte[] value; while ((value = playback.poll()) != null) Arrays.fill(value, (byte) 0);
        try { transport.close(); } catch (RuntimeException ignored) {}
        workers.shutdownNow(); emit(code);
    }
    @Override public void close() { stop("closed"); }
}
