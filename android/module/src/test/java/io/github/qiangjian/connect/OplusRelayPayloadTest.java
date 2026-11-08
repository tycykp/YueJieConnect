package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OplusRelayPayloadTest {
    static String profile(String pkg, int type) throws Exception {
        JSONArray list = new JSONArray();
        list.put(new JSONObject().put("key", OplusRelayPayload.PKG).put("value", pkg));
        list.put(new JSONObject().put("key", OplusRelayPayload.ID).put("value", OplusRelayPayload.DEMO01.equals(pkg) ? "201" : "200"));
        list.put(new JSONObject().put("key", OplusRelayPayload.TYPE).put("value", Integer.toString(type)));
        list.put(new JSONObject().put("key", "Relay_Extend_Key_Doc_Title").put("value", "Synthetic title"));
        list.put(new JSONObject().put("key", "Relay_Extend_Key_Doc_Data").put("value", "<b>Synthetic</b>\n正文"));
        return list.toString();
    }
    private byte[] wire(String body, int type) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8), result = new byte[text.length + 7];
        result[1] = (byte) 200; result[2] = (byte) type; System.arraycopy(text, 0, result, 7, text.length); return result;
    }
    @Test public void documentAndRichTextKeepExactProfilesRatherThanDeepLink() throws Exception {
        String json = profile(OplusRelayPayload.DEMO, 6);
        OplusRelayPayload payload = OplusRelayPayload.decode(wire(json, 6));
        assertEquals(json, payload.json()); assertTrue(payload.richText());
        assertEquals("com.example.datarelaydemo.ui.EditActivity", payload.activity());
        AppRelayRequest request = new AppRelayRequest("synthetic-rich", OplusRelayPayload.DEMO, AppRelayRequest.Kind.RICH_TEXT,
                List.of(OplusRelayPayload.DEMO), "", "a".repeat(64), "", "", 0, 0, "", json);
        assertEquals(request, AppRelayCodec.decode(AppRelayCodec.encode(request)));
        assertFalse(request.toString().contains("Synthetic")); assertFalse(payload.toString().contains("正文"));
        assertEquals("com.example.datarelaydemo.ui.EditActivity01", OplusRelayPayload.richText(profile(OplusRelayPayload.DEMO01, 0), OplusRelayPayload.DEMO01).activity());
    }
    @Test public void headerAndProfileIdentityMustMatchAndCastTypesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.decode(wire(profile(OplusRelayPayload.DEMO, 6), 5)));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.decode(wire(profile(OplusRelayPayload.DEMO, 2), 2)));
        byte[] bad = wire(profile(OplusRelayPayload.DEMO, 6), 6); bad[3] = 1;
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.decode(bad));
        assertThrows(Exception.class, () -> OplusRelayPayload.decode(new byte[]{0, (byte)200, 6, 0, 0, 0, 0, (byte)0xc3, 0x28}));
    }
    @Test public void noCoercionDuplicatesOrArbitraryTarget() throws Exception {
        JSONArray json = new JSONArray(profile(OplusRelayPayload.DEMO, 6)); json.put(json.getJSONObject(0));
        String duplicated = json.toString();
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.decode(wire(duplicated, 6)));
        json = new JSONArray(profile(OplusRelayPayload.DEMO, 6)); json.getJSONObject(1).put("value", 200);
        String coerced = json.toString();
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.decode(wire(coerced, 6)));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.richText(profile("test.synthetic.other", 6), "test.synthetic.other"));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayPayload.richText(profile(OplusRelayPayload.DEMO01, 6).replace("201", "200"), OplusRelayPayload.DEMO01));
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("synthetic", OplusRelayPayload.DEMO, AppRelayRequest.Kind.RICH_TEXT,
                List.of("test.synthetic.other"), "", "a".repeat(64), "", "", 0, 0, "", profile(OplusRelayPayload.DEMO, 6)));
    }
    @Test public void nativeDataCannotEscapeItsKindOrBinderBudget() throws Exception {
        String profile = profile(OplusRelayPayload.DEMO, 6);
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("synthetic", OplusRelayPayload.DEMO, AppRelayRequest.Kind.HOME,
                List.of(OplusRelayPayload.DEMO), "", "", "", "", 0, 0, "", profile));
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("synthetic", OplusRelayPayload.DEMO, AppRelayRequest.Kind.RICH_TEXT,
                List.of(OplusRelayPayload.DEMO), "", "a".repeat(64), "", "", 0, 0, "", "x".repeat(4097)));
    }
}
