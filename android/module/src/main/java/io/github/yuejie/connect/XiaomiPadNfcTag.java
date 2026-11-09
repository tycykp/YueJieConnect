package io.github.yuejie.connect;

import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Pad 8 Pro factory tag: external NDEF record carrying the Xiaomi mirror handoff. */
final class XiaomiPadNfcTag {
    static final String TYPE = "com.xiaomi.mi_connect_service:externaltype";
    // AttrOps/AttrAdvData envelope, Pad 0x0008, TAG_DISCOVERED, MIRROR, then the 17-byte BT address.
    private static final byte[] BEFORE_ADDRESS = HexFormat.of().parseHex(
            "0a510801100d2201032a094d492d4e4643544147380f4a37271700000008000e"
            + "5441475f444953434f564552454465064d4952524f520111");
    private static final byte[] AFTER_ADDRESS = HexFormat.of().parseHex("7901015a01016a02fa7f");

    static String target(Tag tag) {
        Ndef ndef = tag == null ? null : Ndef.get(tag);
        var message = ndef == null ? null : ndef.getCachedNdefMessage();
        if (message == null) return null;
        NdefRecord record = message.getRecords()[0];
        if (record.getTnf() != NdefRecord.TNF_EXTERNAL_TYPE
                || !TYPE.equals(new String(record.getType(), StandardCharsets.US_ASCII))) return null;
        return target(record.getPayload());
    }

    static String target(byte[] payload) {
        int end = BEFORE_ADDRESS.length + 17;
        if (payload.length != end + AFTER_ADDRESS.length
                || !Arrays.equals(payload, 0, BEFORE_ADDRESS.length, BEFORE_ADDRESS, 0, BEFORE_ADDRESS.length)
                || !Arrays.equals(payload, end, payload.length, AFTER_ADDRESS, 0, AFTER_ADDRESS.length)) return null;
        return targetForAddress(new String(payload, BEFORE_ADDRESS.length, 17, StandardCharsets.US_ASCII));
    }

    static String targetForAddress(String address) {
        if (address == null || !address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")) return null;
        String normalized = address.toUpperCase(Locale.ROOT);
        if (normalized.equals("02:00:00:00:00:00") || normalized.equals("00:00:00:00:00:00")) return null;
        return FileTransferEngine.hex(FileTransferEngine.sha256().digest(normalized.getBytes(StandardCharsets.US_ASCII)));
    }
}
