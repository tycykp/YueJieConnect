package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Physical byte carrier in the pinned OEM host process. A connected socket is NOT authenticated
 * control readiness. Only the Rust parent-bound handshake/ready ACKs can issue a stable lease.
 * Never enables Bluetooth, scans, makes discoverable, creates bonds or confirms pairing dialogs.
 */
final class NetworkShareBluetoothBearer implements AutoCloseable {
    static final UUID SERVICE_UUID = UUID.fromString("b2687261-7e55-4a85-a3b2-414a4e435431");
    interface Listener { void connected(Link link); }
    record ServerInfo(long generation, UUID service, boolean secure) {}
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ExecutorService io = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "yj-network-bluetooth"); t.setDaemon(true); return t;
    });
    private final ScheduledThreadPoolExecutor monitor = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "yj-network-bluetooth-watch"); t.setDaemon(true); return t;
    });
    private final AtomicLong generations = new AtomicLong();
    private BluetoothServerSocket server;
    private BluetoothSocket pending;
    private CompletableFuture<Link> pendingResult;
    private Link active;
    private boolean listening, closed;

    NetworkShareBluetoothBearer(Context context, Listener listener) {
        if (context == null || listener == null
                || !NativeBridgeHost.hostCaller(context.getPackageManager(), android.os.Process.myUid()))
            throw new IllegalArgumentException("invalid_bluetooth_context");
        this.context = context; this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        monitor.setRemoveOnCancelPolicy(true);
        monitor.scheduleWithFixedDelay(this::observe, 500, 500, TimeUnit.MILLISECONDS);
    }
    CompletableFuture<ServerInfo> listen() {
        CompletableFuture<ServerInfo> result = new CompletableFuture<>();
        synchronized (this) {
            if (closed || listening || server != null || active != null) return failed("bluetooth_busy_or_closed");
            listening = true;
        }
        try { io.execute(() -> accept(result)); }
        catch (RuntimeException error) { synchronized (this) { listening = false; } result.completeExceptionally(failure("bluetooth_closed")); }
        return result;
    }
    @SuppressLint("MissingPermission") // Every call checks the actual OEM Context; services may still revoke/reject.
    private void accept(CompletableFuture<ServerInfo> result) {
        BluetoothServerSocket opened = null;
        try {
            requireConnect();
            opened = adapter.listenUsingRfcommWithServiceRecord("YueJie network control", SERVICE_UUID);
            long generation;
            synchronized (this) {
                if (closed) throw failure("bluetooth_closed");
                server = opened; generation = generations.incrementAndGet();
            }
            if (!result.complete(new ServerInfo(generation, SERVICE_UUID, true))) return;
            BluetoothSocket socket = opened.accept();
            synchronized (this) { if (server == opened) server = null; }
            try { opened.close(); } catch (IOException ignored) { } opened = null;
            attach(socket);
        } catch (IOException | RuntimeException error) { result.completeExceptionally(failure("bluetooth_listen_failed")); }
        finally {
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            synchronized (this) { if (server == opened) server = null; listening = false; }
        }
    }
    CompletableFuture<Link> connect(BluetoothDevice device) {
        CompletableFuture<Link> result = new CompletableFuture<>();
        synchronized (this) {
            if (closed || pendingResult != null || active != null || device == null) return failed("bluetooth_busy_or_closed");
            pendingResult = result;
        }
        try { io.execute(() -> connectNow(device, result)); }
        catch (RuntimeException error) { synchronized (this) { pendingResult = null; } result.completeExceptionally(failure("bluetooth_closed")); }
        result.whenComplete((link, error) -> { if (result.isCancelled()) cancelConnect(result); });
        return result;
    }
    @SuppressLint("MissingPermission")
    private void connectNow(BluetoothDevice device, CompletableFuture<Link> result) {
        BluetoothSocket socket = null;
        try {
            requireConnect();
            // Ordinary OS bonding is a separate user-approved prerequisite. No createBond/hidden API.
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw failure("bluetooth_bond_required");
            if (granted(Manifest.permission.BLUETOOTH_SCAN)) adapter.cancelDiscovery();
            requireConnect();
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            synchronized (this) {
                if (closed || result.isCancelled()) throw failure("bluetooth_closed");
                pending = socket;
            }
            BluetoothSocket expected = socket;
            var timeout = monitor.schedule(() -> cancelConnect(result), 20, TimeUnit.SECONDS);
            try { socket.connect(); } finally { timeout.cancel(false); }
            synchronized (this) {
                if (pending != expected || closed || result.isCancelled()) throw failure("bluetooth_connect_cancelled");
                pending = null;
            }
            Link link = attach(socket); socket = null;
            if (!result.complete(link)) link.close();
        } catch (IOException | RuntimeException error) { result.completeExceptionally(failure("bluetooth_connect_failed")); }
        finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            synchronized (this) { if (pending == socket) pending = null; if (pendingResult == result) pendingResult = null; }
        }
    }
    private synchronized void cancelConnect(CompletableFuture<Link> result) {
        if (pendingResult != result) return;
        if (pending != null) try { pending.close(); } catch (IOException ignored) { }
        pending = null;
        result.completeExceptionally(failure("bluetooth_connect_cancelled"));
    }
    @SuppressLint("MissingPermission")
    private Link attach(BluetoothSocket socket) throws IOException {
        Link link = null;
        try {
            requireConnect();
            if (!socket.isConnected() || socket.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED)
                throw failure("bluetooth_bond_required");
            synchronized (this) {
                if (closed || active != null) throw failure("bluetooth_busy_or_closed");
                link = new Link(socket, generations.incrementAndGet()); active = link;
            }
            link.start();
            listener.connected(link); // Raw untrusted bytes: listener MUST perform the child handshake.
            return link;
        } catch (IOException | RuntimeException error) {
            if (link != null) link.close(); else socket.close();
            throw error;
        }
    }
    @SuppressLint("MissingPermission")
    private void requireConnect() {
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) throw failure("bluetooth_connect_permission_denied");
        if (adapter == null || !adapter.isEnabled()) throw failure("bluetooth_disabled");
    }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    @SuppressLint("MissingPermission")
    private void observe() {
        try {
            requireConnect();
            Link link; synchronized (this) { link = active; }
            if (link != null && (!link.alive() || link.socket.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED))
                link.finish("bluetooth_transport_or_bond_lost");
        } catch (RuntimeException error) { closePhysical("bluetooth_unavailable"); }
    }
    private synchronized void closePhysical(String reason) {
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        server = null;
        if (pending != null) try { pending.close(); } catch (IOException ignored) { }
        pending = null;
        if (pendingResult != null) pendingResult.completeExceptionally(failure(reason));
        if (active != null) active.finish(reason);
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        closePhysical("bluetooth_closed"); monitor.shutdownNow(); io.shutdownNow();
    }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static <T> CompletableFuture<T> failed(String code) { return CompletableFuture.failedFuture(failure(code)); }

    /** Byte-preserving bounded scratch space; no framing, parsing, authentication or diagnostics. */
    static String pump(InputStream source, OutputStream destination, BooleanSupplier stopped) {
        byte[] buffer = new byte[8192];
        try {
            while (!stopped.getAsBoolean()) {
                int length = source.read(buffer);
                if (length < 0) return "bluetooth_eof";
                if (stopped.getAsBoolean()) return "bluetooth_closed";
                if (length != 0) { destination.write(buffer, 0, length); destination.flush(); }
            }
            return "bluetooth_closed";
        } catch (IOException | RuntimeException error) { return "bluetooth_io_failed"; }
        finally { java.util.Arrays.fill(buffer, (byte) 0); }
    }

    final class Link implements AutoCloseable {
        private final BluetoothSocket socket;
        private final long generation;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final CompletableFuture<String> termination = new CompletableFuture<>();
        private final ParcelFileDescriptor pump;
        private ParcelFileDescriptor nativeEnd;
        private final InputStream nativeInput, bluetoothInput;
        private final OutputStream nativeOutput, bluetoothOutput;
        private Link(BluetoothSocket socket, long generation) throws IOException {
            this.socket = socket; this.generation = generation;
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
            nativeEnd = pair[0]; pump = pair[1];
            InputStream read = null; OutputStream write = null;
            try {
                read = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(pump.getFileDescriptor()));
                write = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(pump.getFileDescriptor()));
                bluetoothInput = socket.getInputStream(); bluetoothOutput = socket.getOutputStream();
                nativeInput = read; nativeOutput = write;
            } catch (IOException | RuntimeException error) {
                if (read != null) try { read.close(); } catch (IOException ignored) { }
                if (write != null) try { write.close(); } catch (IOException ignored) { }
                nativeEnd.close(); pump.close(); throw error;
            }
        }
        long generation() { return generation; }
        BluetoothSocket socket() { return socket; }
        /** Exactly one duplex handoff to JNI. Caller owns the returned FD. Closing it causes pump EOF.
         * Binding that FD to a parent session and authenticating it is the shared owner's job.
         */
        synchronized ParcelFileDescriptor duplicateDuplex() throws IOException {
            if (ended.get() || nativeEnd == null) throw failure("bluetooth_fd_already_handed_off");
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(nativeEnd.getFileDescriptor());
            nativeEnd.close(); nativeEnd = null; return result;
        }
        boolean alive() { return !ended.get() && socket.isConnected(); }
        CompletableFuture<String> termination() { return termination.thenApply(reason -> reason); }
        private void start() {
            io.execute(() -> copy(bluetoothInput, nativeOutput));
            io.execute(() -> copy(nativeInput, bluetoothOutput));
        }
        private void copy(InputStream source, OutputStream destination) {
            finish(pump(source, destination, ended::get));
        }
        private void finish(String reason) {
            if (!ended.compareAndSet(false, true)) return;
            try { Os.shutdown(pump.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
            try { nativeInput.close(); } catch (IOException ignored) { }
            try { nativeOutput.close(); } catch (IOException ignored) { }
            try { pump.close(); } catch (IOException ignored) { }
            synchronized (this) { if (nativeEnd != null) try { nativeEnd.close(); } catch (IOException ignored) { } nativeEnd = null; }
            synchronized (NetworkShareBluetoothBearer.this) { if (active == this) active = null; }
            termination.complete(reason);
        }
        @Override public void close() { finish("bluetooth_closed"); }
        @Override public String toString() { return "NetworkShareBluetoothLink[physical carrier only]"; }
    }
}
