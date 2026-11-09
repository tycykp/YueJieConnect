package io.github.yuejie.connect;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.ViewConfiguration;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Native-process single-pointer input. Requires existing INJECT_EVENTS and a current approved display lease. */
public final class RemoteInputPointerTarget implements AutoCloseable {
    public enum Result { ACCEPTED, DENIED, STALE, INVALID, NATIVE_REJECTED }
    private final Object manager;
    private final Method inject, setDisplay;
    private final Context context;
    private final int displayId, width, height;
    private final BooleanSupplier currentAndAllowed;
    private final PowerManager power;
    private final WakeGesture wakeGesture;
    private long sequence, downTime;
    private float lastX, lastY;
    private boolean down, closed;
    private boolean mouse;
    private final java.util.Map<Integer,Long> keys=new java.util.HashMap<>();

    public RemoteInputPointerTarget(Context context, int displayId, int width, int height,
                                    BooleanSupplier currentAndAllowed) throws ReflectiveOperationException {
        if (displayId < 0 || width < 1 || height < 1 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("invalid_input_display");
        this.context = context; this.displayId = displayId; this.width = width; this.height = height;
        this.currentAndAllowed = currentAndAllowed;
        power = displayId == Display.DEFAULT_DISPLAY ? context.getSystemService(PowerManager.class) : null;
        ViewConfiguration config = ViewConfiguration.get(context);
        wakeGesture = new WakeGesture(config.getScaledTouchSlop(), config.getScaledDoubleTapSlop(),
                ViewConfiguration.getDoubleTapTimeout());
        manager = context.getSystemService(Context.INPUT_SERVICE);
        if (manager == null) throw new IllegalStateException("native_input_service_missing");
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
    }
    /** Coordinates are normalized to the negotiated unrotated capture dimensions. */
    public synchronized Result pointer(long sequence, int action, float x, float y) {
        return pointer(sequence,action,x,y,false);
    }
    public synchronized Result pointer(long sequence, int action, float x, float y,boolean mouseSource) {
        if (closed || sequence <= this.sequence) return Result.STALE;
        this.sequence = sequence;
        if (!permitted()) { close(); return Result.DENIED; }
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1
                || (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)) return Result.INVALID;
        long now = SystemClock.uptimeMillis();
        float px = x * (width - 1), py = y * (height - 1);
        boolean sleeping = power != null && !power.isInteractive();
        if (sleeping || wakeGesture.held) {
            cancelPointer();
            // Consume both taps here so neither can land on the lock screen as it lights up.
            if (wakeGesture.accept(action, now, px, py, mouseSource, sleeping) && !power.isInteractive()) {
                boolean pressed = sendKey(KeyEvent.KEYCODE_WAKEUP, true, now, now, 0);
                boolean released = sendKey(KeyEvent.KEYCODE_WAKEUP, false, now, SystemClock.uptimeMillis(), 0);
                if (!pressed || !released) return Result.NATIVE_REJECTED;
            }
            return Result.ACCEPTED;
        }
        wakeGesture.reset();
        if (action == MotionEvent.ACTION_DOWN ? down : !down) return Result.INVALID;
        long start = action == MotionEvent.ACTION_DOWN ? now : downTime;
        if(action==MotionEvent.ACTION_DOWN)mouse=mouseSource;
        if (!send(action, start, now, px, py)) return Result.NATIVE_REJECTED;
        down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
        downTime = start; lastX = px; lastY = py; return Result.ACCEPTED;
    }
    private boolean permitted() {
        return currentAndAllowed.getAsBoolean()
                && context.checkSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED;
    }
    public synchronized Result key(long sequence,int code,boolean pressed) {
        if(closed||sequence<=this.sequence)return Result.STALE;
        this.sequence=sequence;
        if(!permitted()){close();return Result.DENIED;}
        if(code<1||code>KeyEvent.getMaxKeyCode()||(!pressed&&!keys.containsKey(code))||keys.size()>=32)return Result.INVALID;
        long now=SystemClock.uptimeMillis(),start=keys.getOrDefault(code,now);
        if(!sendKey(code,pressed,start,now,0))return Result.NATIVE_REJECTED;
        if(pressed)keys.put(code,start);else keys.remove(code);
        return Result.ACCEPTED;
    }
    private boolean sendKey(int code,boolean pressed,long start,long now,int flags) {
        KeyEvent event=new KeyEvent(start,now,pressed?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,code,0,0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_KEYBOARD);
        try{setDisplay.invoke(event,displayId);return Boolean.TRUE.equals(inject.invoke(manager,event,1));}
        catch(ReflectiveOperationException|RuntimeException error){return false;}
    }
    private boolean send(int action, long start, long now, float x, float y) {
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;property.toolType=mouse?MotionEvent.TOOL_TYPE_MOUSE:MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();coordinates.x=x;coordinates.y=y;coordinates.pressure=action==MotionEvent.ACTION_UP?0:1;coordinates.size=1;
        MotionEvent event = MotionEvent.obtain(start,now,action,1,new MotionEvent.PointerProperties[]{property},new MotionEvent.PointerCoords[]{coordinates},0,
            mouse&&action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_CANCEL?MotionEvent.BUTTON_PRIMARY:0,1,1,0,0,mouse?InputDevice.SOURCE_MOUSE:InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            setDisplay.invoke(event, displayId);
            // WAIT_FOR_RESULT: native dispatch result, never success solely from enqueueing.
            return Boolean.TRUE.equals(inject.invoke(manager, event, 1));
        } catch (ReflectiveOperationException | RuntimeException error) { return false; }
        finally { event.recycle(); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        wakeGesture.reset();
        cancelPointer();
        for(var key:keys.entrySet())sendKey(key.getKey(),false,key.getValue(),SystemClock.uptimeMillis(),KeyEvent.FLAG_CANCELED);
        keys.clear();
    }
    private void cancelPointer() {
        if (down) send(MotionEvent.ACTION_CANCEL, downTime, SystemClock.uptimeMillis(), lastX, lastY);
        down = false;
    }

    static final class WakeGesture {
        private static final int MAX_TAP_DURATION_MS = 250;
        private final float touchSlop, doubleTapSlop;
        private final int doubleTapTimeout;
        private boolean held, tap, mouse, firstMouse;
        private long downAt, firstUpAt = -1;
        private float downX, downY, firstX, firstY;

        WakeGesture(float touchSlop, float doubleTapSlop, int doubleTapTimeout) {
            this.touchSlop = touchSlop;
            this.doubleTapSlop = doubleTapSlop;
            this.doubleTapTimeout = doubleTapTimeout;
        }

        boolean accept(int action, long time, float x, float y, boolean mouseSource, boolean sleeping) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (held || !sleeping) reset();
                held = true;
                tap = sleeping;
                mouse = mouseSource;
                downAt = time;
                downX = x;
                downY = y;
                return false;
            }
            if (!held || action == MotionEvent.ACTION_CANCEL) {
                reset();
                return false;
            }
            tap &= sleeping && within(x - downX, y - downY, touchSlop);
            if (action != MotionEvent.ACTION_UP) return false;
            held = false;
            if (!tap || time - downAt > MAX_TAP_DURATION_MS) {
                reset();
                return false;
            }
            if (firstUpAt >= 0 && downAt - firstUpAt <= doubleTapTimeout && mouse == firstMouse
                    && within(downX - firstX, downY - firstY, doubleTapSlop)) {
                reset();
                return true;
            }
            firstUpAt = time;
            firstMouse = mouse;
            firstX = downX;
            firstY = downY;
            return false;
        }

        void reset() {
            held = false;
            firstUpAt = -1;
        }

        private static boolean within(float dx, float dy, float slop) {
            return dx * dx + dy * dy <= slop * slop;
        }
    }
}
