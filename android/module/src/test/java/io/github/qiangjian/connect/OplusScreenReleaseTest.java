package io.github.qiangjian.connect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise actual adapter release/timeout paths with controlled native worker/managers.
 * No hook is installed and no OEM display is created; this is a host lifecycle regression. */
public class OplusScreenReleaseTest {
    public static final class Manager {
        public boolean q;
        public String g = "synthetic-bus";
        boolean fail, incomplete;
        int attempts;
        public void release() { attempts++; if (fail) throw new IllegalStateException("synthetic"); if (!incomplete) q = true; }
    }
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>(); boolean reject;
        public void execute(Runnable action) { if (reject) throw new IllegalStateException("synthetic"); jobs.add(action); }
        void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
    }
    private static final class Events implements OplusScreenSourceAdapter.Listener {
        final List<String> failures = new ArrayList<>(); int stops;
        public boolean offer(ScreenCastPacket packet) { return false; }
        public void onFailure(String code) { failures.add(code); }
        public void onPrepared(int id) { fail("unexpected prepare"); }
        public void onStopped() { stops++; }
    }
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void set(Object instance,String name,Object value) throws Exception {
        Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.set(instance,value);
    }
    private static Field field(String name) throws Exception { Field f = Manager.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private final OplusScreenSourceAdapter.Scope scope = new OplusScreenSourceAdapter.Scope("c".repeat(64),7);
    private final Map<Object,Object> bindings = new IdentityHashMap<>();
    private OplusScreenSourceAdapter source() throws Exception {
        var source = (OplusScreenSourceAdapter) allocate(OplusScreenSourceAdapter.class);
        set(source,"bindings",bindings); set(source,"released",field("q")); set(source,"busId",field("g"));
        set(source,"release",Manager.class.getMethod("release")); return source;
    }
    private void bind(Manager manager, Worker worker, Events events, OplusScreenSourceAdapter.Scope requested) throws Exception {
        Class<?> type = Class.forName(OplusScreenSourceAdapter.class.getName()+"$Binding");
        Constructor<?> ctor = type.getDeclaredConstructor(Object.class,String.class,OplusScreenSourceAdapter.Scope.class,
                ScreenCastFormat.class,BooleanSupplier.class,Executor.class,ScreenCastVideoEncoder.Listener.class); ctor.setAccessible(true);
        bindings.put(manager,ctor.newInstance(manager,manager.g,requested,new ScreenCastFormat(640,480,30,1_000_000),
                (BooleanSupplier) () -> true,worker,events));
    }
    @Test public void rejectedReleaseDispatchRetainsOwnerAndTimerRetries() throws Exception {
        var source = source(); Manager manager = new Manager(); Worker worker = new Worker(); Events events = new Events();
        bind(manager,worker,events,scope); worker.reject = true; source.stop(scope);
        assertEquals(0,events.stops); assertEquals(1,bindings.size()); assertEquals(0,manager.attempts);
        assertEquals(List.of("native_source_release_dispatch_failed"),events.failures);
        worker.reject = false; source.tick(); source.tick(); assertEquals(1,worker.jobs.size()); worker.drain();
        assertTrue(manager.q); assertEquals(1,events.stops); assertTrue(bindings.isEmpty()); source.tick(); assertEquals(1,manager.attempts);
    }
    @Test public void failedNativeReleaseDoesNotClaimStoppedAndCanRetry() throws Exception {
        var source = source(); Manager manager = new Manager(); Worker worker = new Worker(); Events events = new Events();
        bind(manager,worker,events,scope); manager.fail = true; source.stop(scope); worker.drain();
        assertFalse(manager.q); assertEquals(0,events.stops); assertEquals(1,bindings.size());
        manager.fail = false; source.tick(); worker.drain(); assertTrue(manager.q); assertEquals(1,events.stops);
        assertEquals(List.of("native_source_release_failed"),events.failures); assertTrue(bindings.isEmpty());
    }
    @Test public void normalReturnWithoutNativeReleasedFlagRemainsUnconfirmed() throws Exception {
        var source = source(); Manager manager = new Manager(); Worker worker = new Worker(); Events events = new Events();
        bind(manager,worker,events,scope); manager.incomplete = true; source.stop(scope); worker.drain();
        assertEquals(0,events.stops); assertEquals(1,bindings.size());
        assertEquals(List.of("native_source_release_unconfirmed"),events.failures);
        manager.incomplete = false; source.tick(); worker.drain(); assertEquals(1,events.stops);
    }
    @Test public void stoppingOneStreamDoesNotReleaseAnother() throws Exception {
        var source = source(); Worker worker = new Worker(); Manager a = new Manager(), b = new Manager(); Events ea = new Events(), eb = new Events();
        bind(a,worker,ea,scope); bind(b,worker,eb,new OplusScreenSourceAdapter.Scope(scope.sessionId(),8));
        source.stop(scope); worker.drain(); assertTrue(a.q); assertFalse(b.q); assertEquals(1,bindings.size());
        assertEquals(1,ea.stops); assertEquals(0,eb.stops);
    }
    @Test public void expiredSilentPermissionRequestNotifiesFailureExactlyOnce() throws Exception {
        var source = source(); var requests = (OplusScreenRequestAdapter) allocate(OplusScreenRequestAdapter.class);
        Object device = new Object(); Events events = new Events(); AtomicLong clock = new AtomicLong(100);
        var consent = new ScreenCastNativeConsent(1000,() -> true,clock::get); assertTrue(consent.attach(device));
        Object request = allocate(Class.forName(OplusScreenRequestAdapter.class.getName()+"$Request"));
        set(request,"device",device); set(request,"scope",scope); set(request,"consent",consent); set(request,"listener",events);
        Map<Object,Object> pending = new IdentityHashMap<>(); pending.put(device,request);
        set(requests,"pending",pending); set(requests,"source",source); set(requests,"retired",new ArrayList<>());
        clock.set(1000); requests.tick(); requests.tick(); assertTrue(pending.isEmpty());
        assertEquals(List.of("native_screen_request_expired"),events.failures); assertEquals(0,events.stops);
    }
    private static final class EqualDevice {
        @Override public boolean equals(Object other) { return other instanceof EqualDevice; }
        @Override public int hashCode() { return 1; }
    }
    private Object pendingRequest(Object device,Events events,AtomicLong clock) throws Exception {
        var consent=new ScreenCastNativeConsent(1000,() -> true,clock::get); assertTrue(consent.attach(device));
        Object request=allocate(Class.forName(OplusScreenRequestAdapter.class.getName()+"$Request"));
        set(request,"device",device); set(request,"scope",scope); set(request,"consent",consent); set(request,"listener",events);
        return request;
    }
    private boolean retired(OplusScreenRequestAdapter adapter,Object device) throws Exception {
        Method method=OplusScreenRequestAdapter.class.getDeclaredMethod("retired",Object.class); method.setAccessible(true);
        return (Boolean)method.invoke(adapter,device);
    }
    @Test public void cancelledNativeIdentityCannotFallBackButEqualUnrelatedDeviceIsUntouched() throws Exception {
        var adapter=(OplusScreenRequestAdapter)allocate(OplusScreenRequestAdapter.class); Events events=new Events();
        Object exact=new EqualDevice(); Map<Object,Object> pending=new IdentityHashMap<>();
        pending.put(exact,pendingRequest(exact,events,new AtomicLong(100)));
        set(adapter,"pending",pending); set(adapter,"source",source()); set(adapter,"retired",new ArrayList<>());
        adapter.cancel(scope); assertTrue(pending.isEmpty()); assertTrue(retired(adapter,exact));
        assertFalse(retired(adapter,new EqualDevice())); adapter.sessionChanged(); assertTrue(retired(adapter,exact));
    }
    @Test public void expiredLookupRetiresBeforeDelayedNativeEnqueueCanProceed() throws Exception {
        var adapter=(OplusScreenRequestAdapter)allocate(OplusScreenRequestAdapter.class); Events events=new Events();
        Object device=new Object(); AtomicLong clock=new AtomicLong(100); Map<Object,Object> pending=new IdentityHashMap<>();
        pending.put(device,pendingRequest(device,events,clock)); set(adapter,"pending",pending); set(adapter,"source",source());
        set(adapter,"retired",new ArrayList<>()); clock.set(1000);
        Method lookup=OplusScreenRequestAdapter.class.getDeclaredMethod("pending",Object.class); lookup.setAccessible(true);
        assertNull(lookup.invoke(adapter,device)); assertTrue(retired(adapter,device)); assertTrue(pending.isEmpty());
        assertNull(lookup.invoke(adapter,device)); assertEquals(List.of("native_screen_request_expired"),events.failures);
    }
}
