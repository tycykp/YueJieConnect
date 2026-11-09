package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static io.github.yuejie.connect.RemoteInputSession.Result.*;

/** Mouse and hardware keys share one sequence ledger and one native focus lease.
 * Events are built with local uptime; peer clocks, display IDs and UIDs never select the target.
 * Call on the same bounded input worker as the native target, never a Binder/UI polling thread. */
public final class RemoteInputDesktopSession implements AutoCloseable {
    public static final int PRIMARY = 1, SECONDARY = 2, TERTIARY = 4, MAX_HELD_KEYS = 16;
    public static final int META_MASK = 0x007770ff;
    public enum MouseAction { DOWN, UP, MOVE, HOVER_MOVE, BUTTON_PRESS, BUTTON_RELEASE, SCROLL, CANCEL }
    public enum ReleaseResult { NOT_NEEDED, RELEASED, FOCUS_CHANGED, NATIVE_REJECTED }
    /** Local OEM observation, not a remotely supplied payload. Rebind with a new epoch after resize. */
    public record Focus(int displayId, int targetUid, int width, int height, long epoch) {
        public Focus {
            if (displayId < 0 || targetUid < 0 || width < 1 || height < 1 || width > 8192 || height > 8192 || epoch <= 0)
                throw new IllegalArgumentException("invalid_native_input_focus");
        }
    }
    public record Mouse(MouseAction action, long downTimeMs, long eventTimeMs, float x, float y,
                        int buttons, int actionButton, float scrollX, float scrollY, int metaState) { }
    public record Key(int keyCode, boolean down, long downTimeMs, long eventTimeMs,
                      int repeatCount, int metaState, boolean cancelled) { }
    public record Reset(Mouse mouse, List<Key> keys) {
        public Reset { keys = List.copyOf(keys); }
    }
    public interface Target {
        boolean hasFocus(long epoch);
        boolean mouse(Mouse event);
        boolean key(Key event);
        /** Only release the original focus/UID. Never send delayed ups to a replacement editor. */
        ReleaseResult release(Reset reset);
    }
    private record Held(long downTime, int repeats) { }
    private final RemoteInputSession.Scope scope;
    private final BooleanSupplier allowed;
    private final LongSupplier uptime;
    private final Target target;
    private final Map<Integer, Held> keys = new LinkedHashMap<>();
    private long sequence, lastTime, mouseDownTime;
    private int buttons, suppliedMeta;
    private float x, y;
    private boolean mouseContact, closed;
    private ReleaseResult releaseResult = ReleaseResult.NOT_NEEDED;

