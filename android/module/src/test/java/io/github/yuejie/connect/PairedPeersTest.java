package io.github.yuejie.connect;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class PairedPeersTest {
    private static final String A="01".repeat(32), B="02".repeat(32);
    @Test public void consentSurvivesReloadWithoutSessionOrAddress(){
        PairedPeers peers=new PairedPeers(Set.of());
        assertTrue(peers.remember(A,Set.of("device_profile","battery_status")));
        PairedPeers restored=new PairedPeers(peers.encode());
        assertEquals(Set.of("device_profile","battery_status"),restored.grants(A));
        assertTrue(restored.grants(B).isEmpty());
        assertFalse(restored.remember(A,restored.grants(A)));
    }
    @Test public void restoredConsentDoesNotExpandToNewFeatures(){
        PairedPeers peers=new PairedPeers(Set.of());
        peers.remember(A,Set.of("device_profile","screen_video","screen_control","screen_request","nfc_tap","logical_open"));
        assertEquals(Set.of("device_profile","screen_video"),new PairedPeers(peers.encode()).grants(A));
        peers.remember(A,Set.of("device_profile"));
        assertEquals(Set.of("device_profile"),peers.grants(A));
    }
    @Test public void corruptOrUnknownRecordsCannotBecomeTrusted(){
        PairedPeers peers=new PairedPeers(Set.of("bad|device_profile",A+"|device_profile,unknown",B+"|battery_status"));
        assertTrue(peers.isEmpty());
        assertFalse(peers.remember("bad",Set.of("device_profile")));
        assertFalse(peers.remember(A,Set.of("battery_status")));
    }
    @Test public void onlyOnePeerInitiatesSimultaneousDiscovery(){
        assertTrue(PairedPeers.initiates(A,B));
        assertFalse(PairedPeers.initiates(B,A));
        assertFalse(PairedPeers.initiates(A,A));
        assertFalse(PairedPeers.initiates("",B));
    }
    @Test public void capacityIsBoundedAndExistingConsentCanBeUpdated(){
        PairedPeers peers=new PairedPeers(Set.of());
        for(int i=0;i<16;i++)assertTrue(peers.remember(String.format("%064x",i),Set.of("device_profile")));
        assertEquals(16,peers.size());assertFalse(peers.remember(A,Set.of("device_profile")));
        assertTrue(peers.remember("0".repeat(64),Set.of("device_profile","battery_status")));
    }
}
