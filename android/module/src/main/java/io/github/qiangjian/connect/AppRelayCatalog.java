package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Native candidate metadata only. No task ID, launch receipt, document title or icon bytes. */
final class AppRelayCatalog {
    enum Kind { APP, BROWSER, DOCUMENT, RECENT, CAST }
    record Candidate(String packageName, String label, Kind kind, boolean visible,
                     boolean advertisesDataRelay, int nativeDestinationType, String activity) {
        Candidate {
            AppRelayRequest.packageName(packageName);
            label = text(label, 256); activity = text(activity, 512);
            if (kind == null || nativeDestinationType < 0 || nativeDestinationType > 255)
                throw new IllegalArgumentException("invalid_relay_candidate");
            if (advertisesDataRelay && (!visible || kind == Kind.RECENT || kind == Kind.CAST))
                throw new IllegalArgumentException("invalid_relay_support");
        }
        // A directory entry cannot establish that the receiver can execute the handoff.
        boolean eligible(boolean nativePeerBound, boolean localTargetSupported) {
            return visible && advertisesDataRelay && nativePeerBound && localTargetSupported;
        }
        @Override public String toString() { return "RelayCandidate[" + kind + ",visible=" + visible + "]"; }
    }
    record Update(boolean clearAll, List<Candidate> candidates) {
        Update {
            candidates = List.copyOf(candidates);
            if (candidates.size() > 16 || (clearAll && !candidates.isEmpty()))
                throw new IllegalArgumentException("invalid_relay_catalog");
            HashSet<String> seen = new HashSet<>();
            for (Candidate candidate : candidates) if (!seen.add(candidate.packageName()))
                throw new IllegalArgumentException("duplicate_relay_candidate");
        }
    }
    static String text(String value, int limit) {
        if (value == null) return "";
        if (value.getBytes(StandardCharsets.UTF_8).length > limit || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid_relay_metadata");
        return value;
    }
    static void requireClass(Object value, String name) {
        if (value == null || !name.equals(value.getClass().getName())) throw new IllegalArgumentException("invalid_native_relay_type");
    }
    private AppRelayCatalog() {}
}
