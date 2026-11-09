package io.github.yuejie.connect;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteInputWakeGestureTest {
    private final RemoteInputPointerTarget.WakeGesture gesture =
            new RemoteInputPointerTarget.WakeGesture(8, 60, 300);

    @Test public void onlySecondNearbyTapWakes() {
        assertFalse(tap(1000, 100, false));
        assertTrue(tap(1180, 112, false));
        assertFalse(tap(1300, 112, false));
    }

    @Test public void distantLateAndMixedSourceTapsStartNewPairs() {
        assertFalse(tap(1000, 100, false));
        assertFalse(tap(1150, 200, false));
        assertFalse(tap(1600, 200, false));
        assertFalse(tap(1750, 200, true));
        assertTrue(tap(1900, 200, true));
    }

    @Test public void dragLongPressAndCancelBreakThePair() {
        assertFalse(tap(1000, 100, false));
        assertFalse(touch(ACTION_DOWN, 1100, 100));
        assertFalse(touch(ACTION_MOVE, 1120, 125));
        assertFalse(touch(ACTION_UP, 1140, 100));
        assertFalse(tap(1180, 100, false));
        assertFalse(touch(ACTION_DOWN, 1250, 100));
        assertFalse(touch(ACTION_UP, 1510, 100));
        assertFalse(tap(1550, 100, false));
        assertFalse(touch(ACTION_DOWN, 1640, 100));
        assertFalse(touch(ACTION_CANCEL, 1660, 100));
        assertFalse(tap(1700, 100, false));
    }

    @Test public void finishingAfterExternalWakeDoesNotTriggerOrPrimeWake() {
        assertFalse(tap(1000, 100, false));
        assertFalse(touch(ACTION_DOWN, 1150, 100));
        assertFalse(gesture.accept(ACTION_UP, 1190, 100, 100, false, false));
        assertFalse(tap(1250, 100, false));
    }

    private boolean tap(long time, float x, boolean mouse) {
        assertFalse(gesture.accept(ACTION_DOWN, time, x, 100, mouse, true));
        return gesture.accept(ACTION_UP, time + 40, x, 100, mouse, true);
    }

    private boolean touch(int action, long time, float x) {
        return gesture.accept(action, time, x, 100, false, true);
    }
}
