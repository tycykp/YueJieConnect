package io.github.qiangjian.connect;

import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.os.IInterface;
import android.view.Surface;
import com.xiaomi.camera.companion.util.SurfaceUtils;
import com.xiaomi.vtcamera.injection.VirtualCamera2;
import io.github.libxposed.api.XposedModule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Production lifecycle/result code against explicit host Android/OEM/JNI/Xposed doubles. */
public final class CameraShareNativeLifecycleChecks {
    private static int checks;
    private static final ClassLoader LOADER = CameraShareNativeLifecycleChecks.class.getClassLoader();
    private static final CameraShareStillRequest REQUEST = new CameraShareStillRequest("session", "peer", 91,
            "capture-1", "rear", 640, 480, 0);
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void await(CountDownLatch latch, String label) throws Exception {
        check(latch.await(2, TimeUnit.SECONDS), label);
    }
    private static byte[] jpeg() {
        return new byte[]{(byte)255,(byte)216, (byte)255,(byte)192,0,17,8,1,(byte)224,2,(byte)128,3,
                1,17,0,2,17,0,3,17,0, (byte)255,(byte)218,0,12,3,1,0,2,17,3,17,0,63,0,
                0,(byte)255,(byte)217};
    }
    private static final class Binder implements IBinder {
        int links;
        public boolean isBinderAlive() { return true; }
        public void linkToDeath(DeathRecipient r, int flags) { links++; }
        public boolean unlinkToDeath(DeathRecipient r, int flags) { links--; return true; }
    }
    private static final class Events implements XiaomiCameraStillConsumer.Listener {
        final AtomicInteger queued = new AtomicInteger(), consumed = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        public void queued(CameraShareStillRequest r, long surface, long timestamp) { queued.incrementAndGet(); }
        public void consumed(CameraShareStillReceipt.Evidence e) { consumed.incrementAndGet(); }
        public void closed(CameraShareStillRequest r, String reason) { closes.incrementAndGet(); closed.countDown(); }
    }
    private static VirtualCamera2 camera(Binder binder, long id) {
        VirtualCamera2 camera = new VirtualCamera2(); camera.mVirtualCameraCallback = () -> binder;
        camera.outputs.add(new VirtualCamera2.StreamConfiguration(new Surface(id)));
        return camera;
    }
    private static XiaomiCameraNativeRequests.Still binding(VirtualCamera2 camera, Binder binder) {
        Object stream = camera.outputs.get(0);
        return new XiaomiCameraNativeRequests.Still(camera, stream, camera.outputs.get(0).surface().id,
                binder, camera.mClientUid, camera.mPackageName);
    }
    private static XiaomiCameraStillConsumer consumer(VirtualCamera2 camera, Binder binder, Events events) throws Exception {
        return new XiaomiCameraStillConsumer(LOADER, REQUEST, binding(camera, binder), r -> true, events);
    }
    public static void main(String[] args) throws Throwable {
        Binder binder = new Binder(); VirtualCamera2 camera = camera(binder, 34);
        XiaomiCameraNativeRequests.Still binding = binding(camera, binder);
        check(XiaomiCameraNativeRequests.current(LOADER, binding, REQUEST), "exact native binding current");
        Object original = camera.outputs.get(0);
        camera.outputs.set(0, new VirtualCamera2.StreamConfiguration(new Surface(34)));
        check(!XiaomiCameraNativeRequests.current(LOADER, binding, REQUEST), "same surface id/new stream object is a new generation");
        camera.outputs.set(0, (VirtualCamera2.StreamConfiguration) original);
        IInterface callback = camera.mVirtualCameraCallback; camera.mVirtualCameraCallback = () -> new Binder();
        check(!XiaomiCameraNativeRequests.current(LOADER, binding, REQUEST), "callback owner replacement invalidates");
        camera.mVirtualCameraCallback = callback;

        XposedModule module = new XposedModule();
        XiaomiCameraStillLifecycleHooks lifecycle = new XiaomiCameraStillLifecycleHooks(module, LOADER); lifecycle.install();
        Events blockedEvents = new Events(); XiaomiCameraStillConsumer blocked = consumer(camera, binder, blockedEvents);
        AutoCloseable blockedLease = lifecycle.bind(binding, REQUEST, blocked);
        SurfaceUtils.entered = new CountDownLatch(1); SurfaceUtils.release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { check(!blocked.deliver(REQUEST, 700, jpeg()), "revoked blocked enqueue cannot report queued success"); }
            catch (Throwable e) { failure.set(e); }
        }); writer.start(); await(SurfaceUtils.entered, "production deliver entered blocked native writer double");
        // Original close releases the blocked native writer. Waiting on deliver's monitor here deadlocks.
        module.call(camera, "close");
        writer.join(2000); check(!writer.isAlive(), "native close proceeds without waiting on deliver monitor");
        if (failure.get() != null) throw new AssertionError(failure.get());
        await(blockedEvents.closed, "blocked producer observation resources released");
        check(blockedEvents.queued.get() == 0 && blockedEvents.consumed.get() == 0, "revoked native return is not an ack");
        check(blockedEvents.closes.get() == 1 && binder.links == 0, "one terminal callback and death link released");
        check(!XiaomiCameraNativeRequests.current(LOADER, binding, REQUEST), "original close empties configuration");
        blockedLease.close(); lifecycle.close();

        // Hold original reconfiguration before it changes output objects: reject insertion in that gap.
        binder = new Binder(); camera = camera(binder, 35); binding = binding(camera, binder);
        module = new XposedModule(); lifecycle = new XiaomiCameraStillLifecycleHooks(module, LOADER); lifecycle.install();
        Events firstEvents = new Events(); XiaomiCameraStillConsumer first = consumer(camera, binder, firstEvents);
        AutoCloseable firstLease = lifecycle.bind(binding, REQUEST, first);
        camera.configEntered = new CountDownLatch(1); camera.configRelease = new CountDownLatch(1);
        VirtualCamera2 configuring = camera; XposedModule configuringModule = module;
        Thread reconfigure = new Thread(() -> {
            try { configuringModule.call(configuring, "configureOutputStreams", new Class<?>[]{vendor.xiaomi.hardware.camera.synthetic.OutputConfiguration[].class},
                    new Object[]{new vendor.xiaomi.hardware.camera.synthetic.OutputConfiguration[0]}); }
            catch (Throwable e) { failure.set(e); }
        }); reconfigure.start(); await(camera.configEntered, "original reconfigure held before generation change");
        Events rejectedEvents = new Events(); XiaomiCameraStillConsumer rejected = consumer(camera, binder, rejectedEvents);
        boolean denied = false;
        try { lifecycle.bind(binding, REQUEST, rejected); } catch (IllegalStateException expected) { denied = true; }
        check(denied, "bind during pre-original invalidation gap rejected"); rejected.close();
        camera.configRelease.countDown(); reconfigure.join(2000);
        check(!reconfigure.isAlive(), "original reconfiguration finishes");
        if (failure.get() != null) throw new AssertionError(failure.get());
        await(firstEvents.closed, "reconfiguration revokes original consumer");
        firstLease.close(); lifecycle.close();

        // A late cleanup callback from an old registration must not remove its replacement slot.
        binder = new Binder(); camera = camera(binder, 37); binding = binding(camera, binder);
        module = new XposedModule(); lifecycle = new XiaomiCameraStillLifecycleHooks(module, LOADER); lifecycle.install();
        Events oldEvents = new Events(); XiaomiCameraStillConsumer old = consumer(camera, binder, oldEvents);
        AutoCloseable oldLease = lifecycle.bind(binding, REQUEST, old);
        old.close(); await(oldEvents.closed, "old observation disposed before replacement");
        Events replacementEvents = new Events(); XiaomiCameraStillConsumer replacement = consumer(camera, binder, replacementEvents);
        AutoCloseable replacementLease = lifecycle.bind(binding, REQUEST, replacement);
        oldLease.close(); module.call(camera, "close");
        check(replacement.isClosed(), "late old lease cleanup preserves replacement for original close revocation");
        await(replacementEvents.closed, "replacement observation resources released");
        replacementLease.close(); lifecycle.close();

        // Original acquireLatest internally acquires/discards a matching older image. Observe only its final result.
        binder = new Binder(); camera = camera(binder, 36); Events events = new Events();
        XiaomiCameraStillConsumer consumer = consumer(camera, binder, events);
        module = new XposedModule(); ImageReader.module = module;
        XiaomiCameraStillResultHooks results = new XiaomiCameraStillResultHooks(module); results.install();
        ImageReader reader = new ImageReader(camera.outputs.get(0).surface());
        AutoCloseable readerLease = results.bind(reader, consumer);
        SurfaceUtils.release = null; SurfaceUtils.entered = null;
        check(consumer.deliver(REQUEST, 700, jpeg()), "production writer returns queued only");
        check(events.queued.get() == 1 && events.consumed.get() == 0 && events.closes.get() == 0, "enqueue leaves native result pending");
        long stamp = System.nanoTime(); byte[] other = jpeg(); other[other.length - 3] ^= 1;
        Image discarded = new Image(jpeg(), stamp), latest = new Image(other, stamp);
        reader.images.add(discarded); reader.images.add(latest);
        Object returned = module.call(reader, "acquireLatestImage");
        check(returned == latest && discarded.closed && !latest.closed, "original latest-image behavior and ownership unchanged");
        check(events.consumed.get() == 0 && events.closes.get() == 0, "nested discarded matching image cannot complete");
        Image actual = new Image(jpeg(), System.nanoTime()); reader.images.add(actual);
        returned = module.call(reader, "acquireNextImage");
        check(returned == actual && !actual.closed && actual.buffer.position() == 0, "original acquired Image/plane unchanged");
        check(events.consumed.get() == 1 && events.closes.get() == 1 && binder.links == 0, "only actual returned matching native image completes once");
        readerLease.close(); results.close();
        check(!reader.closed && reader.getSurface().isValid(), "observer disposal never closes OEM reader/surface");
        reader.close();
        System.out.println("camera-native-lifecycle-host-checks: PASS " + checks + " assertions");
    }
}
