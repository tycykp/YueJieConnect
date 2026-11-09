package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AppRelayRequestTest {
    private AppRelayRequest app(String uri) {
        return new AppRelayRequest("synthetic", "com.example.synthetic", AppRelayRequest.Kind.APP,
                List.of("com.example.synthetic"), uri, "a".repeat(64), "", "", 0, 0, "");
    }
    @Test public void rejectsSerializedIntentAndSensitiveSchemes() {
        for (String uri : List.of("intent://x#Intent;component=evil/.Act;end", "content://private/x", "file:///data/x", "javascript:alert(1)", "tel:123", "https://user:password@example.invalid"))
            assertThrows(IllegalArgumentException.class, () -> app(uri));
    }
    @Test public void appNeedsSigner() {
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.APP,
                List.of("com.example.synthetic"), "synthetic://test", "", "", "", 0, 0, ""));
    }
    @Test public void targetsAreImmutable() {
        List<String> targets = new ArrayList<>(List.of("com.example.synthetic"));
        AppRelayRequest r = new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.HOME, targets, "", "", "", "", 0, 0, "");
        targets.clear(); assertEquals(1, r.targets().size());
        assertThrows(UnsupportedOperationException.class, () -> r.targets().clear());
    }
    @Test public void documentUsesTransferTokenNotRemoteUri() {
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.DOCUMENT,
                List.of("com.example.synthetic"), "", "", "content://remote/private", "text/plain", 0, 0, ""));
    }
    @Test public void browserMustBeNetworkUri() {
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.BROWSER,
                List.of(), "synthetic://test", "", "", "", 0, 0, ""));
    }
    @Test public void logDoesNotExposeUriOrToken() { assertFalse(app("synthetic://private?token=secret").toString().contains("secret")); }
    @Test public void validatesRequestAndTargets() {
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("bad/id", "com.example.synthetic", AppRelayRequest.Kind.HOME,
                List.of("com.example.synthetic"), "", "", "", "", 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new AppRelayRequest("a", "com.example.synthetic", AppRelayRequest.Kind.HOME,
                List.of("com.example.synthetic", "com.example.synthetic"), "", "", "", "", 0, 0, ""));
    }
}
