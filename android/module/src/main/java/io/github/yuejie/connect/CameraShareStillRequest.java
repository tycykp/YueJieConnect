package io.github.yuejie.connect;

/** One authorized still exposure. Native camera ids and source Camera2 ids are separate identities. */
record CameraShareStillRequest(String sessionId, String peerId, long streamId, String requestId,
                              String cameraId, int width, int height, int orientation) {
    static final int MAX_JPEG_BYTES = 16 * 1024 * 1024;

    CameraShareStillRequest {
        identity(sessionId); identity(peerId); identity(requestId); identity(cameraId);
        if (streamId <= 0 || width < 1 || height < 1 || width > 8192 || height > 8192
                || (long) width * height > 32_000_000L
                || (orientation != 0 && orientation != 90 && orientation != 180 && orientation != 270))
            throw new IllegalArgumentException("invalid_camera_still_request");
    }

    private static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("invalid_camera_identity");
    }

    /** Keep for the source's business-session lifetime; do not evict completed ids into replayability. */
    static final class History {
        private record Key(String session, String request) { }
        private final java.util.Set<Key> seen = new java.util.HashSet<>();
        synchronized boolean claim(CameraShareStillRequest request) {
            Key key = new Key(request.sessionId(), request.requestId());
            if (seen.contains(key)) return false;
            if (seen.size() >= 128) throw new IllegalStateException("camera_still_request_capacity");
            return seen.add(key);
        }
    }
}
