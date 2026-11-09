package io.github.yuejie.connect;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.Telephony;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import static io.github.yuejie.connect.TelephonySyncController.Session;

/** Exact captured Mms database/action adapter. No content/provider legacy calls or SMS send/retry. */
final class OplusTelephonySmsNativeAdapter implements AutoCloseable {
    private record Row(TelephonySyncSmsNative.Target target, int status) {}
    private final Context context;
    private final Method model, database, query, mutation, start;
    private final Constructor<?> action;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yj-oem-sms-status"); t.setDaemon(true); return t;
    });
    private final List<HookHandle> hooks = new ArrayList<>();
    private record Observation(Binding binding, String row) {}
    private final Set<Observation> queuedObservations = ConcurrentHashMap.newKeySet();
    private final Semaphore observationCapacity = new Semaphore(64);
    private volatile Binding active;
    private volatile boolean closed;

    OplusTelephonySmsNativeAdapter(Context context, ClassLoader loader) throws ReflectiveOperationException {
        this.context = Objects.requireNonNull(context);
        if (!"com.android.mms".equals(context.getPackageName())) throw new IllegalArgumentException("wrong_sms_host");
        Class<?> modelType = Class.forName("com.android.mms.datamodel.DataModel", false, loader);
        Class<?> db = Class.forName("com.android.mms.datamodel.p", false, loader);
        Class<?> utility = Class.forName("com.android.mms.datamodel.e", false, loader);
        Class<?> actionType = Class.forName("com.oplus.mms.communicationshare.operation.UpdateMessageStatusAction", false, loader);
        model = modelType.getDeclaredMethod("r"); database = modelType.getDeclaredMethod("v");
        query = db.getDeclaredMethod("n", String.class, String[].class, String.class, String[].class,
                String.class, String.class, String.class);
        mutation = utility.getDeclaredMethod("y1", db, String.class, ContentValues.class);
        if (mutation.getReturnType() != boolean.class || query.getReturnType() != Cursor.class)
            throw new IllegalStateException("sms_native_signature_mismatch");
        action = actionType.getDeclaredConstructor(String.class, int.class, int.class);
        start = actionType.getMethod("start");
    }
    synchronized void install(XposedModule module) {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("sms_adapter_lifecycle");
        hooks.add(module.hook(mutation).setId("yj.telephony.oplus.sms.native.mutation").intercept(chain -> {
            Object result = chain.proceed(); // OEM database behavior always runs unchanged.
            try {
                Binding b = active;
                if (Boolean.TRUE.equals(result) && b != null && b.allowedObservation()) {
                    Object row = chain.getArg(1);
                    if (row instanceof String id && id.matches("[0-9]{1,20}")) enqueueObservation(b, id);
                }
            } catch (RuntimeException ignored) { /* Keep original OEM return intact. */ }
            return result;
        }));
    }
    private void enqueueObservation(Binding binding, String row) {
        Observation key = new Observation(binding, row);
        if (!observationCapacity.tryAcquire()) return;
        if (!queuedObservations.add(key)) { observationCapacity.release(); return; }
        if (!enqueue(() -> {
            try { observeRow(binding, row); }
            finally { queuedObservations.remove(key); observationCapacity.release(); }
        })) { queuedObservations.remove(key); observationCapacity.release(); }
    }
    synchronized Binding bind(Session session, TelephonySyncSmsNative.Policy policy,
                              Consumer<TelephonySyncSmsNative.Snapshot> events) {
        if (closed || hooks.isEmpty()) throw new IllegalStateException("sms_adapter_not_installed");
        if (active != null) active.close();
        Binding b = new Binding(session, policy, events); active = b; return b;
    }
    final class Binding implements AutoCloseable {
        private final Session session;
        private final TelephonySyncSmsNative.Policy policy;
        private final Consumer<TelephonySyncSmsNative.Snapshot> events;
        private final TelephonySyncSmsNative endpoint;
        private volatile boolean ended;
        private Binding(Session session, TelephonySyncSmsNative.Policy policy,
                        Consumer<TelephonySyncSmsNative.Snapshot> events) {
            this.session = Objects.requireNonNull(session); this.policy = Objects.requireNonNull(policy);
            this.events = Objects.requireNonNull(events);
            endpoint = new TelephonySyncSmsNative(session, policy,
                    (target, previous, status, completion) -> enqueue(() -> applyNative(this, target, previous, status, completion)),
                    SystemClock::elapsedRealtime);
        }
        CompletableFuture<TelephonySyncSmsNative.Result> apply(TelephonySyncSmsNative.Command command, long deadlineElapsedMs) {
            return endpoint.apply(command, Math.max(0, deadlineElapsedMs - SystemClock.elapsedRealtime()));
        }
        boolean cancel(Session source, String request) { return endpoint.cancel(source, request); }
        void tick() { endpoint.tick(); }
        private boolean allowedObservation() {
            return !closed && !ended && active == this && policy.observes(session) && platformAllowed();
        }
        private boolean allowedUpdate() { return allowedObservation() && policy.updates(session); }
        @Override public void close() { ended = true; endpoint.close(); if (active == this) active = null; }
    }
    private void observeRow(Binding b, String row) {
        if (!b.allowedObservation()) return;
        try {
            Row value = read("_id = ? AND message_protocol = 0", row);
            if (value == null || !b.allowedObservation()) return;
            TelephonySyncSmsNative.Snapshot snapshot = b.endpoint.observe(value.target(), value.status());
            if (snapshot != null && b.allowedObservation()) b.events.accept(snapshot);
        } catch (Exception ignored) { /* No message data or identifiers in logs. Next native update may retry observation. */ }
    }
    private void applyNative(Binding b, TelephonySyncSmsNative.Target target, int previous, int status,
                             TelephonySyncSmsNative.Completion completion) {
        if (!completion.active()) return;
        if (!b.allowedUpdate()) { completion.complete("permission_denied", null, -1); return; }
        try {
            Row row = read("_id = ? AND message_protocol = 0", target.row());
            if (row == null || !target.equals(row.target())) { completion.complete("sms_missing", null, -1); return; }
            if (row.status() != previous) { completion.complete("stale_message", null, -1); return; }
            if (!completion.active()) return;
            if (!b.allowedUpdate()) { completion.complete("permission_denied", null, -1); return; }
            Object nativeAction = action.newInstance(target.identifier(), status, 1);
            if (!completion.active()) return;
            if (!b.allowedUpdate()) { completion.complete("permission_denied", null, -1); return; }
            start.invoke(nativeAction); // void enqueue is not completion; type3/SIM fallback is never invoked.
            verifyLater(b, target, status, SystemClock.elapsedRealtime() + 5000, completion);
        } catch (Exception failure) { completion.complete("outcome_unknown", null, -1); }
    }
    private void verifyLater(Binding b, TelephonySyncSmsNative.Target target, int expected, long deadline,
                             TelephonySyncSmsNative.Completion completion) {
        if (!completion.active()) return;
        if (!b.allowedUpdate() || SystemClock.elapsedRealtime() >= deadline) {
            completion.complete("outcome_unknown", null, -1); return;
        }
        try {
            // Exact, bound SQL parameters; never IdConvertHelper's interpolated identifier IN (...).
            Row value = read("_id = ? AND message_protocol = 0", target.row());
            if (value == null || !value.target().equals(target)) { completion.complete("sms_missing", null, -1); return; }
            if (value.status() == expected) { completion.complete("native_applied", value.target(), value.status()); return; }
            worker.schedule(() -> verifyLater(b, target, expected, deadline, completion), 100, TimeUnit.MILLISECONDS);
        } catch (Exception failure) { completion.complete("outcome_unknown", null, -1); }
    }
    private Row read(String selection, String parameter) throws ReflectiveOperationException {
        Object db = database.invoke(model.invoke(null));
        try (Cursor cursor = (Cursor) query.invoke(db, "messages",
                new String[]{"_id", "identifier", "message_status"}, selection, new String[]{parameter}, null, null, null)) {
            if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst() || cursor.isNull(1)) return null;
            return new Row(new TelephonySyncSmsNative.Target(cursor.getString(1), cursor.getString(0)), cursor.getInt(2));
        }
    }
    private boolean platformAllowed() {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context));
    }
    private boolean enqueue(Runnable action) {
        if (!closed) { try { worker.execute(action); return true; } catch (java.util.concurrent.RejectedExecutionException ignored) {} }
        return false;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (active != null) active.close();
        for (HookHandle h : hooks) h.unhook(); hooks.clear(); worker.shutdownNow(); queuedObservations.clear();
    }
}
