package io.github.qiangjian.connect;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Worker-thread control with observed completion. Never equates dispatch with execution. */
final class MediaControlSession {
    enum Action { QUERY, PLAY, PAUSE, STOP, SEEK, TOGGLE, SEEK_FORWARD, SEEK_BACKWARD,
        NEXT, PREVIOUS, FAST_FORWARD, REWIND, VOLUME_QUERY, SET_VOLUME, RESTORE_VOLUME }
    record Request(Action action, String packageName, String token, long value, int expectedVolume) {
        Request {
            Objects.requireNonNull(action);
            packageName = packageName == null ? "" : packageName;
            token = token == null ? "" : token;
            if (packageName.length() > 255 || token.length() > 80) throw new IllegalArgumentException("invalid_target");
            if (isMedia(action) && !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("invalid_target");
            if (isMedia(action) && action != Action.QUERY && token.isEmpty())
                throw new IllegalArgumentException("missing_media_token");
            if (action == Action.SEEK && (value < 0 || value > 604_800_000L))
                throw new IllegalArgumentException("invalid_position");
            if ((action == Action.SET_VOLUME || action == Action.RESTORE_VOLUME)
                    && (value < 0 || value > 10_000 || expectedVolume < -1 || expectedVolume > 10_000))
                throw new IllegalArgumentException("invalid_volume");
            if (action == Action.RESTORE_VOLUME && expectedVolume < 0)
                throw new IllegalArgumentException("missing_expected_volume");
        }
    }
    // No title, artist, queue, artwork or media URI crosses this boundary.
    record State(String token, int playback, long position, long actions, int volume,
                 int minVolume, int maxVolume, boolean interactive, boolean powerSave,
                 long queueItem, long nextItem, long previousItem) {
        State(String token, int playback, long position, long actions, int volume,
                int minVolume, int maxVolume, boolean interactive, boolean powerSave) {
            this(token, playback, position, actions, volume, minVolume, maxVolume, interactive, powerSave, -1, -1, -1);
        }
    }
    record Result(String code, boolean dispatched, State state) {
        boolean confirmed() { return "ok".equals(code); }
    }
    interface Backend {
        State read(Request request) throws Exception;
        void apply(Request request, State before) throws Exception;
    }
    interface Clock {
        long now();
        void sleep(long ms) throws InterruptedException;
    }
    private final Backend backend;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    MediaControlSession(Backend backend, Clock clock) { this.backend = backend; this.clock = clock; }

