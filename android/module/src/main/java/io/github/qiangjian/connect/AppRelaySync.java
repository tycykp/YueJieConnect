package io.github.qiangjian.connect;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Native directory pull, scoped to one authenticated peer/session. A request is never a receipt. */
final class AppRelaySync implements AutoCloseable {
    interface Native {
        CompletableFuture<AppRelayTasks.Result> request(String requestId, String sourcePackage);
        default CompletableFuture<AppRelayTasks.Result> request(String requestId, String sourcePackage, String nativeOwnerToken) {
            if (!nativeOwnerToken.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("native_document_consent_required"));
            return request(requestId, sourcePackage);
        }
        void cancel(String requestId);
    }
    private static final class Entry {
        final String id, pkg, ownerToken;
        final long created, timeout;
        long catalogGeneration;
        final CompletableFuture<AppRelayTasks.Result> result = new CompletableFuture<>();
        Entry(String id, String pkg, String ownerToken, long created, long timeout) {
            this.id = id; this.pkg = pkg; this.ownerToken = ownerToken; this.created = created; this.timeout = timeout;
        }
    }
    private final Native source;
    private final BooleanSupplier current;
    private final LongSupplier clock;
    private final Map<String, AppRelayCatalog.Candidate> catalog = new HashMap<>();
    private final Map<String, Long> catalogGenerations = new HashMap<>();
    private final Map<String, Entry> entries = new HashMap<>();
    // Native SDK responses have no gateway request ID. A timed-out/cancelled package cannot be
    // rebound in the same session, otherwise its late response could launch a later request.
    private final Map<String, Entry> reserved = new HashMap<>();
    private boolean closed;
    AppRelaySync(Native source, BooleanSupplier current, LongSupplier clock) {
        this.source = java.util.Objects.requireNonNull(source); this.current = current; this.clock = clock;
    }
    synchronized void observe(AppRelayCatalog.Update update) {
        if (closed || !current.getAsBoolean()) throw new IllegalStateException("stale_session");
        if (update.clearAll()) catalog.clear();
        for (var candidate : update.candidates()) {
            if (candidate.eligible(true, true)) {
                if (!catalogGenerations.containsKey(candidate.packageName()) && catalogGenerations.size() >= 256)
                    throw new IllegalStateException("relay_busy");
                if (!catalog.containsKey(candidate.packageName()) && catalog.size() >= 16)
                    throw new IllegalStateException("relay_busy");
                catalog.put(candidate.packageName(), candidate);
                catalogGenerations.merge(candidate.packageName(), 1L, Long::sum);
            } else catalog.remove(candidate.packageName());
        }
    }
    synchronized CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) {
        return request(id, pkg, "");
    }
    synchronized CompletableFuture<AppRelayTasks.Result> request(String id, String pkg, String ownerToken) {
        validate(id, pkg); expire();
        validateOwnerToken(ownerToken);
        if (closed || !current.getAsBoolean()) return CompletableFuture.completedFuture(failed(id, "session_revoked"));
        Entry old = entries.get(id);
        if (old != null) return (old.pkg.isEmpty() || (old.pkg.equals(pkg) && old.ownerToken.equals(ownerToken))) ? old.result.copy()
                : CompletableFuture.completedFuture(failed(id, "request_conflict"));
        if (entries.size() >= 128) return CompletableFuture.completedFuture(failed(id, "relay_busy"));
        Entry entry = new Entry(id, pkg, ownerToken, clock.getAsLong(), ownerToken.isEmpty() ? 15000 : 30 * 60 * 1000);
        entries.put(id, entry);
        if (!catalog.containsKey(pkg)) entry.result.complete(failed(id, "native_source_unavailable"));
        else if ((catalog.get(pkg).kind() == AppRelayCatalog.Kind.DOCUMENT) != !ownerToken.isEmpty())
            entry.result.complete(failed(id, "native_document_consent_required"));
        else if (!reserve(entry)) entry.result.complete(failed(id, "native_source_already_requested"));
        else {
            try {
                // Native producers can hold their own SDK lock while completing. Never acquire
                // this registry's lock inline there: expiry/cancel calls the producer in reverse.
                source.request(id, pkg, ownerToken).whenCompleteAsync((result, error) -> finish(entry, result, error));
            } catch (RuntimeException error) { finish(entry, null, error); }
        }
        return entry.result.copy();
    }
    private boolean reserve(Entry entry) {
        Entry previous = reserved.get(entry.pkg);
        long generation = catalogGenerations.getOrDefault(entry.pkg, 0L);
        if (previous != null) {
            AppRelayTasks.Result completed = previous.result.getNow(null);
            // A fresh source-side OEM publication after the original native result was consumed
            // permits another normal handoff. Timeout/cancel/failure keep their tombstones.
            if (completed == null || completed.state() != AppRelayTasks.State.COMPLETED || generation <= previous.catalogGeneration) return false;
        }
        entry.catalogGeneration = generation; reserved.put(entry.pkg, entry); return true;
    }
    private synchronized void finish(Entry entry, AppRelayTasks.Result result, Throwable error) {
        if (entry.result.isDone()) return;
        if (closed || !current.getAsBoolean()) { entry.result.complete(failed(entry.id, "session_revoked")); return; }
        if (error != null || result == null || !entry.id.equals(result.requestId())) {
            entry.result.complete(failed(entry.id, "native_source_failed")); return;
        }
        // Only a real terminal remote launch/observation is accepted; neither callback delivery
        // nor an OEM publish Boolean can complete a sync successfully.
        if (result.state() == AppRelayTasks.State.COMPLETED
                && (result.taskId() < 0 || !"foreground_observed".equals(result.code()) || result.targetPackage().isEmpty()))
            result = failed(entry.id, "invalid_remote_receipt");
        else if (result.state() == AppRelayTasks.State.PREPARED || result.state() == AppRelayTasks.State.LAUNCHING)
            result = failed(entry.id, "invalid_remote_receipt");
        if (result.state() == AppRelayTasks.State.COMPLETED) entry.catalogGeneration = catalogGenerations.getOrDefault(entry.pkg, 0L);
        entry.result.complete(result);
    }
    synchronized boolean contains(String id) { return entries.containsKey(id); }
    synchronized AppRelayTasks.Result status(String id) {
        expire();
        Entry entry = entries.get(id);
        if (entry == null) return failed(id, "unknown_request");
        return entry.result.getNow(new AppRelayTasks.Result(id, AppRelayTasks.State.LAUNCHING, "native_source_requested", entry.pkg, -1));
    }
    synchronized CompletableFuture<AppRelayTasks.Result> cancel(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            // cancel-before-sync is terminal, retaining the request identity instead of reviving it.
            if (entries.size() >= 128) return CompletableFuture.completedFuture(failed(id, "relay_busy"));
            entry = new Entry(id, "", "", clock.getAsLong(), 15000); entries.put(id, entry);
            entry.result.complete(new AppRelayTasks.Result(id, AppRelayTasks.State.CANCELLED, "cancelled_before_launch", "", -1));
        } else if (!entry.result.isDone()) {
            try { source.cancel(id); } catch (RuntimeException failure) { entry.result.complete(failed(id, "native_source_failed")); }
        }
        // After native data has been dispatched, the source adapter must consume the actual
        // sender cancellation/launch result. Do not fabricate a cancelled terminal state here.
        return entry.result.copy();
    }
    synchronized void expire() {
        for (Entry entry : entries.values()) if (!entry.result.isDone() && clock.getAsLong() - entry.created >= entry.timeout) {
            entry.result.complete(failed(entry.id, "request_timeout"));
            try { source.cancel(entry.id); } catch (RuntimeException ignored) { /* Timeout remains terminal. */ }
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; catalog.clear();
        for (Entry entry : entries.values()) if (!entry.result.isDone()) {
            entry.result.complete(failed(entry.id, "session_revoked"));
            try { source.cancel(entry.id); } catch (RuntimeException ignored) { /* Revocation remains terminal. */ }
        }
    }
    static void validate(String id, String pkg) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("invalid_relay_action");
        AppRelayRequest.packageName(pkg);
    }
    static void validateOwnerToken(String token) {
        if (token == null || (!token.isEmpty() && !token.matches("[0-9a-f]{32}"))) throw new IllegalArgumentException("invalid_native_owner_token");
    }
    private static AppRelayTasks.Result failed(String id, String code) {
        return new AppRelayTasks.Result(id, AppRelayTasks.State.FAILED, code, "", -1);
    }
}
