package io.github.yuejie.connect;

/** Bounded JPEG header validation without decoding, rotating, retaining or logging a photograph. */
final class CameraShareJpeg {
    record Size(int width, int height) { }

    static Size dimensions(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 12 || jpeg.length > CameraShareStillRequest.MAX_JPEG_BYTES
                || u(jpeg[0]) != 0xff || u(jpeg[1]) != 0xd8
                || u(jpeg[jpeg.length - 2]) != 0xff || u(jpeg[jpeg.length - 1]) != 0xd9)
            throw invalid();
        int cursor = 2;
        Size size = null;
        while (cursor < jpeg.length - 2) {
            if (u(jpeg[cursor++]) != 0xff) throw invalid();
            while (cursor < jpeg.length && u(jpeg[cursor]) == 0xff) cursor++;
            if (cursor >= jpeg.length) throw invalid();
            int marker = u(jpeg[cursor++]);
            // A real camera JPEG must contain a frame header before the first scan.
            if (marker == 0xda) {
                if (size == null || cursor + 2 > jpeg.length) throw invalid();
                int length = word(jpeg, cursor);
                if (length < 6 || cursor + length >= jpeg.length - 2) throw invalid();
                return size;
            }
            if (marker == 0 || marker == 0xd8 || marker == 0xd9 || (marker >= 0xd0 && marker <= 0xd7))
                throw invalid();
            if (marker == 0x01) continue;
            if (cursor + 2 > jpeg.length) throw invalid();
            int length = word(jpeg, cursor);
            if (length < 2 || length > jpeg.length - 2 - cursor) throw invalid();
            // Camera2 JPEG uses baseline/progressive Huffman frames; reject other encodings.
            if (marker == 0xc0 || marker == 0xc2) {
                if (size != null || length < 8 || u(jpeg[cursor + 2]) != 8) throw invalid();
                int height = word(jpeg, cursor + 3), width = word(jpeg, cursor + 5);
                int components = u(jpeg[cursor + 7]);
                if ((components != 1 && components != 3) || length != 8 + 3 * components
                        || width < 1 || height < 1 || width > 8192 || height > 8192
                        || (long) width * height > 32_000_000L) throw invalid();
                size = new Size(width, height);
            } else if ((marker >= 0xc1 && marker <= 0xcf) && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                throw invalid();
            }
            cursor += length;
        }
        throw invalid();
    }

    static void validate(byte[] jpeg, CameraShareStillRequest request) {
        Size size = dimensions(jpeg);
        boolean exact = size.width == request.width() && size.height == request.height();
        // Camera2 may implement JPEG_ORIENTATION by rotating pixels or by EXIF orientation.
        boolean rotated = (request.orientation() == 90 || request.orientation() == 270)
                && size.width == request.height() && size.height == request.width();
        if (!exact && !rotated) throw new IllegalArgumentException("camera_still_size_mismatch");
    }

    private static int u(byte value) { return value & 0xff; }
    private static int word(byte[] value, int at) { return (u(value[at]) << 8) | u(value[at + 1]); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_camera_jpeg"); }
}
