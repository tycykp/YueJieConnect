package io.github.qiangjian.connect;

/** Negotiated limits, not a claim that a device has a compatible codec or capture permission. */
public record ScreenCastFormat(int width, int height, int fps, int bitrate) {
    public static final String MIME = "video/avc";
    public ScreenCastFormat {
        if (width < 16 || height < 16 || width > 4096 || height > 4096
                || (width & 1) != 0 || (height & 1) != 0 || (long) width * height > 8_847_360
                || fps < 1 || fps > 60 || bitrate < 64_000 || bitrate > 30_000_000)
            throw new IllegalArgumentException("invalid_video_format");
    }
}
