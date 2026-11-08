package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OplusRelayControlTest {
    private static final String PKG = "test.synthetic.app";
    private JSONObject profile(String key, String value) throws Exception { return new JSONObject().put("key", key).put("value", value); }
    private JSONArray data() throws Exception {
        return new JSONArray().put(profile(OplusRelayPayload.PKG, PKG)).put(profile(OplusRelayPayload.TYPE, "5"))
                .put(profile("Relay_Extend_Key_Doc_Title", "Synthetic"));
    }
    @Test public void nativeConfirmationIsDataOnlyAndSdkRequestTargetsActualAppId() throws Exception {
        byte[] confirm = OplusRelayControl.confirmation("Synthetic peer");
        assertEquals(503, ((confirm[0]&255)<<8)|(confirm[1]&255)); assertEquals(9, confirm[2]);
        JSONArray profiles = new JSONArray(new String(confirm, 7, confirm.length - 7, StandardCharsets.UTF_8));
        assertTrue(profiles.toString().contains("Relay_Extend_Key_Relay_Type"));
        assertEquals("com.oplus.linker", OplusRelayControl.sourcePackage(profiles.toString()));
        String request = OplusRelayControl.request(PKG, 1008, "Synthetic peer");
        assertEquals(PKG, OplusRelayControl.sourcePackage(request));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayControl.request(PKG, -1, "Synthetic peer"));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayControl.request(PKG, 65536, "Synthetic peer"));
    }
    @Test public void realSdkProfileGetsOneVerifiedHeaderWithoutLosingData() throws Exception {
        String json = data().toString();
        OplusRelayPayload payload = OplusRelayPayload.decode(OplusRelayControl.sdkData(json, PKG, 1008));
        assertEquals(1008, payload.appId()); assertEquals(5, payload.type()); assertTrue(payload.json().contains("Synthetic"));
        assertEquals(4, payload.profiles().length());
        JSONArray suppliedId = data().put(profile(OplusRelayPayload.ID, "1008"));
        assertEquals(4, OplusRelayPayload.decode(OplusRelayControl.sdkData(suppliedId.toString(), PKG, 1008)).profiles().length());
    }
    @Test public void sdkCannotRedirectPackageInjectControlsOrSpoofAppId() throws Exception {
        assertThrows(SecurityException.class, () -> OplusRelayControl.sdkData(data().toString(), "test.synthetic.other", 1008));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayControl.sdkData(data().put(profile(OplusRelayPayload.ID, "503")).toString(), PKG, 1008));
        JSONArray control = new JSONArray().put(profile(OplusRelayPayload.PKG, PKG)).put(profile(OplusRelayPayload.TYPE, "8"));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayControl.sdkData(control.toString(), PKG, 1008));
        assertThrows(IllegalArgumentException.class, () -> OplusRelayControl.sdkData(data().put(profile(OplusRelayPayload.TYPE, "5")).toString(), PKG, 1008));
    }
}
