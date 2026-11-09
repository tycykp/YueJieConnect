package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ScreenCastNativeConsentTest {
    @Test public void registeringRequestOrPromptIsNotConsent() {
        AtomicLong clock = new AtomicLong(100);
        var consent = new ScreenCastNativeConsent(200, () -> true, clock::get);
        Object request = new Object();
        assertFalse(consent.nativeAllowed(request));
        assertTrue(consent.attach(request));
        assertFalse(consent.approved(request));
        assertFalse(consent.allowed(request));
        assertTrue(consent.prompted(request));
        assertFalse(consent.approved(request));
        assertTrue(consent.allowed(request));
        assertTrue(consent.approved(request));
    }
    @Test public void equalDeviceFieldsCannotReplaceNativeRequestObject() {
        var consent = new ScreenCastNativeConsent(200, () -> true, () -> 100);
        Object first = new String("synthetic-device"), copied = new String("synthetic-device");
        assertTrue(consent.attach(first));
        assertFalse(consent.attach(copied));
        assertFalse(consent.prompted(copied));
        assertFalse(consent.nativeAllowed(copied));
        assertTrue(consent.nativeAllowed(first));
        assertFalse(consent.approved(copied));
    }
    @Test public void existingPermissionStillNeedsOriginalNativeAllowCallback() {
        AtomicLong clock = new AtomicLong(100);
        var consent = new ScreenCastNativeConsent(200, () -> true, clock::get);
        Object request = new Object(); consent.attach(request);
        assertFalse(consent.approved(request)); // An SDK zero return has no state transition.
        assertTrue(consent.nativeAllowed(request));
        clock.set(300); // Deadline limits waiting, not an already active native capture lease.
        assertTrue(consent.approved(request));
        assertFalse(consent.nativeAllowed(request));
    }
    @Test public void lateAllowAndOldLeaseCannotRevive() {
        AtomicLong clock = new AtomicLong(100);
        var consent = new ScreenCastNativeConsent(200, () -> true, clock::get);
        Object request = new Object(); consent.attach(request); consent.prompted(request);
        clock.set(200);
        assertFalse(consent.nativeAllowed(request));
        clock.set(100);
        assertFalse(consent.attach(request));
        assertEquals(ScreenCastNativeConsent.State.CLOSED, consent.state());
    }
    @Test public void revocationAndAuthorityFailureCloseAllowedLease() {
        AtomicBoolean current = new AtomicBoolean(true);
        var consent = new ScreenCastNativeConsent(200, current::get, () -> 100);
        Object request = new Object(); consent.attach(request); consent.nativeAllowed(request);
        current.set(false); assertFalse(consent.approved(request));
        current.set(true); assertFalse(consent.approved(request));
        var failed = new ScreenCastNativeConsent(200, () -> { throw new IllegalStateException(); }, () -> 100);
        assertFalse(failed.attach(request));
        assertEquals(ScreenCastNativeConsent.State.CLOSED, failed.state());
    }
}
