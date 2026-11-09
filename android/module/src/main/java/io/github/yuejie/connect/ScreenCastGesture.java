package io.github.yuejie.connect;

/** Keeps unsupported multi-touch and mouse hover out of the single-pointer wire lane. */
final class ScreenCastGesture {
    private boolean down;

    boolean accept(int action) {
        if (action == 0) {
            if (down) return false;
            down = true;
            return true;
        }
        if (!down || action < 1 || action > 3) return false;
        if (action == 1 || action == 3) down = false;
        return true;
    }

    void reset() { down = false; }
    boolean held() { return down; }
}
