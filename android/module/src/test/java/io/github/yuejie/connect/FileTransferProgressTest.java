package io.github.yuejie.connect;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FileTransferProgressTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private void transfer(boolean badChunk, boolean badCommit) throws Exception {
        var key = FileTransferEngine.Key.create("pad", "b".repeat(64));
        var source = FileTransferSource.snapshot(temp.newFolder(), new ByteArrayInputStream(new byte[5000]),
                "test.bin", "application/octet-stream", 5000);
        List<Long> progress = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        var client = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key value) { return true; }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key value, String op, JSONObject payload) {
                calls.add(op);
                try {
                    JSONObject reply = FileTransferProtocol.identity(value);
                    if (op.equals("file_offer")) reply.put("offset", 0);
                    if (op.equals("file_chunk")) reply.put("offset", badChunk ? -1 : payload.getLong("offset")
                            + Base64.getDecoder().decode(payload.getString("data")).length);
                    if (op.equals("file_commit")) {
                        if (badCommit) return CompletableFuture.failedFuture(new IOException("commit_failed"));
                        reply.put("committed", true).put("size", source.offer().size()).put("sha256", source.offer().sha256());
                    }
                    return CompletableFuture.completedFuture(reply);
                } catch (Exception error) { return CompletableFuture.failedFuture(error); }
            }
        }, Runnable::run);
        var sent = client.send(key, source, ignored -> true, bytes -> {
            assertFalse("bytes are progress, not a successful commit", calls.contains("file_commit"));
            progress.add(bytes);
        });
        if (badChunk || badCommit) {
            try { sent.get(); fail("must not report success"); } catch (ExecutionException expected) { }
            assertEquals("file_cancel", calls.get(calls.size() - 1));
        } else assertEquals(5000, sent.get().size());
        assertEquals(badChunk ? List.of() : List.of(2048L, 4096L, 5000L), progress);
    }

    @Test public void progressUsesAcknowledgedBytes() throws Exception { transfer(false, false); }
    @Test public void invalidAcknowledgementNeverAdvancesProgress() throws Exception { transfer(true, false); }
    @Test public void allBytesAcknowledgedStillRequiresSuccessfulCommit() throws Exception { transfer(false, true); }
}
