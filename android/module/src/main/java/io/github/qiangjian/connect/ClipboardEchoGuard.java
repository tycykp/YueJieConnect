package io.github.qiangjian.connect;

import android.content.ClipData;
import java.util.Objects;

/** Suppress only the received platform event, not a later user copy of the same content.
 * Calls belong to the native main thread. Retains an HTML digest or a local URI, no text. */
final class ClipboardEchoGuard {
    private String session, htmlDigest, mime;
    private android.net.Uri uri;
    private boolean pending;
    private java.lang.ref.WeakReference<ClipData> stamped;
    private long timestamp;

    void armHtml(String session, ClipboardRichContent.Html html) {
        clear(); this.session = session; htmlDigest = html.digest(); pending = true;
    }
    void armUri(String session, android.net.Uri uri, String mime) {
        clear(); this.session = session; this.uri = uri; this.mime = mime; pending = true;
    }
    void seal(ClipData actual) {
        timestamp = actual.getDescription().getTimestamp();
        stamped = timestamp == 0 ? new java.lang.ref.WeakReference<>(actual) : null; pending = false;
    }
    boolean suppress(String session, ClipData clip) {
        if (!Objects.equals(this.session, session) || this.session == null || clip == null) return false;
        boolean same;
        if (uri != null) same = ClipboardNativeContent.matchesUri(clip, uri, mime);
        else try { same = htmlDigest != null && htmlDigest.equals(ClipboardRichContent.snapshot(clip).digest()); }
        catch (IllegalArgumentException invalid) { return false; }
        if (!same) return false;
        // During the synchronous native sink the system may notify before stamping/readback.
        // Afterwards a new timestamp is a new user event, even if its bytes are identical.
        return pending || timestamp > 0 && clip.getDescription().getTimestamp() == timestamp
                || timestamp == 0 && stamped != null && clip == stamped.get();
    }
    void clear() { session = null; htmlDigest = null; mime = null; uri = null; pending = false; stamped = null; timestamp = 0; }
}
