package io.github.yuejie.connect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferEndpointTest {
    final FileTransferEngine.Key key = new FileTransferEngine.Key("synthetic-peer", "a".repeat(64), "b".repeat(32));
    final FileTransferEngine.Offer offer = new FileTransferEngine.Offer("synthetic-empty.bin", "application/octet-stream", 0,
            FileTransferEngine.hex(FileTransferEngine.sha256().digest(new byte[0])));
    final AtomicBoolean active = new AtomicBoolean(true);
    final AtomicLong clock = new AtomicLong(1);
    final AtomicInteger completions = new AtomicInteger();
    File root;
    FileTransferEndpoint endpoint;
    CompletableFuture<Void> nativeResult;
    FileTransferEngine.Authorization authorization;
    int published;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("yj-file-endpoint-").toFile();
        endpoint = new FileTransferEndpoint(root, k -> active.get(), clock::get);
        nativeResult = new CompletableFuture<>();
        authorization = endpoint.authorizeTracked(key, offer, new FileTransferEngine.Sink() {
            public void publishVerified(File staged, FileTransferEngine.Offer actual, FileTransferEngine.SessionGate gate,
                    FileTransferEngine.Key key) { published++; }
            public void rollback() { }
        }, nativeResult).get(3, TimeUnit.SECONDS);
        request("file_offer", FileTransferProtocol.offerPayload(key, offer), "1").get(3, TimeUnit.SECONDS);
        completions.set(0);
    }
    @After public void cleanup() throws Exception {
        endpoint.close();
        assertEquals(0, root.list().length); Files.delete(root.toPath());
    }
    CompletableFuture<JSONObject> request(String operation, JSONObject payload, String id) throws Exception {
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        endpoint.execute(new JSONObject().put("operation", operation).put("payload", payload)
                .put("session_id", key.session()).put("request_id", id).put("projection_id", key.peer())
                .put("deadline_elapsed_ms", clock.get() + 15000), new FileTransferEndpoint.Completion() {
            public void success(JSONObject data) { completions.incrementAndGet(); response.complete(data); }
            public void fail(String code) { completions.incrementAndGet(); response.completeExceptionally(new IOException(code)); }
        });
        return response;
    }
    CompletableFuture<JSONObject> commit() throws Exception {
        CompletableFuture<JSONObject> response = request("file_commit", FileTransferProtocol.identity(key), "2");
        authorization.committed().toCompletableFuture().get(3, TimeUnit.SECONDS);
        // A succeeding later task proves the commit worker returned while the native result waits.
        request("file_offer", FileTransferProtocol.offerPayload(key, offer), "3").get(3, TimeUnit.SECONDS);
        return response;
    }
    void fails(String expected, CompletableFuture<?> future) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); fail(expected); }
        catch (java.util.concurrent.ExecutionException error) { assertEquals(expected, error.getCause().getMessage()); }
    }
    @Test public void engineReadyUnblocksConsumerWhileWireCommitWaitsForActualNativeResult() throws Exception {
        CompletableFuture<JSONObject> response = commit();
        assertEquals(1, published); assertFalse(response.isDone());
        nativeResult.complete(null);
        assertTrue(response.get(3, TimeUnit.SECONDS).getBoolean("committed"));
        assertEquals(2, completions.get());
    }
    @Test public void failedNativeResultAndFreshCommitRetryCannotBecomeSuccess() throws Exception {
        CompletableFuture<JSONObject> response = commit(); nativeResult.completeExceptionally(new IOException("synthetic_failure"));
        fails("native_file_receive_failed", response);
        fails("native_file_receive_failed", request("file_commit", FileTransferProtocol.identity(key), "4"));
        assertEquals(1, published);
    }
    @Test public void cancellationAfterCommitCannotInventRollbackOfNativeConsumption() throws Exception {
        CompletableFuture<JSONObject> response = commit(); endpoint.cancel(key.session(), "2");
        endpoint.sessionChanged();
        request("file_offer", FileTransferProtocol.offerPayload(key, offer), "4").get(3, TimeUnit.SECONDS);
        assertFalse(response.isDone());
        nativeResult.complete(null);
        assertTrue(response.get(3, TimeUnit.SECONDS).getBoolean("committed"));
        assertEquals(3, completions.get());
    }
    @Test public void deadlineWinsAgainstLateNativeCallback() throws Exception {
        CompletableFuture<JSONObject> response = commit(); clock.addAndGet(15001); endpoint.sessionChanged();
        fails("request_timeout", response); nativeResult.complete(null);
        assertEquals(2, completions.get());
    }
    @Test public void sessionLossCannotAcknowledgeACommittedOldSession() throws Exception {
        CompletableFuture<JSONObject> response = commit(); active.set(false); endpoint.sessionChanged();
        fails("stale_session", response); nativeResult.complete(null);
        assertEquals(2, completions.get());
    }
    @Test public void closeTerminatesPendingCommitWithoutNativeSuccess() throws Exception {
        CompletableFuture<JSONObject> response = commit(); endpoint.close();
        fails("stale_session", response); nativeResult.complete(null);
        assertEquals(2, completions.get());
    }
    @Test public void revokedPendingNativeResultCannotSucceedOnCommitRetry() throws Exception {
        CompletableFuture<JSONObject> response = commit(); endpoint.revoke(key).get(3, TimeUnit.SECONDS);
        fails("native_file_receive_failed", response);
        nativeResult.complete(null);
        fails("native_file_receive_failed", request("file_commit", FileTransferProtocol.identity(key), "4"));
        assertEquals(1, published);
    }
}
