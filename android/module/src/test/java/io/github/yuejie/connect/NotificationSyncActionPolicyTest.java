package io.github.yuejie.connect;

import static org.junit.Assert.assertThrows;
import java.util.List;
import org.junit.Test;

public class NotificationSyncActionPolicyTest {
    private NotificationSyncCodec.Post post(boolean ongoing, boolean openable) {
        return new NotificationSyncCodec.Post("synthetic", "test", "", "", 1, ongoing, openable, new byte[0], new byte[0],
                List.of(new NotificationSyncCodec.Button("1", "normal", false), new NotificationSyncCodec.Button("3", "reply", true)));
    }
    @Test public void unadvertisedButtonCannotBeGuessedFromNativeArrayIndex() {
        var post = post(false, true);
        NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("1", ""));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("0", "")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("2", "")));
    }
    @Test public void replyCannotBeConvertedIntoUnintendedButtonPress() {
        var post = post(false, true);
        NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("3", "人工回复"));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("3", "")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post, new NotificationSyncCodec.Action("1", "人工回复")));
    }
    @Test public void openAndDismissRespectAdvertisedFlagsAndRejectReplyData() {
        NotificationSyncActionPolicy.requireAdvertised(post(false, true), new NotificationSyncCodec.Action("open", ""));
        NotificationSyncActionPolicy.requireAdvertised(post(false, true), new NotificationSyncCodec.Action("dismiss", ""));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post(true, true), new NotificationSyncCodec.Action("dismiss", "")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post(false, false), new NotificationSyncCodec.Action("open", "")));
        assertThrows(IllegalArgumentException.class, () -> NotificationSyncActionPolicy.requireAdvertised(post(false, true), new NotificationSyncCodec.Action("dismiss", "text")));
    }
}
