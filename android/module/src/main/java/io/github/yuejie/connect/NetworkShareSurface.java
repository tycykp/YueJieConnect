package io.github.yuejie.connect;

import static io.github.yuejie.connect.NetworkShareSession.*;

/** A short-lived view of an authenticated share; never an authorization to create one. */
final class NetworkShareSurface {
    static final String STATE = "io.github.yuejie.connect.NETWORK_SURFACE";
    static final String CONTROL = "io.github.yuejie.connect.NETWORK_SYSTEMUI_CONTROL";
    static final String MODULE_PERMISSION = "io.github.yuejie.connect.permission.NETWORK_CONTROL";
    static final String SYSTEM_PERMISSION = "android.permission.STATUS_BAR_SERVICE";
    static final String SYSTEM_UI = "com.android.systemui";
    record Snapshot(String session, String id, String name, Phase phase, Upstream upstream,
                    boolean available, long observedAt) {
        Snapshot {
            if(session==null||!session.matches("[a-f0-9]{64}")||id==null||!id.matches("yj:[a-f0-9]{64}")
                ||name==null||name.isBlank()||name.length()>128||phase==null||observedAt<0
                ||phase==Phase.ACTIVE&&upstream==null)throw new IllegalArgumentException("invalid_surface");
        }
        boolean live(long now){return now>=observedAt&&now-observedAt<=7000;}
        int state(long now){
            if(!live(now)||!available&&phase!=Phase.ACTIVE&&phase!=Phase.STOPPING)return 0;
            return NetworkSharePolicy.xiaomi(phase,upstream,available);
        }
        boolean controls(String target,long now){return live(now)&&id.equals(target)&&state(now)!=0;}
    }
    private NetworkShareSurface(){}
}
