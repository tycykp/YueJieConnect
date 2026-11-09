package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalDeviceOperationsTest {
    @Test public void chargingUsesBatteryStatusAndPreservesUnknown() {
        assertEquals(Boolean.TRUE,LocalDeviceOperations.charging(2));
        assertEquals(Boolean.FALSE,LocalDeviceOperations.charging(3));
        assertEquals(Boolean.FALSE,LocalDeviceOperations.charging(4));
        assertEquals(Boolean.FALSE,LocalDeviceOperations.charging(5));
        assertNull(LocalDeviceOperations.charging(1));
        assertNull(LocalDeviceOperations.charging(-1));
    }
}
