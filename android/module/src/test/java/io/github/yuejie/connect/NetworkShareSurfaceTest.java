package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.NetworkShareSession.*;

public class NetworkShareSurfaceTest {
    private static final String SESSION="a".repeat(64),ID="yj:"+"b".repeat(64);
    private NetworkShareSurface.Snapshot value(Phase phase,Upstream upstream,boolean available){
        return new NetworkShareSurface.Snapshot(SESSION,ID,"Phone",phase,upstream,available,1000);
    }
    @Test public void expiresPeerAndCannotControlAnotherDevice(){
        var snapshot=value(Phase.STOPPED,Upstream.WIFI,true);
        assertEquals(1,snapshot.state(1000));assertTrue(snapshot.controls(ID,8000));
        assertFalse(snapshot.controls(ID,8001));assertEquals(0,snapshot.state(999));
        assertFalse(snapshot.controls("yj:"+"c".repeat(64),1000));
    }
    @Test public void advertisesOnlyActualAvailabilityAndConfirmedUpstream(){
        assertEquals(0,value(Phase.STOPPED,Upstream.WIFI,false).state(1000));
        assertEquals(0,value(Phase.FAILED,null,false).state(1000));
        assertEquals(2,value(Phase.STARTING,Upstream.CELLULAR,true).state(1000));
        assertEquals(3,value(Phase.ACTIVE,Upstream.CELLULAR,true).state(1000));
        assertEquals(4,value(Phase.ACTIVE,Upstream.WIFI,true).state(1000));
        assertEquals(2,value(Phase.STOPPING,Upstream.WIFI,false).state(1000));
        assertThrows(IllegalArgumentException.class,()->value(Phase.ACTIVE,null,true));
    }
}
