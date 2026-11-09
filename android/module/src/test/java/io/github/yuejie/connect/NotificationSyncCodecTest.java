package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class NotificationSyncCodecTest {
    private NotificationSyncCodec.Post post(String title) {
        return new NotificationSyncCodec.Post("人工通知", title, "合成文本🔔", "多行\n内容", 123L, false, true,
                new byte[]{1, 2}, new byte[]{3}, List.of(new NotificationSyncCodec.Button("0", "回复", true)));
    }
    @Test public void unicodeIconsAndActionsRoundTripWithoutExposingMutableArrays() {
        var original = NotificationSyncCodec.posted("synthetic_a", 1, post("标题"));
        var decoded = NotificationSyncCodec.decode(NotificationSyncCodec.encode(original));
        assertEquals(original.post().text(), decoded.post().text());
        assertEquals(original.post().buttons(), decoded.post().buttons());
        assertArrayEquals(original.post().icon(), decoded.post().icon());
        byte[] changed = decoded.post().icon(); changed[0] = 99;
        assertEquals(1, decoded.post().icon()[0]);
    }
    @Test public void framingRejectsTrailingTruncationOversizeAndMalformedUtf8() {
        byte[] good = NotificationSyncCodec.encode(NotificationSyncCodec.removed("a", 1));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.decode(Arrays.copyOf(good, good.length + 1)));
        for (int i = 0; i < good.length; i++) {
            byte[] truncated = Arrays.copyOf(good, i);
            assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.decode(truncated));
        }
        byte[] bad = good.clone(); bad[9] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.decode(bad));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.decode(new byte[NotificationSyncCodec.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> post("\ud800"));
    }
    @Test public void boundsAndDuplicateActionIdsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> post("a".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> new NotificationSyncCodec.Post("test", "", "", "", 1, false, false,
                new byte[8193], new byte[0], List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NotificationSyncCodec.Post("test", "", "", "", 1, false, false,
                new byte[0], new byte[0], List.of(new NotificationSyncCodec.Button("0", "a", false), new NotificationSyncCodec.Button("0", "b", true))));
    }
    @Test public void receiptRequiresExactTargetRevisionAndActualEffect() {
        var request = NotificationSyncCodec.posted("key", 2, post("test"));
        NotificationSyncCodec.checkReceipt(request, NotificationSyncCodec.encode(NotificationSyncCodec.receipt(request, "posted")));
        var other = NotificationSyncCodec.posted("other", 2, post("test"));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.checkReceipt(request, NotificationSyncCodec.encode(NotificationSyncCodec.receipt(other, "posted"))));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.checkReceipt(request, NotificationSyncCodec.encode(NotificationSyncCodec.receipt(request, "removed"))));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncCodec.receipt(request, "success"));
    }
    @Test public void actionRoundTripRequiresExplicitEffectReceipt() {
        var action = NotificationSyncCodec.action("key", 7, "0", "人工回复");
        assertEquals(action, NotificationSyncCodec.decode(NotificationSyncCodec.encode(action)));
        NotificationSyncCodec.checkReceipt(action, NotificationSyncCodec.encode(NotificationSyncCodec.receipt(action, "pending_intent_finished")));
        var dismiss = NotificationSyncCodec.action("key", 7, "dismiss", "");
        NotificationSyncCodec.checkReceipt(dismiss, NotificationSyncCodec.encode(NotificationSyncCodec.receipt(dismiss, "removed")));
    }
    @Test public void updatesAreCommittedOnlyAfterVerifiedEffectAndTombstonesRejectResurrection() {
        var ledger = new NotificationSyncLedger();
        var first = NotificationSyncCodec.posted("key", 1, post("v1"));
        assertEquals(NotificationSyncLedger.Decision.APPLY, ledger.check(first));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("key", 1));
        ledger.commit(first); ledger.requireActive("key", 1);
        assertEquals(NotificationSyncLedger.Decision.DUPLICATE, ledger.check(first));
        var conflict = NotificationSyncCodec.posted("key", 1, post("different"));
        assertThrows(IllegalStateException.class, () -> ledger.check(conflict));
        ledger.commit(NotificationSyncCodec.removed("key", 2));
        assertThrows(IllegalStateException.class, () -> ledger.check(first));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("key", 1));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("key", 2));
        var fresh = NotificationSyncCodec.posted("key", 3, post("v3"));
        ledger.commit(fresh); ledger.requireActive("key", 3);
    }
    @Test public void capacityDoesNotEvictTombstonesAndSessionResetClearsTargets() {
        var ledger = new NotificationSyncLedger();
        for (int i = 0; i < 256; i++) ledger.commit(NotificationSyncCodec.removed("k" + i, 1));
        assertThrows(IllegalStateException.class, () -> ledger.check(NotificationSyncCodec.removed("overflow", 1)));
        assertThrows(IllegalStateException.class, () -> ledger.check(NotificationSyncCodec.posted("k0", 1, post("late"))));
        ledger.clear();
        assertEquals(NotificationSyncLedger.Decision.APPLY, ledger.check(NotificationSyncCodec.removed("overflow", 1)));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("k0", 1));
    }
    @Test public void uncertainSideEffectFencesOutOldPostsWithoutGrantingActionAccess() {
        var ledger = new NotificationSyncLedger();
        var first = NotificationSyncCodec.posted("key", 1, post("old")); ledger.commit(first);
        var pending = NotificationSyncCodec.posted("key", 3, post("new")); ledger.reserve(pending);
        assertThrows(IllegalStateException.class, () -> ledger.check(first));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("key", 1));
        assertThrows(IllegalStateException.class, () -> ledger.requireActive("key", 3));
        assertEquals(NotificationSyncLedger.Decision.APPLY, ledger.check(pending));
        ledger.commit(pending); ledger.requireActive("key", 3);
    }
}
