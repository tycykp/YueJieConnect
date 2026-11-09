package io.github.yuejie.connect;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static io.github.yuejie.connect.RemoteInputDesktopSession.*;

/** Executes only in an already privileged OEM input process, on its serial input worker.
 * The OS targetUid overload is mandatory: no fallback to unrestricted two-argument injection. */
public final class RemoteInputDesktopTarget implements RemoteInputDesktopSession.Target {
    private final Context context;
    private final Focus expected;
    private final Supplier<Focus> nativeFocus;
    private final BooleanSupplier inputAllowed;
    private final Object input;
    private final DisplayManager displays;
    private final Method inject, setDisplay, setActionButton;

    public RemoteInputDesktopTarget(Context context, Focus expected, Supplier<Focus> currentNativeFocus,
                                    BooleanSupplier currentSessionAndInputAllowed) throws ReflectiveOperationException {
        this.context = Objects.requireNonNull(context); this.expected = Objects.requireNonNull(expected);
        nativeFocus = Objects.requireNonNull(currentNativeFocus);
        inputAllowed = Objects.requireNonNull(currentSessionAndInputAllowed);
        input = context.getSystemService(Context.INPUT_SERVICE);
        displays = context.getSystemService(DisplayManager.class);
        if (input == null || displays == null) throw new IllegalStateException("native_input_service_missing");
        inject = input.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("desktop_input_requires_worker");
    }
    private boolean permission() {
        return context.checkSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED;
    }
    private boolean originalFocus() {
        try { return expected.equals(nativeFocus.get()) && displays.getDisplay(expected.displayId()) != null; }
        catch (RuntimeException error) { return false; }
    }
    @Override public boolean hasFocus(long epoch) {
        requireWorker();
        try { return epoch == expected.epoch() && permission() && originalFocus() && inputAllowed.getAsBoolean(); }
        catch (RuntimeException error) { return false; }
    }
    private boolean dispatch(InputEvent event, boolean cleanup) {
        try {
            if (!permission() || !originalFocus() || !cleanup && !inputAllowed.getAsBoolean()) return false;
            setDisplay.invoke(event, expected.displayId());
            // WAIT_FOR_RESULT + exact OEM target UID. This reports native dispatch, not visible application result.
            return Boolean.TRUE.equals(inject.invoke(input, event, 1, expected.targetUid()));
        } catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    @Override public boolean mouse(Mouse event) {
        requireWorker(); return hasFocus(expected.epoch()) && sendMouse(event, false);
    }
    private boolean sendMouse(Mouse event, boolean cleanup) {
        if (event == null || event.action() == null || !Float.isFinite(event.x()) || !Float.isFinite(event.y())
                || (event.metaState() & ~RemoteInputDesktopSession.META_MASK) != 0
                || event.x() < 0 || event.x() > 1 || event.y() < 0 || event.y() > 1
                || event.downTimeMs() < 0 || event.eventTimeMs() < event.downTimeMs()
                || (event.buttons() & ~7) != 0 || (event.actionButton() != 0 && event.actionButton() != 1
                && event.actionButton() != 2 && event.actionButton() != 4)
                || !Float.isFinite(event.scrollX()) || !Float.isFinite(event.scrollY())
                || Math.abs(event.scrollX()) > 32 || Math.abs(event.scrollY()) > 32
                || cleanup && event.action() != MouseAction.CANCEL) return false;
        int action = switch (event.action()) {
            case DOWN -> MotionEvent.ACTION_DOWN; case UP -> MotionEvent.ACTION_UP;
            case MOVE -> MotionEvent.ACTION_MOVE; case HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE;
            case BUTTON_PRESS -> MotionEvent.ACTION_BUTTON_PRESS; case BUTTON_RELEASE -> MotionEvent.ACTION_BUTTON_RELEASE;
            case SCROLL -> MotionEvent.ACTION_SCROLL; case CANCEL -> MotionEvent.ACTION_CANCEL;
        };
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = event.x() * (expected.width() - 1); coords.y = event.y() * (expected.height() - 1);
        coords.pressure = event.buttons() == 0 ? 0 : 1; coords.size = 1;
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, event.scrollX());
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, event.scrollY());
        MotionEvent nativeEvent = MotionEvent.obtain(event.downTimeMs(), event.eventTimeMs(), action, 1,
                new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{coords},
                event.metaState(), event.buttons(), 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_MOUSE, 0);
        try { setActionButton.invoke(nativeEvent, event.actionButton()); return dispatch(nativeEvent, cleanup); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
        finally { nativeEvent.recycle(); }
    }
    @Override public boolean key(Key event) {
        requireWorker(); return hasFocus(expected.epoch()) && sendKey(event, false);
    }
    private boolean sendKey(Key event, boolean cleanup) {
        if (event == null || event.keyCode() < 1 || event.keyCode() > KeyEvent.getMaxKeyCode()
                || (event.metaState() & ~RemoteInputDesktopSession.META_MASK) != 0
                || event.repeatCount() < 0 || event.repeatCount() > 10_000 || event.downTimeMs() < 0
                || event.eventTimeMs() < event.downTimeMs() || event.cancelled() && event.down()
                || cleanup && (!event.cancelled() || event.down())) return false;
        KeyEvent nativeEvent = new KeyEvent(event.downTimeMs(), event.eventTimeMs(),
                event.down() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, event.keyCode(),
                event.repeatCount(), event.metaState(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                event.cancelled() ? KeyEvent.FLAG_CANCELED : 0, InputDevice.SOURCE_KEYBOARD);
        return dispatch(nativeEvent, cleanup);
    }
    @Override public ReleaseResult release(Reset reset) {
        requireWorker();
        if (reset == null || reset.keys().size() > MAX_HELD_KEYS) return ReleaseResult.NATIVE_REJECTED;
        // Ignore a revoked business grant only for releasing owned presses; never ignore focus or UID.
        if (!originalFocus()) return ReleaseResult.FOCUS_CHANGED;
        if (!permission()) return ReleaseResult.NATIVE_REJECTED;
        if (reset.mouse() != null && (!originalFocus() || !sendMouse(reset.mouse(), true))) return ReleaseResult.NATIVE_REJECTED;
        for (Key key : reset.keys()) {
            if (!originalFocus()) return ReleaseResult.FOCUS_CHANGED;
            if (!key.cancelled() || key.down() || !sendKey(key, true)) return ReleaseResult.NATIVE_REJECTED;
        }
        return ReleaseResult.RELEASED;
    }
}
