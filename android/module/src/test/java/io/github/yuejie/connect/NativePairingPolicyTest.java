package io.github.yuejie.connect;

import java.net.InetAddress;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePairingPolicyTest {
    @Test public void discoveryAliasesMatchTheAuthenticatedGateway(){
        String key="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        assertEquals("yj:0dbdb850629ef3d358848891a05dd349464eb769dfe531b0379d508e851339d7",NativePairingPolicy.alias(1,key));
        assertEquals("yj:3f48df81b78df89a85c2bb4fb98bcd21a62d11544c48d6818367673dd418b588",NativePairingPolicy.alias(2,key));
        assertThrows(IllegalArgumentException.class,()->NativePairingPolicy.alias(0,key));
        assertThrows(IllegalArgumentException.class,()->NativePairingPolicy.alias(1,"malformed"));
    }
    @Test public void discoveryOnlyAcceptsLanIpv4Endpoints()throws Exception{
        InetAddress lan=InetAddress.getByName("192.168.1.3");
        assertEquals(lan,NativePairingPolicy.lanAddress(java.util.List.of(InetAddress.getByName("fe80::1"),lan),38271));
        assertNull(NativePairingPolicy.lanAddress(java.util.List.of(InetAddress.getByName("fe80::1")),38271));
        assertTrue(NativePairingPolicy.endpoint(InetAddress.getByName("192.168.1.3"),38271));
        assertTrue(NativePairingPolicy.endpoint(InetAddress.getByName("169.254.1.3"),1234));
        for(String ip:new String[]{"8.8.8.8","127.0.0.1","0.0.0.0","224.0.0.251","::1"})
            assertFalse(NativePairingPolicy.endpoint(InetAddress.getByName(ip),38271));
        assertFalse(NativePairingPolicy.endpoint(InetAddress.getByName("192.168.1.3"),0));
    }
    @Test public void onlyDiscoveryPagesCanEnterPairing(){
        assertTrue(NativePairingPolicy.caller(CallerPolicy.XIAOMI));assertTrue(NativePairingPolicy.caller(CallerPolicy.OPLUS));
        assertTrue(NativePairingPolicy.caller(CallerPolicy.OPLUS_PAIRING));
        for(String pkg:new String[]{CallerPolicy.MIRROR,CallerPolicy.OPLUS_FINDER,CallerPolicy.OPLUS_CAST,"io.github.yuejie.connect","unknown"})
            assertFalse(NativePairingPolicy.caller(pkg));
    }
    @Test public void repeatedStartCannotReplaceHandshakeOrActivePeer(){
        for(String phase:new String[]{"connecting","handshaking","awaiting_confirmation","active"})assertFalse(NativePairingPolicy.idle(phase));
        assertTrue(NativePairingPolicy.idle("stopped"));assertTrue(NativePairingPolicy.idle("disconnected"));assertTrue(NativePairingPolicy.idle("listening"));
    }
    @Test public void grantsAreExplicitAndRejectHiddenCapabilities(){
        assertTrue(NativePairingPolicy.grants(Set.copyOf(NativePairingPolicy.DEFAULT_GRANTS)));
        assertTrue(NativePairingPolicy.DEFAULT_GRANTS.containsAll(Set.of("clipboard_write","clipboard_rich_write","clipboard_uri_write")));
        assertTrue(NativePairingPolicy.grants(Set.of("device_profile","device_finder")));
        PairedPeers previous=new PairedPeers(Set.of("a".repeat(64)+"|device_profile,clipboard_write"));
        assertFalse(previous.grants("a".repeat(64)).contains("device_finder"));
        assertFalse(NativePairingPolicy.grants(Set.of("device_profile","clipboard_uri_write")));
        assertTrue(NativePairingPolicy.grants(Set.of("device_profile","battery_status","file_transfer","clipboard_write")));
        assertTrue(NativePairingPolicy.grants(Set.of("device_profile","screen_video")));
        assertFalse(NativePairingPolicy.grants(Set.of("battery_status")));
        for(String op:new String[]{"camera_video","logical_open","notification_action","network_share","unknown"})
            assertFalse(NativePairingPolicy.grants(Set.of("device_profile",op)));
        assertFalse(NativePairingPolicy.session(""));assertFalse(NativePairingPolicy.session("stale"));assertTrue(NativePairingPolicy.session("a".repeat(64)));
    }
}
