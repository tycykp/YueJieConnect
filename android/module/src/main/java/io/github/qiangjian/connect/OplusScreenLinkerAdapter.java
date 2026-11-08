package io.github.qiangjian.connect;

import android.os.Bundle;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observes Linker's original selected CastDevice and the exact DeviceInfo copied into its SDK
 * call. It leaves the OEM ScreenRecordAuthorizeCallback untouched. Binder will parcel a different
 * object into CastService: the public layer must correlate it with verified caller/session proof. */
public final class OplusScreenLinkerAdapter implements AutoCloseable {
    public static final String REQUEST_PROOF_EXTRA = "io.github.qiangjian.connect.screen.request_proof.v1";
    /** Opaque one-shot handle from BridgeService. This class conveys it; it grants no authority. */
    public static final class ProofCarrier {
        private String proof;
        private boolean applied;
        public synchronized void attach(String requestProof) {
            if (applied || proof != null || !validProof(requestProof))
                throw new IllegalStateException("native_screen_request_proof_invalid");
            proof = requestProof;
        }
        private synchronized void apply(Bundle actualOutgoing) {
            if (proof == null) return; // Compatible observer-only use; Cast resolver must require its own proof.
            if (applied || actualOutgoing == null) throw new IllegalStateException("native_screen_request_proof_unavailable");
            if (actualOutgoing.containsKey(REQUEST_PROOF_EXTRA))
                throw new IllegalStateException("native_screen_request_proof_conflict");
            actualOutgoing.putString(REQUEST_PROOF_EXTRA, proof); applied = true;
        }
        private synchronized void clear() { if (!applied) proof = null; applied = true; }
        private synchronized void finish(Bundle actualOutgoing) {
            try {
                if (proof != null && actualOutgoing != null && proof.equals(readRequestProof(actualOutgoing)))
                    actualOutgoing.remove(REQUEST_PROOF_EXTRA);
            } finally { proof = null; applied = true; }
        }
        @Override public String toString() { return "NativeScreenProofCarrier"; }
    }
    private static boolean validProof(String proof) { return proof != null && proof.matches("[0-9a-f]{64}"); }
    /** Read ONLY the dedicated primitive string. Malformed/unparcelable ordinary extras are never proof. */
    public static String readRequestProof(Bundle nativeRequestBundle) {
        try {
            String proof = nativeRequestBundle == null ? null : nativeRequestBundle.getString(REQUEST_PROOF_EXTRA);
            return validProof(proof) ? proof : null;
        } catch (RuntimeException error) { return null; }
    }
    public record Selection(Object castDevice, String nativeUid, String deviceType, ProofCarrier proofCarrier) {
        public Selection(Object castDevice, String nativeUid, String deviceType) {
            this(castDevice, nativeUid, deviceType, new ProofCarrier());
        }
        public Selection { Objects.requireNonNull(proofCarrier); }
        @Override public String toString() { return "NativeCastSelection"; }
    }
    public interface Delegate {
        /** null means an unrelated native device. Called on the original native selected request. */
        Lease selected(Selection selected);
    }
    public interface Lease extends AutoCloseable {
        long deadlineElapsedMs();
        boolean current();
        /** Register the request proof before returning; this is not screen consent. */
        void converted(Selection selected, Object actualSdkDeviceInfo);
        /** Actual SDK return, never permission/prepared/first-frame success. */
        void dispatched(int status);
        void failed(String reason);
        @Override void close();
    }
    private static final class Entry {
        final Selection selected;
        final Lease lease;
        boolean started, converted;
        volatile boolean revoked;
        Bundle outgoing;
        Entry(Selection selected, Lease lease) { this.selected = selected; this.lease = lease; }
    }
    private final XposedModule module;
    private final ClassLoader loader;
    private final Delegate delegate;
    private final Map<Object, Entry> pending = new IdentityHashMap<>();
    private final List<HookHandle> hooks = new ArrayList<>();
    private final ThreadLocal<Entry> converting = new ThreadLocal<>();
    private volatile boolean closed;

