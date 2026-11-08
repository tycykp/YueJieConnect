package com.xiaomi.mirror.message;

import java.util.List;

/** Synthetic reflection fixture for inspected public field names; never packaged into the application. */
public final class NotificationPostMessage {
    public String id = "synthetic.package~7", appName = "人工通知", title = "合成标题", text = "合成内容", bigText = "";
    public long when = 123;
    public boolean ongoing, openable;
    public int style = 1;
    public Bytes smallIcon, largeIcon, bigPicture;
    public List<Button> buttons;
    public List<String> lines;
    public List<Message> messages;
    public static final class Button {
        public String id, text;
        public int type;
        public Button(String id, String text, int type) { this.id = id; this.text = text; this.type = type; }
    }
    public static final class Message {
        public String sender, text, time;
        public Message(String sender, String text) { this.sender = sender; this.text = text; time = "123"; }
    }
    public static final class Bytes {
        private final byte[] bytes;
        public int declaredSize;
        public boolean copied;
        public Bytes(byte[] bytes) { this.bytes = bytes; declaredSize = bytes.length; }
        public int size() { return declaredSize; }
        public byte[] C() { copied = true; return bytes.clone(); }
    }
}