    public RemoteInputDesktopSession(RemoteInputSession.Scope scope, BooleanSupplier currentAndInputAllowed,
                                     LongSupplier uptimeMillis, Target target) {
        this.scope = Objects.requireNonNull(scope); allowed = Objects.requireNonNull(currentAndInputAllowed);
        uptime = Objects.requireNonNull(uptimeMillis); this.target = Objects.requireNonNull(target);
    }
    private RemoteInputSession.Result check(RemoteInputSession.Scope request, long sequence) {
        if (request == null || sequence <= 0) return INVALID_PAYLOAD;
        if (closed || !scope.sessionId().equals(request.sessionId()) || scope.streamId() != request.streamId()) return STALE_SESSION;
        if (scope.focusEpoch() != request.focusEpoch()) return STALE_FOCUS;
        if (sequence <= this.sequence) return REPLAY;
        this.sequence = sequence;
        boolean granted, focused;
        try { granted = allowed.getAsBoolean(); } catch (RuntimeException error) { granted = false; }
        if (!granted) { close(); return DENIED; }
        try { focused = target.hasFocus(scope.focusEpoch()); } catch (RuntimeException error) { focused = false; }
        if (!focused) { close(); return NO_FOCUS; }
        return ACCEPTED;
    }
    private long now() { return lastTime = Math.max(lastTime, Math.max(0, uptime.getAsLong())); }
    private static boolean coordinates(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && x >= 0 && x <= 1 && y >= 0 && y <= 1;
    }
    private Mouse mouse(MouseAction action, long time, int actionButton, float scrollX, float scrollY) {
        return new Mouse(action, mouseContact ? mouseDownTime : time, time, x, y,
                buttons, actionButton, scrollX, scrollY, modifiers());
    }
    private boolean inject(Mouse event) {
        try { if (target.mouse(event)) return true; } catch (RuntimeException ignored) { }
        close(); return false;
    }
    public RemoteInputSession.Result move(RemoteInputSession.Scope request, long sequence, float x, float y) {
        return move(request, sequence, x, y, 0);
    }
    public synchronized RemoteInputSession.Result move(RemoteInputSession.Scope request, long sequence, float x, float y, int sourceMeta) {
        var result = check(request, sequence); if (result != ACCEPTED) return result;
        if (!coordinates(x, y) || !setMeta(sourceMeta)) return INVALID_PAYLOAD;
        this.x = x; this.y = y;
        return inject(mouse(mouseContact ? MouseAction.MOVE : MouseAction.HOVER_MOVE, now(), 0, 0, 0)) ? ACCEPTED : NATIVE_REJECTED;
    }
    public RemoteInputSession.Result button(RemoteInputSession.Scope request, long sequence,
                                            int button, boolean down, float x, float y) {
        return button(request, sequence, button, down, x, y, 0);
    }
    public synchronized RemoteInputSession.Result button(RemoteInputSession.Scope request, long sequence,
                                                         int button, boolean down, float x, float y, int sourceMeta) {
        var result = check(request, sequence); if (result != ACCEPTED) return result;
        if (!coordinates(x, y) || (button != PRIMARY && button != SECONDARY && button != TERTIARY)
                || down == ((buttons & button) != 0) || !setMeta(sourceMeta)) return INVALID_PAYLOAD;
        this.x = x; this.y = y;
        long time = now(); int previous = buttons;
        buttons = down ? buttons | button : buttons & ~button;
        if (down) {
            if (previous == 0) {
                mouseDownTime = time; mouseContact = true;
                if (!inject(mouse(MouseAction.DOWN, time, 0, 0, 0))) return NATIVE_REJECTED;
            }
            if (!inject(mouse(MouseAction.BUTTON_PRESS, time, button, 0, 0))) return NATIVE_REJECTED;
        } else {
            if (!inject(mouse(MouseAction.BUTTON_RELEASE, time, button, 0, 0))) return NATIVE_REJECTED;
            if (buttons == 0) {
                if (!inject(mouse(MouseAction.UP, time, 0, 0, 0))) return NATIVE_REJECTED;
                mouseContact = false; mouseDownTime = 0;
            }
        }
        return ACCEPTED;
    }
    public RemoteInputSession.Result scroll(RemoteInputSession.Scope request, long sequence,
                                            float x, float y, float horizontal, float vertical) {
        return scroll(request, sequence, x, y, horizontal, vertical, 0);
    }
    public synchronized RemoteInputSession.Result scroll(RemoteInputSession.Scope request, long sequence,
                                                         float x, float y, float horizontal, float vertical, int sourceMeta) {
        var result = check(request, sequence); if (result != ACCEPTED) return result;
        if (!coordinates(x, y) || !Float.isFinite(horizontal) || !Float.isFinite(vertical)
                || Math.abs(horizontal) > 32 || Math.abs(vertical) > 32 || horizontal == 0 && vertical == 0
                || !setMeta(sourceMeta))
            return INVALID_PAYLOAD;
        this.x = x; this.y = y;
        return inject(mouse(MouseAction.SCROLL, now(), 0, horizontal, vertical)) ? ACCEPTED : NATIVE_REJECTED;
    }
    /** Repeated down events with fresh sequence numbers become bounded native key repeat events. */
    public RemoteInputSession.Result key(RemoteInputSession.Scope request, long sequence, int keyCode, boolean down) {
        return key(request, sequence, keyCode, down, 0);
    }
    public synchronized RemoteInputSession.Result key(RemoteInputSession.Scope request, long sequence, int keyCode, boolean down, int sourceMeta) {
        var result = check(request, sequence); if (result != ACCEPTED) return result;
        if (keyCode < 1 || keyCode > 1023 || !setMeta(sourceMeta)) return INVALID_PAYLOAD; // Target enforces getMaxKeyCode().
        Held held = keys.get(keyCode);
        if (!down && held == null || down && held == null && keys.size() >= MAX_HELD_KEYS
                || down && held != null && held.repeats >= 10_000) return INVALID_PAYLOAD;
        long time = now(), start = held == null ? time : held.downTime;
        int repeats = down && held != null ? held.repeats + 1 : 0;
        if (down) keys.put(keyCode, new Held(start, repeats));
        // Retain the attempted key until a native UP succeeds so failure cleanup can cancel it.
        int meta = modifiersExcluding(down ? -1 : keyCode);
        boolean accepted;
        try { accepted = target.key(new Key(keyCode, down, start, time, repeats, meta, false)); }
        catch (RuntimeException error) { accepted = false; }
        if (!accepted) { close(); return NATIVE_REJECTED; }
        if (!down) keys.remove(keyCode);
        return ACCEPTED;
    }
    private boolean setMeta(int meta) {
        if ((meta & ~META_MASK) != 0) return false;
        if ((meta & 0xc0) != 0) meta |= 0x1;
        if ((meta & 0x30) != 0) meta |= 0x2;
        if ((meta & 0x6000) != 0) meta |= 0x1000;
        if ((meta & 0x60000) != 0) meta |= 0x10000;
        suppliedMeta = meta; return true;
    }
    private int modifiers() { return modifiersExcluding(-1); }
    private int modifiersExcluding(int excluded) {
        int meta = suppliedMeta;
        for (int key : keys.keySet()) if (key != excluded) meta |= switch (key) {
            case 57 -> 0x02 | 0x10; case 58 -> 0x02 | 0x20; // ALT left/right
            case 59 -> 0x01 | 0x40; case 60 -> 0x01 | 0x80; // SHIFT left/right
            case 113 -> 0x1000 | 0x2000; case 114 -> 0x1000 | 0x4000; // CTRL
            case 117 -> 0x10000 | 0x20000; case 118 -> 0x10000 | 0x40000; // META
            case 119 -> 0x08; default -> 0;
        };
        return meta;
    }
    public synchronized ReleaseResult releaseResult() { return releaseResult; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        long time;
        try { time = now(); } catch (RuntimeException error) { time = lastTime; }
        Mouse cancellation = mouseContact ? mouse(MouseAction.CANCEL, time, 0, 0, 0) : null;
        List<Key> releases = new ArrayList<>();
        List<Integer> reverse = new ArrayList<>(keys.keySet()); Collections.reverse(reverse);
        for (int code : reverse) {
            Held held = keys.remove(code);
            releases.add(new Key(code, false, held.downTime, time, 0, modifiers(), true));
        }
        buttons = 0; mouseContact = false;
        if (cancellation == null && releases.isEmpty()) return;
        try { releaseResult = Objects.requireNonNull(target.release(new Reset(cancellation, releases))); }
        catch (RuntimeException error) { releaseResult = ReleaseResult.NATIVE_REJECTED; }
    }
}
