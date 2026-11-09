package io.github.yuejie.connect;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs only in scripts/test-screen-ime-focus.ps1 with controlled framework boundary doubles. */
public final class RemoteInputImeTargetCheck {
    private static final class Connection implements InputConnection {
        final ArrayList<KeyEvent> keys = new ArrayList<>();
        int texts;
        public boolean commitText(CharSequence text, int position) { texts++; return true; }
        public boolean sendKeyEvent(KeyEvent key) { keys.add(key); return true; }
    }
    private static final class Fixture {
        final InputMethodService service = new InputMethodService();
        final Connection connection = new Connection();
        Fixture() { service.connection = connection; service.editor = new EditorInfo(); service.binding = new InputBinding(10001); }
        RemoteInputImeTarget target() { return new RemoteInputImeTarget(service, 9); }
    }
    private static void check(boolean valid) { if (!valid) throw new AssertionError("IME focus isolation failed"); }
    public static void main(String[] ignored) {
        Fixture f = new Fixture(); RemoteInputImeTarget target = f.target();
        check(target.hasFocus(9) && target.commitText("synthetic") && f.connection.texts == 1);
        check(!target.hasFocus(10));
        check(target.key(29, true) && target.key(29, false));
        check(f.connection.keys.size() == 2);

        f = new Fixture(); target = f.target(); check(target.key(29, true));
        f.service.binding = new InputBinding(10001); // OEM reused connection/editor, but this is a new binding.
        check(!target.hasFocus(9) && !target.commitText("synthetic") && !target.key(29, false));
        target.cancel(); check(f.connection.keys.size() == 1 && f.connection.texts == 0);

        f = new Fixture(); target = f.target(); check(target.key(29, true));
        f.service.binding = new InputBinding(10002); // Different system-authenticated app UID.
        target.cancel(); check(f.connection.keys.size() == 1 && !target.hasFocus(9));

        f = new Fixture(); target = f.target(); check(target.key(29, true));
        f.service.editor = new EditorInfo(); // Same connection/binding, new editor.
        target.cancel(); check(f.connection.keys.size() == 1 && !target.commitText("synthetic"));

        f = new Fixture(); AtomicBoolean permitted = new AtomicBoolean(true);
        var scope = new RemoteInputSession.Scope("a".repeat(64), 1, 9);
        var session = new RemoteInputSession(scope, permitted::get, f.target());
        check(session.key(scope, 1, 29, true) == RemoteInputSession.Result.ACCEPTED);
        permitted.set(false);
        check(session.text(scope, 2, "synthetic") == RemoteInputSession.Result.DENIED);
        check(f.connection.keys.size() == 2 && f.connection.keys.get(1).action == KeyEvent.ACTION_UP);
        permitted.set(true);
        check(session.text(scope, 3, "synthetic") == RemoteInputSession.Result.STALE_SESSION && f.connection.texts == 0);

        f = new Fixture(); f.service.binding = null;
        boolean rejected = false;
        try { f.target(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected);
        System.out.println("PASS: 6 actual IME target focus/binding/UID/cleanup cases with host framework doubles");
    }
}
