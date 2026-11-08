package io.github.qiangjian.connect;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** SystemManager phone Binder anchor plus one-use reverse kernel UID/PID challenge. No telephony query. */
final class TelephonySyncPhoneProof implements AutoCloseable {
    private static final String DESCRIPTOR = "io.github.qiangjian.connect.ProcessProof.v1";
    private static final String CALLBACK = "io.github.qiangjian.connect.ProcessProofCallback.v1";
    private final Supplier<IBinder> actualSystemPhoneBinder;
    private final IntSupplier pinnedPhoneUid;
    private IBinder remote;
    private String nonce;
    private long deadline;
    private int verifiedUid = -1, verifiedPid = -1;
    private boolean closed, pending;
    /** Binder supplier MUST query OS ServiceManager's fixed "phone", never a caller/wire Binder. */
    TelephonySyncPhoneProof(Supplier<IBinder> actualSystemPhoneBinder, IntSupplier pinnedPhoneUid) {
        this.actualSystemPhoneBinder = java.util.Objects.requireNonNull(actualSystemPhoneBinder);
        this.pinnedPhoneUid = java.util.Objects.requireNonNull(pinnedPhoneUid);
    }
    synchronized boolean verifies(int uid, int pid) {
        return !closed && remote != null && remote.isBinderAlive() && uid == verifiedUid && pid == verifiedPid;
    }
    synchronized void start() {
        if (closed || pending || (remote != null && remote.isBinderAlive() && verifiedPid > 0)) return;
        pending = true;
        Thread worker = new Thread(this::prove, "qj-phone-process-proof"); worker.setDaemon(true); worker.start();
    }
    private void prove() {
        IBinder binder = null;
        try {
            if (pinnedPhoneUid.getAsInt() < 0) return;
            binder = actualSystemPhoneBinder.get(); if (binder == null || !binder.isBinderAlive()) return;
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            StringBuilder text = new StringBuilder(64);
            for (byte value : random) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            final IBinder selected = binder; final String challenge = text.toString();
            synchronized (this) {
                if (closed) return;
                remote = binder; nonce = challenge; deadline = SystemClock.elapsedRealtime() + 5000; verifiedUid = verifiedPid = -1;
            }
            binder.linkToDeath(() -> invalidate(selected), 0);
            IBinder callback = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    data.enforceInterface(CALLBACK); String echoed = data.readString();
                    int uid = Binder.getCallingUid(), pid = Binder.getCallingPid(); boolean accepted = false;
                    synchronized (TelephonySyncPhoneProof.this) {
                        if (!closed && remote == selected && selected.isBinderAlive() && challenge.equals(nonce) && challenge.equals(echoed)
                                && data.dataAvail() == 0 && SystemClock.elapsedRealtime() < deadline
                                && uid == pinnedPhoneUid.getAsInt() && pid > 0 && reply != null) {
                            verifiedUid = uid; verifiedPid = pid; nonce = null; accepted = true;
                        }
                    }
                    if (reply != null) { reply.writeNoException(); reply.writeInt(accepted ? 1 : 0); }
                    return true;
                }
            };
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR); data.writeString(challenge); data.writeStrongBinder(callback);
                if (!binder.transact(OplusTelephonyProcessAdapter.PROOF_TRANSACTION, data, reply, 0)) throw new RemoteException();
                reply.readException();
                synchronized (this) { if (verifiedPid <= 0 || remote != binder) invalidate(binder); }
            } finally { data.recycle(); reply.recycle(); }
        } catch (RuntimeException | RemoteException failure) { invalidate(binder); }
        finally { synchronized (this) { pending = false; } }
    }
    private synchronized void invalidate(IBinder expected) {
        if (remote != expected) return; remote = null; verifiedUid = verifiedPid = -1; nonce = null;
    }
    @Override public synchronized void close() { closed = true; invalidate(remote); }
}
