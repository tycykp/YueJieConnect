package io.github.qiangjian.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Versioned notification payload. Authentication and peer/session identity belong to the bridge envelope. */
final class NotificationSyncCodec {
    // Frame limit; NotificationSyncFragments is required for Binder's smaller 8 KiB JSON budget.
    static final int MAX_BYTES = 10 * 1024, POST = 1, REMOVE = 2, ACTION = 3, RECEIPT = 4;
    static final int MAX_RECEIPT_BYTES = 140, MAX_RECEIPT_BASE64 = 188;
    static final String OP_POST = "notification_post", OP_REMOVE = "notification_remove", OP_ACTION = "notification_action";
    record Button(String id, String title, boolean reply) {
        Button { bounded(id, 64, false); bounded(title, 512, false); }
    }
    record Post(String app, String title, String text, String bigText, long when, boolean ongoing,
                boolean openable, byte[] icon, byte[] picture, List<Button> buttons) {
        Post {
            bounded(app, 512, false); bounded(title, 1024, true); bounded(text, 2048, true);
            bounded(bigText, 2048, true);
            icon = copy(icon, 2048); picture = copy(picture, 4096);
            buttons = List.copyOf(buttons);
            if (buttons.size() > 8 || buttons.stream().map(Button::id).distinct().count() != buttons.size())
                throw invalid();
        }
        @Override public byte[] icon() { return icon.clone(); }
        @Override public byte[] picture() { return picture.clone(); }
    }
    record Action(String button, String reply) {
        Action { bounded(button, 64, false); bounded(reply, 1024, true); }
    }
    record Frame(int kind, String key, long revision, Post post, Action action, String result) {
        Frame {
            if (key == null || !key.matches("[a-zA-Z0-9_-]{1,96}") || revision < 1) throw invalid();
            if (kind < POST || kind > RECEIPT || (kind == POST) != (post != null)
                    || (kind == ACTION) != (action != null) || (kind == RECEIPT) != (result != null)) throw invalid();
            if (result != null && !List.of("posted", "removed", "pending_intent_finished").contains(result)) throw invalid();
        }
    }
    static Frame posted(String key, long revision, Post value) { return new Frame(POST, key, revision, value, null, null); }
    static Frame removed(String key, long revision) { return new Frame(REMOVE, key, revision, null, null, null); }
    static Frame action(String key, long revision, String button, String reply) {
        return new Frame(ACTION, key, revision, null, new Action(button, reply), null);
    }
    static Frame receipt(Frame request, String status) { return new Frame(RECEIPT, request.key, request.revision, null, null, status); }
    static String operation(Frame frame) {
        return switch (frame.kind) { case POST -> OP_POST; case REMOVE -> OP_REMOVE; case ACTION -> OP_ACTION; default -> throw invalid(); };
    }
    static byte[] encode(Frame f) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(0x514a4e31); out.writeByte(f.kind); write(out, f.key); out.writeLong(f.revision);
            if (f.post != null) {
                Post p = f.post;
                write(out, p.app); write(out, p.title); write(out, p.text); write(out, p.bigText);
                out.writeLong(p.when); out.writeBoolean(p.ongoing); out.writeBoolean(p.openable);
                blob(out, p.icon); blob(out, p.picture); out.writeByte(p.buttons.size());
                for (Button b : p.buttons) { write(out, b.id); write(out, b.title); out.writeBoolean(b.reply); }
            }
            if (f.action != null) { write(out, f.action.button); write(out, f.action.reply); }
            if (f.result != null) write(out, f.result);
            out.flush();
            if (buffer.size() > MAX_BYTES) throw invalid();
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static Frame decode(byte[] payload) {
        if (payload == null || payload.length > MAX_BYTES) throw invalid();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != 0x514a4e31) throw invalid();
            int kind = in.readUnsignedByte(); String key = read(in, 96); long rev = in.readLong();
            Post post = null; Action action = null; String result = null;
            if (kind == POST) {
                String app = read(in, 512), title = read(in, 1024), text = read(in, 2048), big = read(in, 2048);
                long when = in.readLong(); boolean ongoing = bool(in), openable = bool(in);
                byte[] icon = blob(in, 2048), picture = blob(in, 4096);
                int count = in.readUnsignedByte(); if (count > 8) throw invalid();
                List<Button> buttons = new ArrayList<>();
                for (int i = 0; i < count; i++) buttons.add(new Button(read(in, 64), read(in, 512), bool(in)));
                post = new Post(app, title, text, big, when, ongoing, openable, icon, picture, buttons);
            }
            if (kind == ACTION) action = new Action(read(in, 64), read(in, 1024));
            if (kind == RECEIPT) result = read(in, 64);
            Frame frame = new Frame(kind, key, rev, post, action, result);
            if (in.available() != 0) throw invalid();
            return frame;
        } catch (IOException | RuntimeException error) { throw invalid(); }
    }
    static void checkReceipt(Frame request, byte[] bytes) {
        Frame r = decode(bytes);
        String expected = switch (request.kind) {
            case POST -> "posted"; case REMOVE -> "removed";
            case ACTION -> request.action.button.equals("dismiss") ? "removed" : "pending_intent_finished";
            default -> throw invalid();
        };
        if (r.kind != RECEIPT || !r.key.equals(request.key) || r.revision != request.revision || !expected.equals(r.result))
            throw new IllegalArgumentException("notification_invalid_receipt");
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int b = in.readUnsignedByte(); if (b > 1) throw invalid(); return b == 1;
    }
    private static void write(DataOutputStream out, String s) throws IOException { blob(out, utf8(s)); }
    private static void blob(DataOutputStream out, byte[] b) throws IOException { out.writeInt(b.length); out.write(b); }
    private static byte[] blob(DataInputStream in, int limit) throws IOException {
        int n = in.readInt(); if (n < 0 || n > limit || n > in.available()) throw invalid();
        return in.readNBytes(n);
    }
    private static String read(DataInputStream in, int limit) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(blob(in, limit))).toString(); }
        catch (CharacterCodingException e) { throw invalid(); }
    }
    private static byte[] utf8(String value) {
        if (value == null) throw invalid();
        try {
            ByteBuffer b = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[b.remaining()]; b.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw invalid(); }
    }
    private static void bounded(String value, int max, boolean empty) {
        if (value == null || (!empty && value.isEmpty()) || utf8(value).length > max) throw invalid();
    }
    private static byte[] copy(byte[] b, int max) {
        if (b == null || b.length > max) throw invalid(); return b.clone();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("notification_invalid_payload"); }
}
