package io.github.yuejie.connect;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import static io.github.yuejie.connect.TelephonySyncController.*;

/** HyperOS tablet's actual RelayConnectionService. Install once for the phone process lifetime. */
final class XiaomiTelephonyRelayAdapter {
    static final String PACKAGE = "com.android.phone";
    static final String PREFIX = "com.android.services.telephony.relay.phonecall.";
    static final String TOKEN = "io.github.yuejie.connect.telephony.RELAY_TOKEN";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final Map<String, Lease> pending = new HashMap<>();
    private final Map<Connection, Lease> connections = new IdentityHashMap<>();
    private final Class<?> serviceType, connectionType, relayType, registryType;
    private final Method gate, manager, enable, connectionManager, nativeConnections, forceDisconnected;
    private final Method padInstance, padChannel;
    private Binding current;
    private boolean installed;
    private static final class Lease {
        final String token;
        final Binding binding;
        final Object manager;
        final PhoneAccountHandle account;
        final boolean enabledHere;
        Connection connection;
        Lease(String token, Binding binding, Object manager, PhoneAccountHandle account, boolean enabledHere) {
            this.token = token; this.binding = binding; this.manager = manager;
            this.account = account; this.enabledHere = enabledHere;
        }
    }
    XiaomiTelephonyRelayAdapter(Context context, ClassLoader loader) throws ReflectiveOperationException {
        this.context = context;
        serviceType = Class.forName(PREFIX + "RelayConnectionService", false, loader);
        connectionType = Class.forName(PREFIX + "RelayConnection", false, loader);
        relayType = Class.forName(PREFIX + "RelayUtils$Relay", false, loader);
        registryType = Class.forName(PREFIX + "RelayPhoneAccountRegistry", false, loader);
        Class<?> managerType = Class.forName(PREFIX + "RelayManager", false, loader);
        Class<?> connectionsType = Class.forName(PREFIX + "RelayConnectionsManager", false, loader);
        Class<?> padType = Class.forName(PREFIX + "PadContinuityController", false, loader);
        if (!Connection.class.isAssignableFrom(connectionType)) throw new IllegalStateException("invalid_relay_class");
        gate = relayType.getDeclaredMethod("padSupportRelay", Context.class);
        manager = relayType.getDeclaredMethod("getRelayManager");
        enable = managerType.getDeclaredMethod("enableRelayPhoneAccount", boolean.class);
        connectionManager = managerType.getDeclaredMethod("getRelayConnectionsManager");
        nativeConnections = connectionsType.getDeclaredMethod("getRelayConnections");
        forceDisconnected = connectionType.getDeclaredMethod("forceDisconnected", DisconnectCause.class);
        padInstance = padType.getDeclaredMethod("getInstance");
        padChannel = padType.getDeclaredMethod("getChannelInRelay");
    }
    void install(XposedModule module) throws ReflectiveOperationException {
        if (installed) throw new IllegalStateException("already_installed");
        // Verify all exact declarations before adding any hooks. No inherited global Connection hooks.
        Method incoming = serviceType.getDeclaredMethod("onCreateIncomingConnection", PhoneAccountHandle.class, ConnectionRequest.class);
        String[] gestures = {"onAnswer", "onReject", "onReject", "onDisconnect", "onHold", "onUnhold", "onAbort"};
        Class<?>[][] parameters = {{int.class}, {}, {String.class}, {}, {}, {}, {}};
        Action[] actions = {Action.ANSWER, Action.REJECT, Action.REJECT, Action.END, Action.HOLD, Action.UNHOLD, null};
        Method[] methods = new Method[gestures.length];
        for (int i = 0; i < methods.length; i++) methods[i] = connectionType.getDeclaredMethod(gestures[i], parameters[i]);
        try {
            hooks.add(module.hook(incoming).setId("yj.telephony.relay.incoming").intercept(chain -> {
                ConnectionRequest request = (ConnectionRequest) chain.getArg(1);
                Bundle extras = request == null ? null : request.getExtras();
                if (extras == null || !extras.containsKey(TOKEN)) return chain.proceed();
                // Even a late/unknown token is consumed, never handed to the OEM's number-based fallback.
                if (!main.getLooper().isCurrentThread()) return failed();
                String token = extras.getString(TOKEN);
                Lease lease = pending.get(token);
                if (lease == null || lease.binding != current || !lease.account.equals(request.getAccountHandle())
                        || !lease.binding.relay.canCreate(token)) return failed();
                Object value = chain.proceed();
                if (!(value instanceof Connection connection) || !connectionType.isInstance(value)
                        || connection.getState() == Connection.STATE_DISCONNECTED) {
                    lease.binding.relay.nativeLost(token); return failed();
                }
                lease.connection = connection; connections.put(connection, lease);
                Bundle own = new Bundle(); own.putString(TOKEN, token);
                own.putBoolean("telecomm.EXTRA_RELAY_CALL", true);
                // Clear cached OEM contact data: the public snapshot intentionally contains no identity.
                connection.removeExtras("telecomm.EXTRA_CONTACT");
                connection.setCallerDisplayName(null, TelecomManager.PRESENTATION_RESTRICTED);
                connection.putExtras(own);
                if (!lease.binding.relay.nativeCreated(token)) { release(lease, "creation_rejected"); return failed(); }
                return connection;
            }));
            for (int i = 0; i < methods.length; i++) {
                final Action action = actions[i];
                final boolean answer = i == 0;
                hooks.add(module.hook(methods[i]).setId("yj.telephony.relay.gesture." + i).intercept(chain -> {
                    Connection connection = (Connection) chain.getThisObject();
                    Bundle extras = connection.getExtras();
                    if (extras == null || !extras.containsKey(TOKEN)) return chain.proceed();
                    // Owned connections never reach OEM sendOperateRequest, including after revocation.
                    boolean audioOnly = !answer || ((Integer) chain.getArg(0)) == 0;
                    main.post(() -> {
                        Lease lease = connections.get(connection);
                        if (lease == null || lease.binding != current) return;
                        if (action == null) lease.binding.relay.nativeLost(lease.token);
                        else if (audioOnly) lease.binding.relay.operate(lease.token, action);
                    });
                    return null;
                }));
            }
            // OEM radio/channel teardown must also invalidate our exact remote binding.
            hooks.add(module.hook(forceDisconnected).setId("yj.telephony.relay.destroy").intercept(chain -> {
                Connection connection = (Connection) chain.getThisObject();
                Object value = chain.proceed();
                main.post(() -> { Lease lease = connections.get(connection); if (lease != null) lease.binding.relay.nativeLost(lease.token); });
                return value;
            }));
            hooks.add(module.hook(connectionType.getDeclaredMethod("tearDown")).setId("yj.telephony.relay.teardown").intercept(chain -> {
                Connection connection = (Connection) chain.getThisObject();
                Object value = chain.proceed();
                main.post(() -> { Lease lease = connections.get(connection); if (lease != null) lease.binding.relay.nativeLost(lease.token); });
                return value;
            }));
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            for (HookHandle hook : hooks) hook.unhook(); hooks.clear(); throw failure;
        }
    }
    /** Main thread; replacement withdraws the old native presentation before accepting the new session. */
    Binding bind(Session session, Policy policy, TelephonySyncRelay.Sender sender) {
        return bind(session, policy, sender, (command, observed) -> { });
    }
    Binding bind(Session session, Policy policy, TelephonySyncRelay.Sender sender,
                 java.util.function.BiConsumer<Command, Snapshot> observedGestures) {
        requireMain();
        if (!installed) throw new IllegalStateException("hooks_not_installed");
        if (current != null) current.closeOnMain();
        current = new Binding(session, policy, sender, observedGestures); return current;
    }
    final class Binding implements AutoCloseable, TelephonySyncRelay.NativeUi {
        private final TelephonySyncRelay relay;
        private final Map<String, Connection> audioConnections = new ConcurrentHashMap<>();
        private final Runnable tick = new Runnable() {
            @Override public void run() { if (!closed) { relay.tick(); main.postDelayed(this, 500); } }
        };
        private volatile boolean closed;
        Binding(Session session, Policy policy, TelephonySyncRelay.Sender sender, java.util.function.BiConsumer<Command, Snapshot> observedGestures) {
            relay = new TelephonySyncRelay(session, policy, this, sender, SystemClock::elapsedRealtime, main::post, observedGestures);
            main.post(tick);
        }
        CompletableFuture<TelephonySyncRelay.Receipt> accept(Session source, Snapshot snapshot, long deadlineElapsedMs) {
            CompletableFuture<TelephonySyncRelay.Receipt> result = new CompletableFuture<>();
            main.post(() -> relay.accept(source, snapshot, deadlineElapsedMs - SystemClock.elapsedRealtime())
                    .whenComplete((value, failure) -> { if (failure == null) result.complete(value); else result.completeExceptionally(failure); }));
            return result;
        }
        TelephonySyncAudioSession.Policy audioPolicy() {
            return (source, call, role) -> {
                Connection connection = audioConnections.get(call);
                return role == TelephonySyncAudioSession.Role.PAD && !closed && connection != null
                        && connection.getState() == Connection.STATE_ACTIVE && relay.audioActive(source, call);
            };
        }
        void cancelPending(Session source, String call, long revision) { main.post(() -> relay.cancelPending(source, call, revision)); }
        @Override public void incoming(String token) throws Exception {
            requireMain();
            if (closed || current != this) throw new IllegalStateException("session_invalid");
            if (!Boolean.TRUE.equals(relayType.getDeclaredMethod("isRelaySide").invoke(null)))
                throw new IllegalStateException("not_native_tablet");
            if (!"".equals(gate.invoke(null, context))) throw new IllegalStateException("native_relay_not_ready");
            Object host = manager.invoke(null);
            if (host == null) throw new IllegalStateException("native_manager_missing");
            PhoneAccountHandle account = (PhoneAccountHandle) registryType.getDeclaredField("PHONE_ACCOUNT_HANDLE").get(null);
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            PhoneAccount previous = telecom.getPhoneAccount(account);
            boolean enableHere = previous == null || !previous.isEnabled();
            Lease lease = new Lease(token, this, host, account, enableHere); pending.put(token, lease);
            if (enableHere) enable.invoke(host, true);
            PhoneAccount ready = telecom.getPhoneAccount(account);
            if (ready == null || !ready.isEnabled()) throw new IllegalStateException("native_account_disabled");
            Bundle extras = new Bundle(); extras.putString(TOKEN, token);
            extras.putBoolean("telecomm.EXTRA_RELAY_CALL", true);
            // No synthetic telephone number and no Apple relay extras.
            telecom.addNewIncomingCall(account, extras);
        }
        @Override public void state(String token, Snapshot snapshot) {
            requireMain();
            Lease lease = pending.get(token);
            if (lease == null || lease.binding != this || lease.connection == null) throw new IllegalStateException("native_call_missing");
            audioConnections.remove(snapshot.call());
            switch (snapshot.state()) {
                case RINGING -> lease.connection.setRinging();
                case ACTIVE -> { lease.connection.setActive(); audioConnections.put(snapshot.call(), lease.connection); }
                case HOLDING -> lease.connection.setOnHold();
                case DISCONNECTING -> { /* Await the authenticated terminal state; no local success. */ }
                case ENDED -> release(lease, "remote_ended");
                default -> throw new IllegalStateException("invalid_relay_state");
            }
        }
        @Override public void dismiss(String token, String reason) { requireMain(); Lease lease = pending.get(token); if (lease != null && lease.binding == this) release(lease, reason); }
        @Override public void close() { main.post(this::closeOnMain); }
        private void closeOnMain() {
            if (closed) return;
            closed = true; audioConnections.clear(); main.removeCallbacks(tick); relay.close(); if (current == this) current = null;
        }
    }
    private void release(Lease lease, String reason) {
        pending.remove(lease.token); // Remove first: forceDisconnected's hook cannot recursively release it.
        if (lease.connection != null) {
            lease.binding.audioConnections.values().removeIf(value -> value == lease.connection);
            connections.remove(lease.connection);
            try { forceDisconnected.invoke(lease.connection, new DisconnectCause(DisconnectCause.REMOTE)); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                lease.connection.setDisconnected(new DisconnectCause(DisconnectCause.ERROR)); lease.connection.destroy();
            }
        }
        if (!lease.enabledHere) return;
        try {
            Object hostConnections = nativeConnections.invoke(connectionManager.invoke(lease.manager));
            // Never disable an account now in use by the OEM's own relay channel or another native call.
            if (hostConnections instanceof Set<?> set && set.isEmpty() && padChannel.invoke(padInstance.invoke(null)) == null)
                enable.invoke(lease.manager, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Do not tear down unknown OEM activity. */ }
    }
    private static Connection failed() { return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR)); }
    private void requireMain() { if (!main.getLooper().isCurrentThread()) throw new IllegalStateException("main_thread_required"); }
}