    public OplusScreenLinkerAdapter(XposedModule module, ClassLoader loader, Delegate delegate) {
        this.module = Objects.requireNonNull(module); this.loader = Objects.requireNonNull(loader);
        this.delegate = Objects.requireNonNull(delegate);
    }
    public void install() throws ReflectiveOperationException {
        if (closed || !hooks.isEmpty()) throw new IllegalStateException("native_screen_linker_not_installable");
        Class<?> wrapper = type("com.oplus.linker.synergy.thirdapi.heycast.HeycastSdkWrapper");
        Class<?> castDevice = type("com.oplus.cast.service.sdk.config.CastDevice");
        Class<?> apiCallback = type("com.oplus.linker.synergy.thirdapi.heycast.IHeyCastApi$HeyCastApiCallBack");
        Class<?> manager = type("com.oplus.cast.service.sdk.api.HeyCastClientManager");
        Class<?> recordCallback = type("com.oplus.cast.service.sdk.api.ScreenRecordAuthorizeCallback");
        Class<?> device = type("com.oplus.cast.service.sdk.DeviceInfo");
        Method uid = castDevice.getMethod("getUid"), kind = castDevice.getMethod("getDeviceType");
        Method copiedUid = device.getMethod("getUid"), copiedKind = device.getMethod("getDeviceType");
        try {
            hooks.add(module.hook(wrapper.getDeclaredMethod("startMirror", castDevice, Bundle.class, apiCallback))
                    .setId("qj.screen.oplus.linker.selection").intercept(chain -> {
                        Object selected = chain.getArg(0);
                        Selection snapshot = new Selection(selected, (String) uid.invoke(selected), (String) kind.invoke(selected));
                        Lease lease = delegate.selected(snapshot);
                        if (lease != null) {
                            Entry entry = new Entry(snapshot, lease);
                            long remaining = lease.deadlineElapsedMs() - SystemClock.elapsedRealtime();
                            synchronized (this) {
                                prune();
                                if (closed || !"SYNERGY_PAD".equals(snapshot.deviceType) || !valid(snapshot.nativeUid)
                                        || !lease.current() || remaining <= 0 || remaining > 120_000 || pending.size() >= 4) {
                                    closeEntry(entry); throw new IllegalStateException("native_screen_selection_unavailable");
                                }
                                Entry old = pending.put(selected, entry); if (old != null) closeEntry(old);
                            }
                        }
                        try { return chain.proceed(); }
                        catch (Throwable error) { discard(selected, "native_screen_selection_failed"); throw error; }
                    }));
            hooks.add(module.hook(manager.getDeclaredMethod("startMirror", castDevice, recordCallback, Bundle.class))
                    .setId("qj.screen.oplus.linker.sdk").intercept(chain -> {
                        Entry entry;
                        synchronized (this) {
                            prune(); entry = pending.get(chain.getArg(0));
                            if (entry != null) {
                                if (entry.started) return -4;
                                entry.started = true;
                                entry.outgoing = (Bundle) chain.getArg(2);
                            }
                        }
                        if (entry == null) return chain.proceed();
                        Entry previous = converting.get(); converting.set(entry);
                        try {
                            if (!current(entry) || !entry.selected.nativeUid.equals(uid.invoke(chain.getArg(0)))
                                    || !entry.selected.deviceType.equals(kind.invoke(chain.getArg(0)))) {
                                entry.lease.failed("native_screen_selection_changed"); return -4;
                            }
                            Object result = chain.proceed();
                            entry.lease.dispatched((Integer) result);
                            if (!(result instanceof Integer code) || code != 0)
                                discard(entry.selected.castDevice, "native_screen_sdk_failed");
                            return result;
                        } catch (Throwable error) { discard(entry.selected.castDevice, "native_screen_sdk_failed"); throw error; }
                        finally {
                            if (previous == null) converting.remove(); else converting.set(previous);
                            entry.selected.proofCarrier.finish(entry.outgoing); entry.outgoing = null;
                        }
                    }));
            hooks.add(module.hook(manager.getDeclaredMethod("startMirror", castDevice))
                    .setId("qj.screen.oplus.linker.no_callback").intercept(chain -> {
                        synchronized (this) {
                            if (!pending.containsKey(chain.getArg(0))) return chain.proceed();
                        }
                        discard(chain.getArg(0), "native_screen_consent_callback_unavailable"); return -4;
                    }));
            hooks.add(module.hook(type("i43").getDeclaredMethod("e", castDevice))
                    .setId("qj.screen.oplus.linker.deviceinfo").intercept(chain -> {
                        Object actual = chain.proceed(); Entry entry = converting.get();
                        if (entry != null && chain.getArg(0) == entry.selected.castDevice) {
                            if (!current(entry) || !device.isInstance(actual) || !entry.selected.nativeUid.equals(copiedUid.invoke(actual))
                                    || !entry.selected.deviceType.equals(copiedKind.invoke(actual)))
                                throw new IllegalStateException("native_screen_deviceinfo_changed");
                            if (entry.converted) throw new IllegalStateException("native_screen_deviceinfo_replayed");
                            entry.converted = true;
                            entry.lease.converted(entry.selected, actual);
                            if (!current(entry)) throw new IllegalStateException("native_screen_request_revoked");
                            entry.selected.proofCarrier.apply(entry.outgoing);
                        }
                        return actual;
                    }));
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static boolean valid(String id) { return id != null && !id.isBlank() && id.length() <= 256; }
    private boolean current(Entry entry) {
        try { return !closed && !entry.revoked && entry.lease.current() && SystemClock.elapsedRealtime() < entry.lease.deadlineElapsedMs(); }
        catch (RuntimeException error) { return false; }
    }
    private void prune() {
        pending.entrySet().removeIf(item -> { if (current(item.getValue())) return false; closeEntry(item.getValue()); return true; });
    }
    private void discard(Object selected, String reason) {
        Entry entry;
        synchronized (this) { entry = pending.remove(selected); }
        if (entry != null) { try { entry.lease.failed(reason); } finally { closeEntry(entry); } }
    }
    private void closeEntry(Entry entry) {
        entry.revoked = true; entry.selected.proofCarrier.clear(); entry.lease.close();
    }
    /** Public lifecycle timer calls this; it never probes a device or starts a request. */
    public synchronized void tick() { prune(); }
    public synchronized void sessionChanged() { for (Entry entry : pending.values()) closeEntry(entry); pending.clear(); }
    @Override public void close() {
        closed = true; sessionChanged(); for (HookHandle hook : hooks) hook.unhook(); hooks.clear();
    }
}
