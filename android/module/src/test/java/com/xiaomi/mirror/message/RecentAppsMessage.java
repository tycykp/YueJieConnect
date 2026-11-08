package com.xiaomi.mirror.message;
public final class RecentAppsMessage {
    public int action = 2;
    public java.util.List<RecentAppsInfo> appInfoList = new java.util.ArrayList<>();
    public static final class RecentAppsInfo {
        String packageName;
        public RecentAppsInfo(String pkg) { packageName = pkg; }
    }
}
