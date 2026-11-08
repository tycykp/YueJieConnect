package io.github.qiangjian.connect;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferProtocolTest {
    private File root;
    private FileTransferEngine engine;
    private FileTransferProtocol protocol;
    private final FileTransferEngine.Key key = FileTransferEngine.Key.create("synthetic-peer", "a".repeat(64));
    private final AtomicBoolean active = new AtomicBoolean(true);
    private byte[] published;
    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("qj-file-rpc-").toFile();
        engine = new FileTransferEngine(root, k -> active.get(), System::currentTimeMillis);
        protocol = new FileTransferProtocol(engine);
    }
    @After public void cleanup() throws Exception { engine.close(); assertEquals(0, root.list().length); Files.delete(root.toPath()); }
    private FileTransferEngine.Sink sink() {
        return new FileTransferEngine.Sink() {
            public void publishVerified(File f, FileTransferEngine.Offer o, FileTransferEngine.SessionGate g, FileTransferEngine.Key k) throws IOException { published = Files.readAllBytes(f.toPath()); }
            public void rollback() { published = null; }
        };
    }
    private JSONObject call(String operation, JSONObject payload) throws Exception { return protocol.execute(key.peer(), key.session(), operation, payload); }
    private void invalid(String operation, JSONObject payload) throws Exception {
        try { call(operation, payload); fail("Must reject malformed payload"); } catch (IOException expected) { }
    }

    @Test public void clientExecutesReceiverAndRequiresVerifiedFinalAck() throws Exception {
        byte[] bytes = new byte[8193]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        FileTransferSource source = FileTransferSource.snapshot(root, new ByteArrayInputStream(bytes), "test.bin", "application/octet-stream", bytes.length);
        engine.authorize(key, source.offer(), sink()); AtomicInteger calls = new AtomicInteger();
        FileTransferClient client = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key k) { return active.get(); }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key k, String op, JSONObject payload) {
                calls.incrementAndGet();
                try { return CompletableFuture.completedFuture(protocol.execute(k.peer(), k.session(), op, new JSONObject(payload.toString()))); }
                catch (Exception error) { return CompletableFuture.failedFuture(error); }
            }
        }, Runnable::run);
        var receipt = client.send(key, source).get(2, TimeUnit.SECONDS);
        assertArrayEquals(bytes, published); assertEquals(bytes.length, receipt.size()); assertEquals(7, calls.get());
    }

    @Test public void badOffsetAckAbortsRemoteStaging() throws Exception {
        FileTransferSource source = FileTransferSource.snapshot(root, new ByteArrayInputStream(new byte[]{1}), "test.bin", "application/octet-stream", 1);
        engine.authorize(key, source.offer(), sink());
        FileTransferClient client = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key k) { return true; }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key k, String op, JSONObject payload) {
                try {
                    JSONObject response = protocol.execute(k.peer(), k.session(), op, payload);
                    if (op.equals("file_chunk")) response.put("offset", 99);
                    return CompletableFuture.completedFuture(response);
                } catch (Exception error) { return CompletableFuture.failedFuture(error); }
            }
        }, Runnable::run);
        try { client.send(key, source).get(); fail(); } catch (java.util.concurrent.ExecutionException expected) { }
        assertNull(published); assertEquals(0, root.list().length);
    }

    @Test public void strictFieldsTypesAndCanonicalBase64() throws Exception {
        String hash = FileTransferEngine.hex(FileTransferEngine.sha256().digest(new byte[]{1}));
        var offer = new FileTransferEngine.Offer("test.bin", "application/octet-stream", 1, hash);
        engine.authorize(key, offer, sink());
        JSONObject valid = FileTransferProtocol.offerPayload(key, offer);
        invalid("file_offer", new JSONObject(valid.toString()).put("uri", "content://private/1"));
        invalid("file_offer", new JSONObject(valid.toString()).put("size", "1"));
        invalid("file_offer", new JSONObject(valid.toString()).put("size", 1.0));
        call("file_offer", valid);
        invalid("file_chunk", FileTransferProtocol.identity(key).put("offset", 0).put("data", "AQ"));
        invalid("file_chunk", FileTransferProtocol.identity(key).put("offset", -1).put("data", "AQ=="));
        invalid("file_chunk", FileTransferProtocol.identity(key).put("offset", 0).put("data", "AR=="));
        invalid("file_chunk", FileTransferProtocol.identity(key).put("offset", 0).put("data", Base64.getEncoder().encodeToString(new byte[3073])));
        call("file_chunk", FileTransferProtocol.identity(key).put("offset", 0).put("data", "AQ=="));
        assertTrue(call("file_commit", FileTransferProtocol.identity(key)).getBoolean("committed"));
    }

    @Test public void maximumEscapedChunkAndAuthenticatedEnvelopeFitActualAndroidJsonBudget() throws Exception {
        byte[] worst = new byte[FileTransferClient.SEND_CHUNK_BYTES]; java.util.Arrays.fill(worst,(byte)0xff);
        String encoded = Base64.getEncoder().encodeToString(worst);
        JSONObject payload = FileTransferProtocol.identity(key).put("offset", FileTransferEngine.MAX_BYTES - worst.length).put("data", encoded);
        JSONObject envelope = envelope(payload);
        String serialized = envelope.toString();
        assertTrue("Actual Android JSONStringer slash escaping is required",serialized.contains("\\/"));
        int actual=serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("all-ff client chunk with longest actual source fields",actual<=8192);
        JSONObject task = new JSONObject().put("projection_id","p".repeat(256)).put("session_id",key.session())
                .put("request_id","18446744073709551615").put("operation","file_chunk").put("payload",payload)
                .put("deadline_elapsed_ms",Long.MAX_VALUE).put("remaining_ms",15000);
        int taskBytes=task.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(taskBytes<=8192);
        byte[] oldWorst=new byte[FileTransferEngine.CHUNK_BYTES];java.util.Arrays.fill(oldWorst,(byte)0xff);
        int incompatible=envelope(FileTransferProtocol.identity(key).put("offset",0).put("data",Base64.getEncoder().encodeToString(oldWorst)))
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("3072 remains a codec limit, not a safe Android sender size",incompatible>8192);
        System.out.println("Android37 all-ff chunk="+worst.length+" source_envelope="+actual+" receiver_task="+taskBytes+" old_3072_envelope="+incompatible);
    }
    private JSONObject envelope(JSONObject payload) throws Exception {
        return new JSONObject().put("op","bridge_submit").put("projection_id","p".repeat(256))
                .put("session_id",key.session()).put("request_id","file:"+key.transfer()+":18446744073709551615")
                .put("operation","file_chunk").put("payload",payload).put("token","a".repeat(64)).put("sequence",Long.MAX_VALUE);
    }
}
