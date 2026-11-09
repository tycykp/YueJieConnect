package io.github.yuejie.connect;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.Test;
import static org.junit.Assert.*;

public class XiaomiPadNfcTagTest {
    // Factory record from Pad 8 Pro with its device address replaced by a test address.
    private static byte[] payload() {
        return HexFormat.of().parseHex("0a510801100d2201032a094d492d4e4643544147380f4a37271700000008000e"
                + "5441475f444953434f564552454465064d4952524f520111"
                + "31303a32303a33303a34303a35303a3630" + "7901015a01016a02fa7f");
    }

    @Test public void readsFactoryRecordAndBindsItsAddress() {
        assertEquals(XiaomiPadNfcTag.targetForAddress("10:20:30:40:50:60"), XiaomiPadNfcTag.target(payload()));
        assertNotEquals(XiaomiPadNfcTag.targetForAddress("10:20:30:40:50:61"), XiaomiPadNfcTag.target(payload()));
        assertEquals(XiaomiPadNfcTag.targetForAddress("10:20:30:40:50:6a"),
                XiaomiPadNfcTag.targetForAddress("10:20:30:40:50:6A"));
    }

    @Test public void rejectsOtherActionsAndInvalidRecords() {
        byte[] record = payload();
        assertNull(XiaomiPadNfcTag.target(Arrays.copyOf(record, record.length - 1)));
        record[48] = 'X';
        assertNull(XiaomiPadNfcTag.target(record));
        assertNull(XiaomiPadNfcTag.targetForAddress("02:00:00:00:00:00"));
    }
}
