package io.github.qiangjian.connect;

import android.inputmethodservice.InputMethodService;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputBinding;
import java.util.HashMap;
import java.util.Map;

/** Uses an already-active OEM IME; never switches IMEs or invents focus. Call on its main thread. */
public final class RemoteInputImeTarget implements RemoteInputSession.Target {
    private final InputMethodService service;
    private final InputConnection connection;
    private final EditorInfo editor;
    private final InputBinding binding;
    private final int ownerUid;
    private final long epoch;
    private final Map<Integer, Long> pressed = new HashMap<>();

    public RemoteInputImeTarget(InputMethodService service, long epoch) {
        requireMain();
        if (epoch <= 0 || service == null) throw new IllegalArgumentException("invalid_focus");
        this.service = service; this.epoch = epoch;
        connection = service.getCurrentInputConnection(); editor = service.getCurrentInputEditorInfo();
        binding = service.getCurrentInputBinding(); ownerUid = binding == null ? -1 : binding.getUid();
        if (connection == null || editor == null || ownerUid < 0) throw new IllegalStateException("native_ime_no_focus");
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("input_requires_main_thread");
    }
    @Override public boolean hasFocus(long epoch) {
        requireMain();
        return this.epoch == epoch && connection == service.getCurrentInputConnection()
                && editor == service.getCurrentInputEditorInfo() && service.getCurrentInputStarted() && sameBinding();
    }
    @Override public boolean commitText(String text) {
        requireMain(); return hasFocus(epoch) && connection.commitText(text, 1);
    }
    @Override public boolean key(int keyCode, boolean down) {
        requireMain(); if (!hasFocus(epoch)) return false;
        if (down == pressed.containsKey(keyCode) || pressed.size() >= 16 && down) return false;
        long now = SystemClock.uptimeMillis(), start = down ? now : pressed.get(keyCode);
        boolean accepted = connection.sendKeyEvent(new KeyEvent(start, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0));
        if (accepted) { if (down) pressed.put(keyCode, start); else pressed.remove(keyCode); }
        return accepted;
    }
    @Override public void cancel() {
        requireMain();
        // Release only the same connection, never dispatch delayed ups into a new editor.
        if (connection == service.getCurrentInputConnection() && editor == service.getCurrentInputEditorInfo() && sameBinding()) {
            for (var key : pressed.entrySet()) {
                try { connection.sendKeyEvent(new KeyEvent(key.getValue(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key.getKey(), 0)); }
                catch (RuntimeException ignored) { }
            }
        }
        pressed.clear();
    }
    private boolean sameBinding() {
        InputBinding current = service.getCurrentInputBinding();
        return current == binding && current != null && current.getUid() == ownerUid;
    }
}
