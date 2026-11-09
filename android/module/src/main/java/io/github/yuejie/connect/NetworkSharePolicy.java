package io.github.yuejie.connect;

import java.util.Map;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** OEM status encodings from the reviewed phone/pad samples; never derive them from LAN reachability. */
final class NetworkSharePolicy {
    record Consent(Upstream upstream, boolean meteredAllowed) {
        Consent {
            if (upstream == null || upstream == Upstream.CELLULAR && !meteredAllowed)
                throw new IllegalArgumentException("network_consent_required");
        }
    }
    static int xiaomi(Phase phase, Upstream upstream, boolean available) {
        if (phase == Phase.ACTIVE) return upstream == Upstream.CELLULAR ? 3 : 4;
        if (phase == Phase.REQUESTED || phase == Phase.STARTING || phase == Phase.READY || phase == Phase.STOPPING) return 2;
        if (phase == Phase.FAILED || phase == Phase.RECOVERY_REQUIRED) return 5;
        return available ? 1 : 0;
    }
    static Map<String, String> oplus(Phase phase, boolean available) {
        String card = !available || phase == Phase.RECOVERY_REQUIRED ? "0" : switch (phase) {
            case ACTIVE -> "3";
            case REQUESTED, STARTING, READY -> "2";
            case STOPPING -> "4";
            default -> "1";
        };
        // Until restoration is verified, do not overwrite the last actual sharing state with "off".
        if (phase == Phase.STOPPING || phase == Phase.RECOVERY_REQUIRED)
            return Map.of("share_network_enable", phase == Phase.RECOVERY_REQUIRED || !available ? "0" : "1", "share_network_for_card", card);
        return Map.of("share_network_enable", available ? "1" : "0", "share_network_for_card", card,
                "share_network", phase == Phase.ACTIVE ? "1" : "0",
                "pad_network_setting_status", phase == Phase.ACTIVE ? "1" : "0");
    }
    private NetworkSharePolicy() {}
}
