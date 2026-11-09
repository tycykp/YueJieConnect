package io.github.yuejie.connect;

import android.content.Context;
import io.github.libxposed.api.XposedModule;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Assembly point for the shared owner. Supplies neither peer authorization nor a pretend transport. */
final class NetworkShareBinding implements AutoCloseable {
    private final NetworkShareEndpoint endpoint;
    private final NetworkShareController controller;
    private final Supplier<NetworkShareController.Target> selected;
    private final Consumer<NetworkShareController.Status> properties;
    private final XiaomiNetworkAdapter xiaomi;
    private final OplusNetworkAdapter oplus;
    private AutoCloseable diagnostics;
    private volatile boolean closed;
    private NetworkShareController.Target bound;
    private volatile View observed;

    NetworkShareBinding(XposedModule module, Context context, ClassLoader loader,
            NetworkShareController.Transport transport, Supplier<NetworkShareController.Target> selected,
            Supplier<NetworkSharePolicy.Consent> consent, Consumer<NetworkShareController.Status> properties)
            throws ReflectiveOperationException {
        this.selected = Objects.requireNonNull(selected); this.properties = Objects.requireNonNull(properties);
        endpoint = new NetworkShareEndpoint(context, this::observeLocal);
        controller = new NetworkShareController(endpoint, transport, this::onStatus);
        xiaomi = "com.milink.service".equals(context.getPackageName())
                ? new XiaomiNetworkAdapter(module, loader, controller, selected, consent) : null;
        oplus = "com.oplus.linker".equals(context.getPackageName())
                ? new OplusNetworkAdapter(module, loader, controller, selected, consent) : null;
        try {
            if (xiaomi != null) xiaomi.install();
            if (oplus != null) oplus.install();
            bound = selected.get();
            if (observed != null) controller.observeLocal(bound, observed);
            installDiagnostics(context);
        } catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }
    NetworkShareEndpoint endpoint() { return endpoint; }
    NetworkShareController controller() { return controller; }

    /** Call from projection/session/grant callbacks, including removal. New pairing does not adopt an old share. */
    synchronized void sessionChanged() {
        if (closed) return;
        NetworkShareController.Target next = selected.get();
        if (Objects.equals(bound, next)) return;
        bound = next; controller.sessionLost(); endpoint.invalidate();
    }
    /** Notify immediately when the authenticated control bearer/grant stops satisfying ready(Target). */
    void controlChanged() {
        if (closed) return;
        NetworkShareController.Target target = selected.get();
        if (target == null || !controller.ready(target)) { controller.sessionLost(); endpoint.invalidate(); }
    }
    void grantRevoked() { if (!closed) { controller.sessionLost(); endpoint.invalidate(); } }
    CompletableFuture<View> retryRecovery() { return endpoint.retryRecovery(); }
    private void observeLocal(View view) {
        observed = view;
        if (!closed && controller != null) controller.observeLocal(selected.get(), view);
    }
    private void onStatus(NetworkShareController.Status status) {
        if (closed) return;
        if (xiaomi != null) xiaomi.onStatus(status);
        if (oplus != null) oplus.onStatus(status);
        // The shared publisher must check target/session again and update only this native peer.
        properties.accept(status);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (diagnostics != null) try { diagnostics.close(); } catch (Exception ignored) { }
        if (xiaomi != null) xiaomi.close();
        if (oplus != null) oplus.close();
        controller.close();
    }
    private void installDiagnostics(Context context) {
        // This class exists only in debug builds. A read-only probe cannot create a control
        // lease or invoke start/join/renew/stop; absence must not change business behavior.
        try {
            Class<?> probe = Class.forName("io.github.yuejie.connect.NetworkShareNativeProbe", false,
                    NetworkShareBinding.class.getClassLoader());
            diagnostics = (AutoCloseable) probe.getDeclaredMethod("install", Context.class, NetworkShareBinding.class)
                    .invoke(null, context, this);
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Diagnostic-only. */ }
    }
}
