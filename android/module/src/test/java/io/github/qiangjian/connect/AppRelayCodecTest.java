package io.github.qiangjian.connect;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelayCodecTest {
    @Test public void nativeReceiptRejectsCoercionExtraFieldsAndImpossibleTaskStates() throws Exception {
        JSONObject result = AppRelayCodec.encode(new AppRelayTasks.Result("synthetic", AppRelayTasks.State.COMPLETED, "foreground_observed", "test.synthetic.app", 123));
        assertEquals(123, AppRelayCodec.decodeResult(result).taskId());
        result.put("task_id", "123"); assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decodeResult(result));
        result.put("task_id", -1); assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decodeResult(result));
        result.put("task_id", 123).put("extra", true); assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decodeResult(result));
    }
    private AppRelayRequest request() {
        return new AppRelayRequest("synthetic", "test.synthetic.app", AppRelayRequest.Kind.BROWSER,
                List.of(), "https://example.invalid/relay-test", "", "", "", 0, 0, "");
    }
    @Test public void strictRoundTripAndActionIdentity() throws Exception {
        JSONObject value = AppRelayCodec.encode(request());
        assertEquals(request(), AppRelayCodec.decode(new JSONObject(value.toString())));
        JSONObject action = new JSONObject().put("action", "prepare").put("request_id", "synthetic").put("handoff", value);
        AppRelayCodec.validateAction(action);
        action.put("request_id", "different");
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(action));
    }
    @Test public void refusesCoercedNumbersNullsAndExtraActions() throws Exception {
        JSONObject value = AppRelayCodec.encode(request()).put("version", "1");
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decode(value));
        value.put("version", 1).put("progress", 0.5);
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decode(value));
        value.put("progress", 0).put("uri", JSONObject.NULL);
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.decode(value));
        JSONObject action = new JSONObject().put("action", "launch").put("request_id", "synthetic").put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(action));
        assertThrows(IllegalArgumentException.class, () -> AppRelayCodec.validateAction(new JSONObject()));
    }
}
