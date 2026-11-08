package com.xiaomi.mirror.message;

/** Synthetic test shape only, never included in an APK. Exact fields separately checked in DEX. */
public final class RelayAppMessage {
    public int type = 3, mDstDeviceType = 2;
    public boolean isHideIcon, unregisterRelay, isRelay = true, supportHandoff = true;
    public String processName = "test.relay.app", appName = "Synthetic App", description = "private document title";
}
