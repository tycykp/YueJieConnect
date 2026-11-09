package io.github.yuejie.connect;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Looper;
import org.json.JSONObject;

/** Verified OEM process lifecycle. No executor/capability exists until an actual cast grant does.
 * Shared owner constructs the brand endpoint only AFTER its existing kernel caller proof succeeds.
 * This class does not install a synthetic UI, grant screen permission, or manufacture a consumer. */
final class MediaControlRuntime implements AutoCloseable {
    private final BridgeClient client;
    private final MediaControlEndpoint endpoint;
    private final MediaControlCastRegistry casts = new MediaControlCastRegistry();
    private final MediaControlTasks tasks;
    private final BridgeClient.Subscription projectionListener;
    private BridgeClient.Subscription executor;
    private String session, projection;
    private boolean closed;

    MediaControlRuntime(BridgeClient client, MediaControlEndpoint verifiedEndpoint) {
        requireMain(); this.client = java.util.Objects.requireNonNull(client);
        endpoint = java.util.Objects.requireNonNull(verifiedEndpoint);
        tasks = new MediaControlTasks(client, endpoint, (id, sid, stream, screen, request) ->
                client.hasLocalGrant(MediaControlTasks.OPERATION) && permitted(request)
                        && casts.allowed(id, sid, stream, screen, request));
        projectionListener = client.addProjectionListener(this::projectionChanged);
    }
    /** Original native state is narrowed by actual OS permission/service readiness on EVERY read.
     * Do not construct a grant for a local cast before this authenticated projection is current. */
    MediaControlCastRegistry.Lease bind(MediaControlNativeRoute.Scope scope, Object originalPeer,
            Object originalOwner, String selectedPackage,
            java.util.function.Supplier<MediaControlCastRegistry.NativeState> nativeCast) {
        requireMain(); java.util.Objects.requireNonNull(nativeCast);
        var volume = new MediaControlSession.Request(MediaControlSession.Action.VOLUME_QUERY, "", "", 0, -1);
        var media = selectedPackage == null || selectedPackage.isEmpty() ? null
                : new MediaControlSession.Request(MediaControlSession.Action.QUERY, selectedPackage, "", 0, -1);
        return casts.bind(scope, originalPeer, originalOwner, selectedPackage, () -> {
            JSONObject current = client.projection();
            if (closed || current == null || !scope.sessionId().equals(current.optString("session_id"))
                    || !scope.projectionId().equals(current.optString("id"))
                    || !client.hasLocalGrant(MediaControlTasks.OPERATION)) return null;
            MediaControlCastRegistry.NativeState state = nativeCast.get();
            if (state == null) return null;
            return new MediaControlCastRegistry.NativeState(state.scope(), state.peer(), state.owner(),
                    state.streaming(), state.screenAllowed(), state.volumeAllowed() && permitted(volume),
                    state.mediaAllowed() && media != null && permitted(media), state.selectedPackage());
        });
    }
    private boolean permitted(MediaControlSession.Request request) {
        String permission = MediaControlSession.isMedia(request.action())
                ? Manifest.permission.MEDIA_CONTENT_CONTROL : Manifest.permission.MODIFY_AUDIO_SETTINGS;
        return endpoint.available(request)
                && endpoint.context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private void projectionChanged(JSONObject value) {
        requireMain(); if (closed) return;
        String sid = value == null ? null : value.optString("session_id", null);
        String id = value == null ? null : value.optString("id", null);
        if (sid == null || !sid.matches("[0-9a-f]{64}") || id == null || id.isBlank()) {
            // Preserve tombstones until a genuinely different authenticated session is seen.
            casts.revokeAll(); unregister(); return;
        }
        if (!sid.equals(session) || !id.equals(projection)) {
            casts.revokeAll(); unregister();
            if (!sid.equals(session)) casts.sessionChanged(sid);
            session = sid; projection = id;
        }
        refresh();
    }
    /** Invoke after the real onStreaming grant is bound, after stop/revocation, and on the existing
     * one-second timer. A local cast grant is necessary before peer operation intersection exists. */
    void refresh() {
        requireMain();
        JSONObject current = client.projection();
        boolean currentScope = !closed && current != null && session != null
                && session.equals(current.optString("session_id")) && projection.equals(current.optString("id"));
        if (!currentScope || !client.hasLocalGrant(MediaControlTasks.OPERATION)) {
            casts.revokeAll(); unregister(); return;
        }
        boolean ready = casts.hasActive(this::permitted);
        if (!ready) { casts.revokeAll(); unregister(); return; }
        if (executor == null) executor = client.registerExecutor(MediaControlTasks.OPERATION, tasks);
    }
    private void unregister() {
        if (executor != null) { executor.close(); executor = null; }
        tasks.sessionChanged();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("media_runtime_requires_main");
    }
    @Override public void close() {
        requireMain(); if (closed) return;
        closed = true; projectionListener.close(); casts.close(); unregister(); tasks.close();
    }
}
