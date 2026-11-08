package io.github.qiangjian.connect;

import java.util.function.BooleanSupplier;

/** Per-session directory delivery. Native directory receipts never denote an application launch. */
final class AppRelayDirectory implements AutoCloseable {
    interface Consumer {
        void apply(AppRelayCatalog.Update update) throws Exception;
        void clear() throws Exception;
    }
    private final Consumer consumer;
    private final BooleanSupplier current;
    private long revision;
    private AppRelayCatalog.Update previous;
    private boolean closed;
    AppRelayDirectory(Consumer consumer, BooleanSupplier current) { this.consumer = consumer; this.current = current; }
    synchronized boolean apply(long next, AppRelayCatalog.Update update) throws Exception {
        if (closed || !current.getAsBoolean()) throw new IllegalStateException("stale_session");
        if (next < 1 || next < revision || (next == revision && !update.equals(previous)))
            throw new IllegalArgumentException("stale_relay_catalog");
        if (next == revision) return true;
        consumer.apply(update);
        if (!current.getAsBoolean()) { consumer.clear(); throw new IllegalStateException("stale_session"); }
        revision = next; previous = update; return false;
    }
    @Override public synchronized void close() throws Exception { closed = true; consumer.clear(); }
}
