package com.xiaomi.mirror.message;
public final class ClipDataMessage {
    public static final class FileInfo {
        public String url, name;
        public long size;
    }
    public java.util.List<FileInfo> fileList = new java.util.ArrayList<>();
}
