package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenCastGestureTest {
    @Test public void dragPreservesMovesAndRelease() {
        var gesture = new ScreenCastGesture();
        assertTrue(gesture.accept(0));
        for (int i = 0; i < 120; i++) assertTrue(gesture.accept(2));
        assertTrue(gesture.accept(1));
        assertFalse(gesture.accept(2));
    }
    @Test public void multiTouchCancellationAllowsNextGesture() {
        var gesture = new ScreenCastGesture();
        assertTrue(gesture.accept(0));
        assertTrue(gesture.accept(3));
        assertFalse(gesture.accept(3));
        assertFalse(gesture.accept(2));
        assertFalse(gesture.accept(1));
        assertTrue(gesture.accept(0));
        assertTrue(gesture.accept(1));
    }
    @Test public void hoverAndOldReleaseCannotBreakNewStream() {
        var gesture = new ScreenCastGesture();
        assertFalse(gesture.accept(2));
        assertFalse(gesture.accept(3));
        assertTrue(gesture.accept(0));
        gesture.reset();
        assertFalse(gesture.accept(1));
        assertTrue(gesture.accept(0));
        assertFalse(gesture.accept(0));
        assertTrue(gesture.accept(1));
    }
}
