package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class MediaControlWireTest {
    static final String TOKEN = "b57bf9cf-af6b-48a2-a192-e335da1843f6";
    static JSONObject state() throws Exception {
        return new JSONObject().put("media_token", TOKEN).put("playback", 2).put("position_ms", 2000L)
                .put("actions", 511L).put("volume", 4).put("min_volume", 0).put("max_volume", 15)
                .put("interactive", true).put("power_save", false);
    }
    static JSONObject result() throws Exception {
        return new JSONObject().put("status", "ok").put("code", "ok").put("dispatched", false).put("state", state());
    }
    static JSONObject play() throws Exception {
        return new JSONObject().put("action", "PLAY").put("package", "test.synthetic.player").put("media_token", TOKEN);
    }
    @Test public void strictRequestRejectsWrongNumericTypesAndUnrelatedFields() throws Exception {
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(new JSONObject("{\"action\":\"SET_VOLUME\",\"value\":3.0}")));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(new JSONObject("{\"action\":\"SET_VOLUME\",\"value\":\"3\"}")));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(play().put("title", "synthetic")));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(play().put("value", 7)));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(new JSONObject("{\"action\":\"VOLUME_QUERY\",\"package\":\"test.synthetic.player\"}")));
    }
    @Test public void conditionalRestoreAndExactTokenAreMandatory() throws Exception {
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(new JSONObject("{\"action\":\"RESTORE_VOLUME\",\"value\":3}")));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(new JSONObject("{\"action\":\"SET_VOLUME\",\"value\":3,\"expected_volume\":-1}")));
        assertThrows(Exception.class, () -> MediaControlEndpoint.decode(play().put("media_token", "local-package-is-not-a-token")));
        assertEquals(MediaControlSession.Action.PLAY, MediaControlEndpoint.decode(play()).action());
    }
    @Test public void everySupportedActionRoundTripsWithoutUnrelatedValues() throws Exception {
        for (MediaControlSession.Action action : MediaControlSession.Action.values()) {
            boolean media = MediaControlSession.isMedia(action);
            var request = new MediaControlSession.Request(action, media ? "test.synthetic.player" : "", media ? TOKEN : "", 3,
                    action == MediaControlSession.Action.RESTORE_VOLUME ? 4 : -1);
            assertEquals(action, MediaControlEndpoint.decode(MediaControlWire.request(request)).action());
        }
    }
    @Test public void strictResultsRejectFabricatedStatusAndLeakedMetadata() throws Exception {
        assertThrows(Exception.class, () -> MediaControlWire.result(result().put("code", "result_unconfirmed")));
        assertThrows(Exception.class, () -> MediaControlWire.result(result().put("state", state().put("title", "synthetic"))));
        assertThrows(Exception.class, () -> MediaControlWire.result(result().put("dispatched", "false")));
        assertThrows(Exception.class, () -> MediaControlWire.result(result().put("state", state().put("volume", 99))));
        assertThrows(Exception.class, () -> MediaControlWire.result(result().put("state", state().put("playback", 12))));
        assertFalse(MediaControlWire.confirmed(new JSONObject().put("status", "ok").put("code", "ok")
                .put("dispatched", false).put("state", new JSONObject())));
    }
    @Test public void strictResultsRequireStateAndPreserveObservedValues() throws Exception {
        JSONObject missing = result(); missing.remove("state");
        assertThrows(Exception.class, () -> MediaControlWire.result(missing));
        var parsed = MediaControlWire.result(result());
        assertTrue(parsed.confirmed()); assertFalse(parsed.dispatched());
        assertEquals(TOKEN, parsed.state().token()); assertEquals(4, parsed.state().volume());
    }
    @Test public void everyWireOperationMustIdentifyItsOriginalCastStreamAndScreen() throws Exception {
        var scope = new MediaControlNativeRoute.Scope("projection1", "a".repeat(64), "native-peer", 7, 3);
        var parsed = MediaControlWire.payload(MediaControlWire.payload(scope, MediaControlEndpoint.decode(play())));
        assertEquals(7, parsed.streamId()); assertEquals(3, parsed.screenId());
        assertThrows(Exception.class, () -> MediaControlWire.payload(play()));
        assertThrows(Exception.class, () -> MediaControlWire.payload(new JSONObject().put("cast_stream_id", 0).put("cast_screen_id", 3).put("control", play())));
        assertThrows(Exception.class, () -> MediaControlWire.payload(new JSONObject().put("cast_stream_id", 7.0).put("cast_screen_id", 3).put("control", play())));
        assertThrows(Exception.class, () -> MediaControlWire.payload(new JSONObject().put("cast_stream_id", 7).put("cast_screen_id", -1).put("control", play())));
    }
}
