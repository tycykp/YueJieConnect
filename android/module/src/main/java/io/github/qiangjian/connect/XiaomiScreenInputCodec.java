package io.github.qiangjian.connect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Copies recyclable native MouseMessage/KeyMessage objects at the real peer-bound send entrance.
 * It neither selects a peer from an IP nor grants input permission. The caller owns original message
 * recycling and must use the authenticated FIFO control transport, not an unregistered operation. */
public final class XiaomiScreenInputCodec implements AutoCloseable {
    /** Native text is a separate IME operation; it cannot masquerade as desktop key events. */
    public record TextEvent(String text) {
        public TextEvent {
            if (text == null || text.isEmpty() || text.length() > 8192 || text.indexOf('\0') >= 0
                    || text.getBytes(StandardCharsets.UTF_8).length > 8192)
                throw new IllegalArgumentException("invalid_native_ime_text");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                        throw new IllegalArgumentException("invalid_native_ime_text");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid_native_ime_text");
            }
        }
        public RemoteInputSession.Result apply(RemoteInputSession ime, RemoteInputSession.Scope actualImeFocus, long sequence) {
            return ime.text(actualImeFocus, sequence, text);
        }
        @Override public String toString() { return "NativeImeText"; }
    }
    public enum Kind { MOVE, BUTTON, SCROLL, KEY }
    public record Event(Kind kind, int keyCode, int button, boolean down, float x, float y,
                        float horizontal, float vertical, int metaState) {
        public Event {
            Objects.requireNonNull(kind);
            if ((metaState & ~RemoteInputDesktopSession.META_MASK) != 0
                    || !Float.isFinite(x) || !Float.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1
                    || !Float.isFinite(horizontal) || !Float.isFinite(vertical)
                    || Math.abs(horizontal) > 32 || Math.abs(vertical) > 32)
                throw new IllegalArgumentException("invalid_native_input_event");
            boolean valid = switch (kind) {
                case KEY -> keyCode >= 1 && keyCode <= 1023 && button == 0 && x == 0 && y == 0 && horizontal == 0 && vertical == 0;
                case BUTTON -> keyCode == 0 && (button == 1 || button == 2 || button == 4) && horizontal == 0 && vertical == 0;
                case MOVE -> keyCode == 0 && button == 0 && !down && horizontal == 0 && vertical == 0;
                case SCROLL -> keyCode == 0 && button == 0 && !down && (horizontal != 0 || vertical != 0);
            };
            if (!valid) throw new IllegalArgumentException("invalid_native_input_event");
        }
        public RemoteInputSession.Result apply(RemoteInputDesktopSession session, RemoteInputSession.Scope scope, long sequence) {
            return switch (kind) {
                case KEY -> session.key(scope, sequence, keyCode, down, metaState);
                case MOVE -> session.move(scope, sequence, x, y, metaState);
                case BUTTON -> session.button(scope, sequence, button, down, x, y, metaState);
                case SCROLL -> session.scroll(scope, sequence, x, y, horizontal, vertical, metaState);
            };
        }
        @Override public String toString() { return "NativeDesktopInput(" + kind + ")"; }
    }
    private final Object peer;
    private final String peerId;
    private final int screenId, width, height;
    private final RemoteInputSession.Scope scope;
    private final BooleanSupplier currentApprovedNativeRoute;
    private final Class<?> mouse, key;
    private final Method nativePeerId;
    private final Field mouseScreen, mouseAction, mouseX, mouseY, axisX, axisY, mouseMeta;
    private final Field keyScreen, keyCode, keyDown, keyText, keyMeta;
    private volatile boolean closed;

    /** nativePeer is the exact k0 argument of this native request's MessageManagerImpl.send call.
     * peerId/screenId/geometry must come from the authenticated native screen mapping, not a payload. */
    public XiaomiScreenInputCodec(ClassLoader loader, Object nativePeer, String peerId, int screenId, int width, int height,
            RemoteInputSession.Scope scope, BooleanSupplier currentApprovedNativeRoute) throws ReflectiveOperationException {
        this(Class.forName("com.xiaomi.mirror.k0", false, loader),
                Class.forName("com.xiaomi.mirror.message.MouseMessage", false, loader),
                Class.forName("com.xiaomi.mirror.message.KeyMessage", false, loader),
                nativePeer, peerId, screenId, width, height, scope, currentApprovedNativeRoute);
    }
    XiaomiScreenInputCodec(Class<?> peerType, Class<?> mouse, Class<?> key, Object peer, String peerId,
            int screenId, int width, int height, RemoteInputSession.Scope scope, BooleanSupplier current)
            throws ReflectiveOperationException {
        if (!peerType.isInstance(peer) || peerId == null || peerId.isBlank() || peerId.length() > 256
                || screenId < 0 || width < 2 || height < 2 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("invalid_native_input_route");
        this.peer = peer; this.peerId = peerId; this.screenId = screenId; this.width = width; this.height = height;
        this.scope = Objects.requireNonNull(scope); currentApprovedNativeRoute = Objects.requireNonNull(current);
        this.mouse = mouse; this.key = key; nativePeerId = peerType.getMethod("h");
        if (nativePeerId.getReturnType() != String.class) throw new NoSuchMethodException("native_peer_id_type_changed");
        mouseScreen = field(mouse,"screenId",int.class); mouseAction = field(mouse,"action",int.class);
        mouseX = field(mouse,"x",int.class); mouseY = field(mouse,"y",int.class);
        axisX = field(mouse,"xAxis",float.class); axisY = field(mouse,"yAxis",float.class); mouseMeta = field(mouse,"metaInfo",int.class);
        keyScreen = field(key,"screenId",int.class); keyCode = field(key,"keyCode",int.class);
        keyDown = field(key,"down",boolean.class); keyText = field(key,"text",String.class); keyMeta = field(key,"metaInfo",int.class);
        if (!current()) throw new IllegalStateException("native_input_route_changed");
    }
    private static Field field(Class<?> type,String name,Class<?> expected) throws ReflectiveOperationException {
        Field value = type.getDeclaredField(name);
        if (value.getType() != expected) throw new NoSuchFieldException("native_input_field_type_changed");
        value.setAccessible(true); return value;
    }
    public RemoteInputSession.Scope scope() { return scope; }
    public int screenId() { return screenId; }
    public boolean current() {
        try {
            if (!closed && currentApprovedNativeRoute.getAsBoolean() && peerId.equals(nativePeerId.invoke(peer))) return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        closed = true; return false;
    }
    /** Call synchronously before the OEM returns/recycles message. Null means unrelated message or
     * native IME text (which requires its own actual InputConnection lease); no text is retained.
     * Route mismatch throws so a previously managed event cannot silently select another peer. */
    public Event snapshot(Object actualPeer, Object message) throws ReflectiveOperationException {
        if (actualPeer != peer || !current()) throw new IllegalStateException("native_input_route_changed");
        if (message != null && message.getClass() == key) {
            if (keyScreen.getInt(message) != screenId) throw new IllegalStateException("native_input_screen_changed");
            String text = (String) keyText.get(message);
            if (text != null && !text.isEmpty()) return null;
            return new Event(Kind.KEY, keyCode.getInt(message), 0, keyDown.getBoolean(message), 0, 0, 0, 0, keyMeta.getInt(message));
        }
        if (message == null || message.getClass() != mouse) return null;
        if (mouseScreen.getInt(message) != screenId) throw new IllegalStateException("native_input_screen_changed");
        int px = mouseX.getInt(message), py = mouseY.getInt(message);
        if (px < 0 || py < 0 || px >= width || py >= height) throw new IllegalArgumentException("native_input_coordinates_outside_screen");
        float x = (float) px / (width - 1), y = (float) py / (height - 1);
        int action = mouseAction.getInt(message), meta = mouseMeta.getInt(message);
        return switch (action) {
            case 1,2 -> new Event(Kind.BUTTON,0,RemoteInputDesktopSession.PRIMARY,action == 1,x,y,0,0,meta);
            case 4,5 -> new Event(Kind.BUTTON,0,RemoteInputDesktopSession.SECONDARY,action == 4,x,y,0,0,meta);
            case 3,7 -> new Event(Kind.MOVE,0,0,false,x,y,0,0,meta);
            case 6 -> new Event(Kind.SCROLL,0,0,false,x,y,axisX.getFloat(message),axisY.getFloat(message),meta);
            default -> throw new IllegalArgumentException("invalid_native_mouse_action");
        };
    }
    /** Use only with an independently current remote IME focus lease. String is immutable even
     * after OEM message recycling; callers must not log it or its returned text accessor. */
    public TextEvent snapshotText(Object actualPeer, Object message) throws ReflectiveOperationException {
        if (actualPeer != peer || !current()) throw new IllegalStateException("native_input_route_changed");
        if (message == null || message.getClass() != key) return null;
        if (keyScreen.getInt(message) != screenId) throw new IllegalStateException("native_input_screen_changed");
        String text = (String) keyText.get(message);
        return text == null || text.isEmpty() ? null : new TextEvent(text);
    }
    @Override public void close() { closed = true; }
}
