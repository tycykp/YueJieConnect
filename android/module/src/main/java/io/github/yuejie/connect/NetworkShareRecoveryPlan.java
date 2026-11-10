package io.github.yuejie.connect;

import java.util.List;
import java.util.Objects;

/** Decisions use a complete current system snapshot, never a remembered networkId alone. */
final class NetworkShareRecoveryPlan {
    record Config(int id, String ssid, String credential, String fingerprint, int creatorUid,
            String creatorPackage, boolean wpa2Only) {
        @Override public String toString() { return "NetworkRecoveryConfig[private network redacted]"; }
    }
    record Consumer(int remove, int reconnect, boolean restored, String error) {}
    record ProviderProgress(boolean issued, boolean recovered, boolean persistedSettled, boolean startSettled,
            boolean startFailed, boolean stopRequested, boolean stopAcknowledged, boolean observedAfterStop) {}
    record ProviderSnapshot(boolean received, boolean owned, boolean exclusive, boolean originalWifiEnabled, boolean identitiesKnown) {
        ProviderSnapshot(boolean received, boolean owned, boolean exclusive, boolean originalWifiEnabled) {
            this(received, owned, exclusive, originalWifiEnabled, true);
        }
    }
    record Provider(boolean stop, boolean restored) {}
    static Provider provider(ProviderProgress progress, ProviderSnapshot snapshot) {
        if (!progress.issued() && !progress.recovered()) return new Provider(false, true);
        boolean stop = snapshot.received() && snapshot.identitiesKnown() && snapshot.owned() && snapshot.exclusive() && !progress.stopRequested();
        boolean settled = progress.recovered() ? progress.persistedSettled() : progress.startSettled();
        boolean stopped = progress.startFailed() || progress.stopAcknowledged() && progress.observedAfterStop();
        boolean restored = snapshot.received() && snapshot.identitiesKnown() && settled && !snapshot.owned()
                && (progress.recovered() && !progress.stopRequested() || stopped);
        // We own the synthetic AP, not the user's Wi-Fi switch. A user switching
        // to cellular must not leave a verified-removed AP permanently in recovery.
        return new Provider(stop, restored);
    }
    static Consumer consumer(NetworkShareRecovery.Entry entry, List<Config> configs, int connected, String connectedSsid) {
        Config own = null, previous = null;
        for (Config config : configs) {
            if (config.ssid() == null || config.ssid().isEmpty() || "<unknown ssid>".equals(config.ssid()))
                return new Consumer(-1, -1, false, "network_recovery_snapshot_incomplete");
            if (config.id() == entry.previousNetwork()
                    && Objects.equals(config.fingerprint(), entry.previousFingerprint())) previous = config;
            if (!entry.ssid().equals(config.ssid())) continue;
            boolean credentialMatches = config.credential() == null || "*".equals(config.credential())
                    || entry.passphrase().equals(config.credential());
            if (own != null || !config.wpa2Only() || config.creatorUid() != entry.ownerUid()
                    || !entry.ownerPackage().equals(config.creatorPackage()) || !credentialMatches
                    || entry.addedNetwork() >= 0 && config.id() != entry.addedNetwork())
                return new Consumer(-1, -1, false, "network_recovery_resource_conflict");
            own = config;
        }
        if (connected >= 0 && (connectedSsid == null || connectedSsid.isEmpty() || "<unknown ssid>".equals(connectedSsid)))
            return new Consumer(own == null ? -1 : own.id(), -1, false, "network_restore_connection_unobservable");
        boolean connectedOwn = entry.ssid().equals(connectedSsid);
        if (connectedOwn && (own != null && connected != own.id()
                || own == null && entry.addedNetwork() >= 0 && connected != entry.addedNetwork()))
            return new Consumer(-1, -1, false, "network_recovery_resource_conflict");
        // A different current native network is an observed user/system choice; leave it alone.
        boolean externalChoice = connected >= 0 && !connectedOwn;
        int reconnect = -1;
        if (entry.consumerChanged() && !externalChoice && entry.previousNetwork() >= 0) {
            if (previous == null) return new Consumer(own == null ? -1 : own.id(), -1, false,
                    "network_restore_previous_changed");
            reconnect = previous.id();
        }
        boolean restored = own == null && !connectedOwn && (!entry.consumerChanged() || externalChoice
                || entry.previousNetwork() == -1 && connected == -1);
        return new Consumer(own == null ? -1 : own.id(), reconnect, restored, null);
    }
    private NetworkShareRecoveryPlan() {}
}
