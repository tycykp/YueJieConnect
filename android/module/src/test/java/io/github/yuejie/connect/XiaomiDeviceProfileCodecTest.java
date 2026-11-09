package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class XiaomiDeviceProfileCodecTest {
    @Test public void producesSampleResponseWithoutConfusingPluggedWithBoolean() {
        assertArrayEquals(new byte[]{1,(byte)128,73,2,0,0,0,0,0,0},
            XiaomiDeviceProfileCodec.response(new byte[]{0,(byte)128},73,2));
        assertArrayEquals(new byte[]{1,0},XiaomiDeviceProfileCodec.response(new byte[]{0,0},100,0));
        assertEquals(8,XiaomiDeviceProfileCodec.response(new byte[]{0,(byte)128},1,8)[3]);
    }
    @Test public void refusesUnknownMasksMalformedRequestsAndUnavailableBattery() {
        for(byte[] request:new byte[][]{null,{}, {0},{1,(byte)128},{0,(byte)129},{0,(byte)128,0}})
            assertFalse(XiaomiDeviceProfileCodec.accepts(request));
        for(int[] reading:new int[][]{{-1,0},{101,0},{50,-1},{50,3}})
            assertThrows(IllegalArgumentException.class,()->XiaomiDeviceProfileCodec.response(new byte[]{0,(byte)128},reading[0],reading[1]));
    }
}
