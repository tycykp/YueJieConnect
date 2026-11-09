package io.github.yuejie.connect;

import static org.junit.Assert.*;
import org.junit.Test;

public final class OplusScreenNativeProofTest {
    @Test public void carrierAcceptsOneOpaqueHandleAndRejectsReplacement() {
        var carrier = new OplusScreenLinkerAdapter.ProofCarrier();
        carrier.attach("a".repeat(64));
        assertThrows(IllegalStateException.class, () -> carrier.attach("b".repeat(64)));
        assertEquals("NativeScreenProofCarrier", carrier.toString());
    }
    @Test public void ordinaryStringsAndNonCanonicalHandlesCannotBecomeCarrierProof() {
        for (String invalid : new String[]{null, "", "a".repeat(63), "A".repeat(64), "g".repeat(64), "a".repeat(65)}) {
            var carrier = new OplusScreenLinkerAdapter.ProofCarrier();
            assertThrows(IllegalStateException.class, () -> carrier.attach(invalid));
            carrier.attach("c".repeat(64)); // Failed validation does not consume an unrelated real request.
        }
    }
    @Test public void requestDtoKeepsLegacyConstructorAndHidesOpaqueProofInDiagnostics() {
        Object actual = new Object();
        var old = new OplusScreenRequestAdapter.NativeRequest(actual, "synthetic-native", "SYNERGY_PAD", 10001, 1234);
        assertNull(old.requestProof()); assertSame(actual, old.deviceInfo());
        var current = new OplusScreenRequestAdapter.NativeRequest(actual, "synthetic-native", "SYNERGY_PAD", 10001, 1234, "d".repeat(64));
        assertEquals("d".repeat(64), current.requestProof());
        assertEquals("NativeScreenRequest", current.toString());
        assertThrows(IllegalArgumentException.class,
                () -> new OplusScreenRequestAdapter.NativeRequest(actual, "synthetic-native", "SYNERGY_PAD", 10001, 1234, "ordinary-extra"));
        var selection = new OplusScreenLinkerAdapter.Selection(actual, "synthetic-native", "SYNERGY_PAD");
        assertNotNull(selection.proofCarrier()); assertEquals("NativeCastSelection", selection.toString());
    }
}
