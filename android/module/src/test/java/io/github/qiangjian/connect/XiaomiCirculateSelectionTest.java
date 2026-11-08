package io.github.qiangjian.connect;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class XiaomiCirculateSelectionTest {
    @Test public void unregisteredPrefixNeverTakesOwnershipOfNativeDevices(){
        var result=XiaomiCirculateSelection.plan(List.of("local"),List.of("qj:unregistered","native"),"bound-peer"::equals);
        assertFalse(result.intercepted());assertEquals(List.of("qj:unregistered","native"),result.nativeTargets());
    }
    @Test public void mixedDestinationsPreserveNativeOrderingAndRejectOnlyBoundPeer(){
        var result=XiaomiCirculateSelection.plan(List.of("local"),List.of("first","bound-peer","last"),"bound-peer"::equals);
        assertTrue(result.intercepted());assertEquals(List.of("first","last"),result.nativeTargets());
        assertEquals(List.of("bound-peer"),result.rejected());
    }
    @Test public void unsupportedSourceCannotAccidentallyStartNativeDestination(){
        var result=XiaomiCirculateSelection.plan(List.of("bound-peer"),List.of("native-tv"),"bound-peer"::equals);
        assertTrue(result.intercepted());assertTrue(result.nativeTargets().isEmpty());
        assertEquals(List.of("native-tv"),result.rejected());
    }
    @Test public void sourceOnlyOperationStillGetsFailureAndOrdinarySourceIsUntouched(){
        var rejected=XiaomiCirculateSelection.plan(List.of("bound-peer"),List.<String>of(),"bound-peer"::equals);
        assertTrue(rejected.intercepted());assertEquals(List.of("bound-peer"),rejected.rejected());
        assertFalse(XiaomiCirculateSelection.plan(List.of("native-phone"),List.of("native-tv"),"bound-peer"::equals).intercepted());
    }
}
