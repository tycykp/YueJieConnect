package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferNativeResultTest {
    @Test public void descriptorAloneDoesNotInventNativeResult() {
        FileTransferNativeResult result = new FileTransferNativeResult();
        result.descriptorOpened(); assertFalse(result.completion().isDone());
        result.nativeResult(true); result.completion().join();
    }
    @Test public void nativeResultAloneDoesNotInventFileConsumption() {
        FileTransferNativeResult result = new FileTransferNativeResult();
        result.nativeResult(true); assertFalse(result.completion().isDone());
        result.descriptorOpened(); result.completion().join();
    }
    @Test public void failureIsTerminalForLateOpenAndLateNativeSuccess() {
        FileTransferNativeResult result = new FileTransferNativeResult();
        result.nativeResult(false); result.descriptorOpened(); result.nativeResult(true);
        assertTrue(result.completion().isCompletedExceptionally());
    }
    @Test public void observerCancellationCannotForgeOrCancelNativeResult() {
        FileTransferNativeResult result = new FileTransferNativeResult();
        result.completion().complete(null); result.completion().cancel(true);
        assertFalse(result.completion().isDone());
        result.descriptorOpened(); result.nativeResult(true); result.completion().join();
    }
    @Test public void realSuccessRemainsTerminalAfterOwnerReleasesResource() {
        FileTransferNativeResult result = new FileTransferNativeResult();
        result.descriptorOpened(); result.nativeResult(true); result.fail();
        result.completion().join();
    }
}
