package io.github.qiangjian.connect;

import com.xiaomi.mirror.message.RelayAppMessage;
import com.xiaomi.mirror.message.RecentAppsMessage;
import com.oplus.synergy.bean.RelayInfo;
import com.oplus.synergy.bean.AbilityProfile;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelayCatalogTest {
    @Test public void directoryRequiresBoundPeerAndLocalSupport() throws Exception {
        RelayAppMessage message = new RelayAppMessage();
        var candidate = XiaomiRelayCatalog.relay(message).candidates().get(0);
        assertTrue(candidate.eligible(true, true));
        assertFalse(candidate.eligible(false, true)); assertFalse(candidate.eligible(true, false));
        assertFalse(candidate.toString().contains("private"));
        message.mDstDeviceType = 1;
        assertFalse(XiaomiRelayCatalog.relay(message).candidates().get(0).advertisesDataRelay());
        message.mDstDeviceType = 2; message.unregisterRelay = true;
        assertFalse(XiaomiRelayCatalog.relay(message).candidates().get(0).visible());
    }
    @Test public void recentAppsAreNotDataRelayAndAreBounded() throws Exception {
        RecentAppsMessage message = new RecentAppsMessage();
        message.appInfoList.add(new RecentAppsMessage.RecentAppsInfo("test.synthetic.app"));
        assertFalse(XiaomiRelayCatalog.recent(message).candidates().get(0).advertisesDataRelay());
        message.appInfoList.add(new RecentAppsMessage.RecentAppsInfo("test.synthetic.app"));
        assertThrows(IllegalArgumentException.class, () -> XiaomiRelayCatalog.recent(message));
        message.appInfoList.clear();
        for (int i = 0; i < 5; i++) message.appInfoList.add(new RecentAppsMessage.RecentAppsInfo("test.synthetic.app" + i));
        assertThrows(IllegalArgumentException.class, () -> XiaomiRelayCatalog.recent(message));
    }
    @Test public void castAndPcCannotAdvertiseDataRelay() throws Exception {
        RelayInfo info = new RelayInfo();
        assertTrue(OplusRelayCatalog.relayInfo(info).candidates().get(0).advertisesDataRelay());
        info.relay = "2";
        assertFalse(OplusRelayCatalog.relayInfo(info).candidates().get(0).advertisesDataRelay());
        info.relay = "1"; info.device = 2;
        assertFalse(OplusRelayCatalog.relayInfo(info).candidates().get(0).advertisesDataRelay());
        info.status = "hide"; info.pkg = null;
        assertTrue(OplusRelayCatalog.relayInfo(info).clearAll());
    }
    @Test public void profileSchemaDoesNotDecodeIconsOrPretendSupport() throws Exception {
        var profiles = List.of(new AbilityProfile("Relay_Base_Key_Payload_Type", "2"),
                new AbilityProfile("Relay_Base_Key_Pkg_Name", "test.synthetic.app"),
                new AbilityProfile("Relay_Extend_Key_Icon_Status", "show"),
                new AbilityProfile("Relay_Extend_Key_Relay_Type", "1"),
                new AbilityProfile("Relay_Extend_Key_Peer_Device_Type", "1"),
                new AbilityProfile("Relay_Extend_Key_Icon_Data", "not-decoded"));
        assertTrue(OplusRelayCatalog.profiles(profiles).candidates().get(0).advertisesDataRelay());
        var duplicate = new java.util.ArrayList<>(profiles); duplicate.add(profiles.get(0));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayCatalog.profiles(duplicate));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayCatalog.profiles(List.of(new AbilityProfile("Relay_Base_Key_Payload_Type", "0"))));
    }
}
