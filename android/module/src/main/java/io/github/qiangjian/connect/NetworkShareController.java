package io.github.qiangjian.connect;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static io.github.qiangjian.connect.NetworkShareSession.*;

/** Phone provider -> tablet consumer orchestration. The bridge supplies authenticated transport.
 * An ordinary LAN connection is not sufficient: ready must guarantee control survives the Wi-Fi switch.
 */
final class NetworkShareController {
    record Target(String id, String session) {
        Target {
            if (id == null || id.isEmpty() || id.length() > 256 || session == null || !session.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("invalid_target");
        }
    }
    record Status(Target target, String request, Phase phase, Upstream upstream, String error) {}
    interface Local {
        CompletableFuture<NetworkShareEndpoint.Availability> availability();
        CompletableFuture<NetworkShareEndpoint.Offer> startProvider(String session, String request, Upstream upstream, boolean meteredAllowed);
        CompletableFuture<View> joinConsumer(String session, String request, NetworkShareEndpoint.Offer offer, boolean meteredAllowed);
        CompletableFuture<View> confirmConsumer(String session, String request);
        CompletableFuture<View> stop(String session, String request);
        CompletableFuture<View> renew(String session, String request);
        void close();
    }
    interface Transport {
        boolean ready(Target target);
        CompletableFuture<NetworkShareEndpoint.Availability> availability(Target target);
        CompletableFuture<NetworkShareEndpoint.Offer> start(Target target, String request, Upstream upstream, boolean meteredAllowed);
        CompletableFuture<View> confirm(Target target, String request);
        CompletableFuture<View> join(Target target, String request, NetworkShareEndpoint.Offer offer, boolean meteredAllowed);
        CompletableFuture<View> stop(Target target, String request);
        CompletableFuture<View> renew(Target target, String request);
    }
    private final Local local;
    private final Transport transport;
    private final Consumer<Status> listener;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "qj-network-controller"); t.setDaemon(true); return t;
    });
    private volatile Status status = new Status(null, null, Phase.STOPPED, null, null);
    private volatile boolean closed;
    private boolean stopping, localJoinRequested, remoteStartRequested, localProvider, passive, renewing;
    private CompletableFuture<Status> pendingStart, pendingStop;
    private String observedSession;
    private long observedGeneration;

    NetworkShareController(Local local, Transport transport, Consumer<Status> listener) {
        this.local = local; this.transport = transport; this.listener = listener;
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(this::renew, 30, 30, TimeUnit.SECONDS);
    }
    Status status() { return status; }
    boolean ready(Target target) {
        try { return !closed && transport.ready(target); } catch (RuntimeException error) { return false; }
    }

    CompletableFuture<Status> start(Target target, Upstream upstream, boolean meteredAllowed) {
        return start(target, upstream, meteredAllowed, false);
    }
    CompletableFuture<Status> startProviding(Target target, Upstream upstream, boolean meteredAllowed) {
        return start(target, upstream, meteredAllowed, true);
    }
    private CompletableFuture<Status> start(Target target, Upstream upstream, boolean meteredAllowed, boolean provide) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("controller_closed"));
        CompletableFuture<Status> result = new CompletableFuture<>();
        enqueue(result, () -> {
            if (!ready(target)) { result.completeExceptionally(new IllegalStateException("stable_control_unavailable")); return; }
            if (status.phase() != Phase.STOPPED && status.phase() != Phase.FAILED) {
                result.completeExceptionally(new IllegalStateException("network_busy")); return;
            }
            String request = "network:" + UUID.randomUUID();
            stopping = false; passive = false; renewing = false;
            localJoinRequested = false; remoteStartRequested = false; pendingStart = result; localProvider = provide;
            publish(new Status(target, request, Phase.REQUESTED, upstream, null));
            call(local::availability).thenCombine(call(() -> transport.availability(target)), (here, there) -> {
                NetworkShareEndpoint.Availability provider = provide ? here : there;
                NetworkShareEndpoint.Availability consumer = provide ? there : here;
                if (!consumer.consumer() || !provider.provider() || provider.upstream() != upstream)
                    throw new IllegalStateException("network_unavailable");
                if ((provider.metered() || upstream == Upstream.CELLULAR) && !meteredAllowed)
                    throw new SecurityException("metered_network_not_authorized");
                return there;
            }).whenCompleteAsync((available, error) -> {
                if (!current(target, request) || stopping) return;
                if (error != null) { stopNow("availability_failed"); return; }
                if (!ready(target)) { stopNow("stable_control_lost"); return; }
                if (provide) localJoinRequested = true; else remoteStartRequested = true;
                publish(new Status(target, request, Phase.STARTING, upstream, null));
                call(() -> provide ? local.startProvider(target.session(), request, upstream, meteredAllowed)
                        : transport.start(target, request, upstream, meteredAllowed)).whenCompleteAsync((offer, startError) -> {
                    if (!current(target, request) || stopping) return;
                    if (startError != null || offer == null) { stopNow("provider_start_failed"); return; }
                    if (!Objects.equals(offer.session(), target.session()) || !Objects.equals(offer.request(), request)
                            || offer.upstream() != upstream) { stopNow("offer_identity_mismatch"); return; }
                    if (!ready(target)) { stopNow("stable_control_lost"); return; }
                    if (provide) remoteStartRequested = true; else localJoinRequested = true;
                    call(() -> provide ? transport.join(target, request, offer, meteredAllowed)
                            : local.joinConsumer(target.session(), request, offer, meteredAllowed)).whenCompleteAsync((joined, joinError) -> {
                        if (!current(target, request) || stopping) return;
                        if (joinError != null || !matches(joined, target, request, Role.CONSUMER, Phase.ACTIVE) || joined.upstream() != upstream) {
                            stopNow("consumer_join_failed"); return;
                        }
                        if (!ready(target)) { stopNow("stable_control_lost"); return; }
                        call(() -> provide ? local.confirmConsumer(target.session(), request)
                                : transport.confirm(target, request)).whenCompleteAsync((confirmed, confirmError) -> {
                            if (!current(target, request) || stopping) return;
                            if (confirmError != null || !matches(confirmed, target, request, Role.PROVIDER, Phase.ACTIVE) || confirmed.upstream() != upstream) {
                                stopNow("provider_confirmation_failed"); return;
                            }
                            if (!ready(target)) { stopNow("stable_control_lost"); return; }
                            publish(new Status(target, request, Phase.ACTIVE, upstream, null)); result.complete(status);
                        }, worker);
                    }, worker);
                }, worker);
            }, worker);
        });
        return result;
    }

    CompletableFuture<Status> stop() {
        if (worker.isShutdown()) return CompletableFuture.completedFuture(status);
        CompletableFuture<Status> result = new CompletableFuture<>();
        enqueue(result, () -> {
            if (status.phase() == Phase.STOPPED || status.phase() == Phase.FAILED) { result.complete(status); return; }
            if (pendingStop != null && !pendingStop.isDone()) { pipe(pendingStop, result); return; }
            pendingStop = result; stopNow(null);
        });
        return result;
    }
    void sessionLost() { dispatch(() -> {
        if (status.target() != null && status.phase() != Phase.STOPPED && status.phase() != Phase.FAILED) stopNow("session_lost");
    }); }

    /** An incoming typed operation may own the local endpoint before this process initiates anything.
     * Observe its native state for callbacks and cancellation; only its real remote coordinator renews it.
     */
    void observeLocal(Target target, View view) {
        if (target == null || view == null || !target.session().equals(view.ticket().session()) || closed) return;
        dispatch(() -> {
            if (closed) return;
            if (target.session().equals(observedSession) && view.ticket().generation() < observedGeneration) return;
            observedSession = target.session(); observedGeneration = view.ticket().generation();
            boolean same = Objects.equals(status.target(), target) && Objects.equals(status.request(), view.ticket().request());
            boolean ended = status.phase() == Phase.STOPPED || status.phase() == Phase.FAILED;
            if (same) {
                if (ended || stopping) return;
                if (view.phase() == Phase.STOPPING || view.phase() == Phase.STOPPED || view.phase() == Phase.FAILED
                        || view.phase() == Phase.RECOVERY_REQUIRED) {
                    stopNow(view.error() == null ? "native_network_stopped" : view.error());
                } else if (passive) publish(new Status(target, view.ticket().request(), view.phase(), view.upstream(), view.error()));
                return;
            }
            if (!ended || view.phase() == Phase.STOPPED || view.phase() == Phase.FAILED) return;
            passive = true; localProvider = view.role() == Role.PROVIDER;
            localJoinRequested = true; remoteStartRequested = true; stopping = false; renewing = false;
            publish(new Status(target, view.ticket().request(), view.phase(), view.upstream(), view.error()));
        });
    }
    synchronized void close() {
        if (closed) return;
        closed = true;
        dispatch(() -> { if (status.target() == null || status.phase() == Phase.STOPPED || status.phase() == Phase.FAILED)
            shutdown(); else stopNow("controller_closed"); });
    }

    private void stopNow(String reason) {
        if (stopping) return;
        stopping = true;
        Status before = status;
        publish(new Status(before.target(), before.request(), Phase.STOPPING, before.upstream(), reason));
        if (pendingStart != null && !pendingStart.isDone()) pendingStart.completeExceptionally(new IllegalStateException(reason == null ? "cancelled" : reason));
        CompletableFuture<Boolean> here = localJoinRequested
                ? call(() -> local.stop(before.target().session(), before.request())).handle((v, e) -> e == null && terminal(v, before.target(), before.request(), localProvider ? Role.PROVIDER : Role.CONSUMER))
                : CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> there = remoteStartRequested
                ? call(() -> transport.stop(before.target(), before.request())).handle((v, e) -> e == null && terminal(v, before.target(), before.request(), localProvider ? Role.CONSUMER : Role.PROVIDER))
                : CompletableFuture.completedFuture(true);
        here.thenCombine(there, (a, b) -> a && b).whenCompleteAsync((restored, error) -> {
            if (!current(before.target(), before.request())) return;
            boolean verified = error == null && Boolean.TRUE.equals(restored);
            publish(new Status(before.target(), before.request(), verified ? reason == null ? Phase.STOPPED : Phase.FAILED
                    : Phase.RECOVERY_REQUIRED, before.upstream(), verified ? reason : "network_restore_unverified"));
            stopping = false;
            if (pendingStop != null) pendingStop.complete(status);
            if (closed && verified) shutdown();
        }, worker);
    }

    private void renew() {
        Status v = status;
        if (v.phase() != Phase.ACTIVE || stopping || renewing || passive) return;
        if (!ready(v.target())) { stopNow("session_lost"); return; }
        renewing = true;
        call(() -> local.renew(v.target().session(), v.request())).thenCombine(call(() -> transport.renew(v.target(), v.request())),
                (a, b) -> matches(a, v.target(), v.request(), localProvider ? Role.PROVIDER : Role.CONSUMER, Phase.ACTIVE)
                        && matches(b, v.target(), v.request(), localProvider ? Role.CONSUMER : Role.PROVIDER, Phase.ACTIVE)
                        && a.upstream() == v.upstream() && b.upstream() == v.upstream())
                .whenCompleteAsync((ok, error) -> {
                    if (!current(v.target(), v.request())) return;
                    renewing = false;
                    if (!stopping && (error != null || !Boolean.TRUE.equals(ok) || !ready(v.target()))) stopNow("lease_renew_failed");
                }, worker);
    }
    private boolean current(Target target, String request) {
        return Objects.equals(status.target(), target) && Objects.equals(status.request(), request)
                && status.phase() != Phase.STOPPED && status.phase() != Phase.FAILED && status.phase() != Phase.RECOVERY_REQUIRED;
    }
    private static boolean matches(View v, Target target, String request, Role role, Phase phase) {
        return v != null && v.ticket().session().equals(target.session()) && v.ticket().request().equals(request)
                && v.role() == role && v.phase() == phase;
    }
    private static boolean terminal(View v, Target target, String request, Role role) {
        return matches(v, target, request, role, Phase.STOPPED) || matches(v, target, request, role, Phase.FAILED);
    }
    private void publish(Status value) {
        status = value;
        try { listener.accept(value); } catch (RuntimeException ignored) { /* UI listener cannot abandon cleanup. */ }
    }
    private static <T> void pipe(CompletableFuture<T> from, CompletableFuture<T> to) {
        from.whenComplete((v, e) -> { if (e == null) to.complete(v); else to.completeExceptionally(e); });
    }
    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> operation) {
        try { return Objects.requireNonNull(operation.get()).orTimeout(35, TimeUnit.SECONDS); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private void enqueue(CompletableFuture<?> result, Runnable action) {
        try { worker.execute(action); }
        catch (java.util.concurrent.RejectedExecutionException error) { result.completeExceptionally(new IllegalStateException("controller_closed")); }
    }
    private void dispatch(Runnable action) {
        try { worker.execute(action); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Already restored and shut down. */ }
    }
    private void shutdown() { try { local.close(); } finally { worker.shutdown(); } }
}