    Result execute(Request request, BooleanSupplier authorized, BooleanSupplier cancelled) {
        if (!lock.tryLock()) return new Result("busy", false, null);
        boolean dispatched = false;
        State latest = null;
        try {
            check(authorized, cancelled);
            latest = backend.read(request);
            check(authorized, cancelled);
            if (request.action == Action.QUERY || request.action == Action.VOLUME_QUERY)
                return new Result("ok", false, latest);
            if (isMedia(request.action) && !request.token.equals(latest.token))
                return new Result("stale_media_session", false, latest);
            // Resolve relative OEM commands at the receiver, against this exact media session.
            if (request.action == Action.TOGGLE)
                request = new Request(latest.playback == 3 ? Action.PAUSE : Action.PLAY,
                        request.packageName, request.token, 0, -1);
            if (request.action == Action.SEEK_FORWARD || request.action == Action.SEEK_BACKWARD) {
                if (latest.position < 0) return new Result("position_unavailable", false, latest);
                request = new Request(Action.SEEK, request.packageName, request.token,
                        Math.max(0, Math.addExact(latest.position, request.action == Action.SEEK_FORWARD ? 30_000 : -15_000)), -1);
            }
            long required = requiredAction(request.action);
            if (required != 0 && (latest.actions & required) == 0)
                return new Result("action_unavailable", false, latest);
            if (request.action == Action.NEXT || request.action == Action.PREVIOUS) {
                long target = request.action == Action.NEXT ? latest.nextItem : latest.previousItem;
                if (latest.queueItem == -1 || target == -1 || target == latest.queueItem)
                    return new Result("queue_unavailable", false, latest);
                // Pin the actual neighbor before dispatch. A skipping transient state is not success.
                request = new Request(request.action, request.packageName, request.token, target, -1);
            }
            if (request.action == Action.SET_VOLUME || request.action == Action.RESTORE_VOLUME) {
                if (request.value < latest.minVolume || request.value > latest.maxVolume)
                    return new Result("volume_out_of_range", false, latest);
                if (request.expectedVolume >= 0 && request.expectedVolume != latest.volume)
                    return new Result("volume_changed", false, latest);
            }
            if (matches(request, latest)) return new Result("ok", false, latest);
            check(authorized, cancelled);
            // Conservatively mark attempted before the OS/OEM call: exceptions can follow side effects.
            dispatched = true;
            backend.apply(request, latest);
            long deadline = clock.now() + 2_000;
            while (true) {
                check(authorized, cancelled);
                latest = backend.read(request);
                check(authorized, cancelled);
                if (isMedia(request.action) && !request.token.equals(latest.token))
                    return new Result("stale_media_session", true, latest);
                if (matches(request, latest)) return new Result("ok", true, latest);
                if (clock.now() >= deadline) return new Result("result_unconfirmed", true, latest);
                clock.sleep(40);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(dispatched ? "cancelled_after_dispatch" : "cancelled", dispatched, latest);
        } catch (SecurityException denied) {
            return new Result("permission_denied", dispatched, latest);
        } catch (IllegalStateException failure) {
            String code = failure.getMessage();
            if (!java.util.Set.of("cancelled", "stale_session", "stale_media_session", "media_unavailable",
                    "ambiguous_media_session", "volume_fixed", "volume_changed", "native_rejected").contains(code == null ? "" : code))
                code = "execution_failed";
            if ("cancelled".equals(code) && dispatched) code = "cancelled_after_dispatch";
            return new Result(code, dispatched, latest);
        } catch (Exception failure) {
            return new Result("execution_failed", dispatched, latest);
        } finally { lock.unlock(); }
    }
    private static void check(BooleanSupplier authorized, BooleanSupplier cancelled) {
        if (!authorized.getAsBoolean()) throw new IllegalStateException("stale_session");
        if (cancelled.getAsBoolean()) throw new IllegalStateException("cancelled");
    }
    static boolean isMedia(Action action) {
        return action != Action.VOLUME_QUERY && action != Action.SET_VOLUME && action != Action.RESTORE_VOLUME;
    }
    static long requiredAction(Action action) {
        return switch (action) { case PLAY -> 4; case PAUSE -> 2; case STOP -> 1; case SEEK -> 256;
            case NEXT -> 32; case PREVIOUS -> 16; case FAST_FORWARD -> 64; case REWIND -> 8; default -> 0; };
    }
    private static boolean matches(Request request, State state) {
        return switch (request.action) {
            case PLAY -> state.playback == 3;
            case PAUSE -> state.playback == 2;
            case STOP -> state.playback == 1;
            case SEEK -> state.position >= 0 && Math.abs(state.position - request.value) <= 1_000;
            case NEXT, PREVIOUS -> state.queueItem == request.value
                    && (state.playback == 1 || state.playback == 2 || state.playback == 3);
            case FAST_FORWARD -> state.playback == 4;
            case REWIND -> state.playback == 5;
            case SET_VOLUME, RESTORE_VOLUME -> state.volume == request.value;
            default -> false;
        };
    }
    static int volumeFromBasisPoints(int value, int min, int max) {
        if (value < 0 || value > 10_000 || min < 0 || max <= min || max > 10_000)
            throw new IllegalArgumentException("invalid_volume");
        return min + (int) ((long) value * (max - min) / 10_000);
    }
    static int volumeFromPercent(int value, int max) {
        if (value < 0 || value > 100 || max <= 0 || max > 10_000)
            throw new IllegalArgumentException("invalid_volume");
        return (int) ((long) value * max / 100);
    }
}
