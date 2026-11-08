package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.xiaomi.mirror.message.NotificationPostMessage;
import com.xiaomi.mirror.message.NotificationInteractionMessage;

public class XiaomiNotificationCodecTest {
    @Test public void nativeIdNeverReplacesOpaqueFullKeyBinding() throws Exception {
        var nativePost = new NotificationPostMessage();
        var encoded = XiaomiNotificationCodec.post(nativePost, "opaque_user_tag_key", 4);
        assertEquals("synthetic.package~7", XiaomiNotificationCodec.nativeId(nativePost));
        assertEquals("opaque_user_tag_key", encoded.key()); assertEquals(4, encoded.revision());
        assertEquals("合成内容", encoded.post().text());
    }
    @Test public void iconAndPictureAreNormalizedWithIndependentBudgets() throws Exception {
        var nativePost = new NotificationPostMessage();
        nativePost.smallIcon = new NotificationPostMessage.Bytes(new byte[]{1, 2});
        nativePost.bigPicture = new NotificationPostMessage.Bytes(new byte[]{3, 4});
        List<Integer> budgets = new ArrayList<>();
        var encoded = XiaomiNotificationCodec.post(nativePost, "key", 1, (bytes, limit) -> { budgets.add(limit); return bytes; });
        assertEquals(List.of(2048, 4096), budgets); assertArrayEquals(new byte[]{1, 2}, encoded.post().icon());
        nativePost.smallIcon.declaredSize = 512 * 1024 + 1; nativePost.smallIcon.copied = false;
        assertThrows(IllegalArgumentException.class, () -> XiaomiNotificationCodec.post(nativePost, "key", 1));
        assertFalse(nativePost.smallIcon.copied);
    }
    @Test public void nativeReplyButtonsKeepExactIndexesAndRejectGroupTargets() throws Exception {
        var post = new NotificationPostMessage();
        post.buttons = List.of(new NotificationPostMessage.Button("2", "合成回复", 2));
        var frame = XiaomiNotificationCodec.post(post, "key", 1);
        assertEquals("2", frame.post().buttons().get(0).id()); assertTrue(frame.post().buttons().get(0).reply());
        var action = new NotificationInteractionMessage(); action.type = 1; action.buttonId = "2"; action.replyText = "人工回复";
        assertEquals("人工回复", XiaomiNotificationCodec.interaction(action, "key", 1).action().reply());
        action.type = 4;
        assertThrows(IllegalArgumentException.class, () -> XiaomiNotificationCodec.interaction(action, "key", 1));
        action.type = 5;
        assertThrows(IllegalArgumentException.class, () -> XiaomiNotificationCodec.removed(action, "key", 1));
    }
    @Test public void inboxAndMessagingPreserveNativeTextAndRejectForeignMessageTypes() throws Exception {
        var post = new NotificationPostMessage(); post.style = 4; post.lines = List.of("合成一", "合成二");
        assertEquals("合成一\n合成二", XiaomiNotificationCodec.post(post, "key", 1).post().bigText());
        post.style = 5; post.messages = List.of(new NotificationPostMessage.Message("人工发送者", "合成内容"));
        assertEquals("人工发送者: 合成内容", XiaomiNotificationCodec.post(post, "key", 1).post().bigText());
        assertThrows(IllegalArgumentException.class, () -> XiaomiNotificationCodec.post(new Object(), "key", 1));
    }
}
