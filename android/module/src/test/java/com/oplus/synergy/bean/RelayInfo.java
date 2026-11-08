package com.oplus.synergy.bean;
public final class RelayInfo {
    public String pkg = "test.relay.app", status = "show", relay = "1";
    public int device = 1;
    public String getPkgName() { return pkg; }
    public String getActivityName() { return "test.relay.app.Main"; }
    public String getAppIntentStatus() { return status; }
    public String getRelayType() { return relay; }
    public int getDeviceType() { return device; }
}
