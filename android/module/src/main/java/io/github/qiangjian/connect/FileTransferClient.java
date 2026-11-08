package io.github.qiangjian.connect;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Stop-and-wait file sender; executor must be independent from bridge dispatch/poll workers. */
public final class FileTransferClient {
    // Android JSONStringer escapes '/' in Base64. 2048 bytes leave authenticated-envelope
    // headroom even for all-0xff input; the receive codec keeps its compatible 3072-byte limit.
    public static final int SEND_CHUNK_BYTES = 2048;
    public interface Transport {
        boolean current(FileTransferEngine.Key key);
        CompletableFuture<JSONObject> request(FileTransferEngine.Key key, String operation, JSONObject payload);
    }
    private final Transport transport;
    private final Executor worker;
    private final Semaphore slots = new Semaphore(FileTransferEngine.MAX_TRANSFERS);

    public FileTransferClient(Transport transport, Executor worker) { this.transport = transport; this.worker = worker; }

    /** Takes ownership of source. Invoke only after matching receiver consent has been obtained. */
    public CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key, FileTransferSource source) {
        return send(key, source, ignored -> true);
    }

    /** Adds native owner/channel validity to the transport's authenticated-session checks. */
    public CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key, FileTransferSource source,
                                                              FileTransferEngine.SessionGate nativeRoute) {
        CompletableFuture<FileTransferEngine.Receipt> result = new CompletableFuture<>();
        AtomicBoolean cancel = new AtomicBoolean();
        result.whenComplete((receipt, error) -> { if (result.isCancelled()) cancel.set(true); });
        if (!slots.tryAcquire()) {
            try { source.close(); } catch (IOException ignored) { }
            result.completeExceptionally(new IOException("file_busy"));
            return result;
        }
        try {
            worker.execute(() -> {
                boolean committed = false;
                try (FileTransferSource owned = source; InputStream input = owned.open()) {
                    FileTransferEngine.Offer offer = owned.offer();
                    JSONObject start = rpc(key, "file_offer", FileTransferProtocol.offerPayload(key, offer), cancel, nativeRoute);
                    if (FileTransferProtocol.integer(start, "offset") != 0) throw new IOException("unexpected_start_offset");
                    byte[] buffer = new byte[SEND_CHUNK_BYTES];
                    long offset = 0;
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (n == 0) throw new IOException("source_stalled");
                        JSONObject block = FileTransferProtocol.identity(key).put("offset", offset)
                                .put("data", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, n)));
                        JSONObject ack = rpc(key, "file_chunk", block, cancel, nativeRoute);
                        offset += n;
                        if (FileTransferProtocol.integer(ack, "offset") != offset) throw new IOException("invalid_chunk_ack");
                    }
                    if (offset != offer.size()) throw new IOException("source_size_mismatch");
                    JSONObject ack = rpc(key, "file_commit", FileTransferProtocol.identity(key), cancel, nativeRoute);
                    if (!Boolean.TRUE.equals(ack.get("committed")) || FileTransferProtocol.integer(ack, "size") != offer.size()
                            || !offer.sha256().equals(FileTransferProtocol.string(ack, "sha256")))
                        throw new IOException("invalid_commit_ack");
                    committed = true;
                    result.complete(new FileTransferEngine.Receipt(key, offer.size(), offer.sha256()));
                } catch (Exception error) { result.completeExceptionally(error); }
                finally {
                    if (!committed && transport.current(key)) {
                        try { transport.request(key, "file_cancel", FileTransferProtocol.identity(key)).get(5, TimeUnit.SECONDS); }
                        catch (Exception ignored) { /* Remote TTL/session cleanup remains mandatory. */ }
                    }
                    slots.release();
                }
            });
        } catch (RuntimeException rejected) {
            slots.release();
            try { source.close(); } catch (IOException cleanup) { rejected.addSuppressed(cleanup); }
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private JSONObject rpc(FileTransferEngine.Key key, String operation, JSONObject payload, AtomicBoolean cancel,
                           FileTransferEngine.SessionGate nativeRoute) throws Exception {
        if (cancel.get()) throw new IOException("file_cancelled");
        if (!transport.current(key) || !nativeRoute.current(key)) throw new IOException("stale_session");
        CompletableFuture<JSONObject> request = transport.request(key, operation, payload);
        JSONObject response;
        try { response = request.get(operation.equals("file_commit") ? 18 : 15, TimeUnit.SECONDS); }
        catch (Exception error) { request.cancel(true); throw error; }
        if (cancel.get()) throw new IOException("file_cancelled");
        if (!transport.current(key) || !nativeRoute.current(key)) throw new IOException("stale_session");
        if (response == null || !key.transfer().equals(FileTransferProtocol.string(response, "transfer")))
            throw new IOException("invalid_transfer_ack");
        return response;
    }
}
