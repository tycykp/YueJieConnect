package io.github.qiangjian.connect;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Local receive evidence: an actual descriptor handoff AND the unchanged OEM result callback.
 * Neither event alone claims delivery. Cancellation is terminal, including late native callbacks. */
final class FileTransferNativeResult {
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private boolean opened, nativeSucceeded;
    synchronized void descriptorOpened() { opened = true; finish(); }
    synchronized void nativeResult(boolean success) {
        if (!success) fail(); else { nativeSucceeded = true; finish(); }
    }
    synchronized void fail() { result.completeExceptionally(new IOException("native_file_receive_failed")); }
    synchronized CompletableFuture<Void> completion() { return result.copy(); }
    private void finish() { if (opened && nativeSucceeded) result.complete(null); }
}
