package com.xiaomi.mirror.connection;
public final class G {
    private static final G INSTANCE = new G();
    public Object peer = new Object(), other = new Object();
    public static G J() { return INSTANCE; }
    public String J0(int screen) { return screen == 7 ? "native-synthetic-pad" : "native-other"; }
    public Object t0(String id) { return id.equals("native-synthetic-pad") ? peer : id.equals("native-other") ? other : null; }
}
