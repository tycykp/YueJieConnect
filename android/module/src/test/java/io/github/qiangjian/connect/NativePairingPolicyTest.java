package io.github.qiangjian.connect;

import java.net.InetAddress;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePairingPolicyTest {
    @Test public void discoveryAliasesMatchTheAuthenticatedGateway(){
        String key="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        assertEquals("qj:24506874f034634d0e4470f94008a17cfcade37a7d0183a88c9e07fc99f28a21",NativePairingPolicy.alias(1,key));
        assertEquals("qj:249b35c938b6e79408563d70617dca45d7587a6ce0f69d6f77bb1a1aad2a42d8",NativePairingPolicy.alias(2,key));
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
        for(String pkg:new String[]{CallerPolicy.MIRROR,CallerPolicy.OPLUS_FINDER,CallerPolicy.OPLUS_CAST,"io.github.qiangjian.connect","unknown"})
            assertFalse(NativePairingPolicy.caller(pkg));
    }
    @Test public void repeatedStartCannotReplaceHandshakeOrActivePeer(){
        for(String phase:new String[]{"connecting","handshaking","awaiting_confirmation","active"})assertFalse(NativePairingPolicy.idle(phase));
        assertTrue(NativePairingPolicy.idle("stopped"));assertTrue(NativePairingPolicy.idle("disconnected"));assertTrue(NativePairingPolicy.idle("listening"));
    }
    @Test public void grantsAreExplicitAndRejectHiddenCapabilities(){
        assertTrue(NativePairingPolicy.grants(Set.of("device_profile","battery_status","file_transfer","clipboard_write")));
        assertTrue(NativePairingPolicy.grants(Set.of("device_profile","screen_video")));
        assertFalse(NativePairingPolicy.grants(Set.of("battery_status")));
        for(String op:new String[]{"camera_video","logical_open","notification_action","network_share","unknown"})
            assertFalse(NativePairingPolicy.grants(Set.of("device_profile",op)));
        assertFalse(NativePairingPolicy.session(""));assertFalse(NativePairingPolicy.session("stale"));assertTrue(NativePairingPolicy.session("a".repeat(64)));
    }
}
