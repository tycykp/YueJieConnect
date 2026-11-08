package io.github.qiangjian.connect;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual adapter with a queued transport: an obsolete card release cannot stop its replacement. */
public final class DeviceFinderAdapterRaceCheck {
    private static int checks;
    private static final Context CONTEXT = new ContextWrapper(null) {
        @Override public Context getApplicationContext() { return this; }
        @Override public void sendBroadcast(Intent intent) { }
    };
    private static final byte[] START = ("\0\1synthetic/peer/1").getBytes(StandardCharsets.UTF_8);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker did not progress");
    }

    private static JSONObject projection(String session) throws Exception {
        return new JSONObject().put("id", "synthetic-peer").put("session_id", session)
                .put("operations", new JSONArray().put("device_finder"));
    }

    private static final class Wire implements XiaomiFinderAdapter.Transport {
        final List<String> stops = new CopyOnWriteArrayList<>();
        volatile boolean ringing;
        @Override public JSONObject request(String id, String session, String op, String request, JSONObject payload) {
            try {
                String action = payload.optString("action");
                if ("start".equals(action)) ringing = true;
                if ("stop".equals(action)) { stops.add(session); ringing = false; }
                return new JSONObject().put("status", "ok").put("session_id", session)
                        .put("result", new JSONObject().put("operation", "device_finder")
                                .put("data", new JSONObject().put("ringing", ringing)));
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    private static ThreadPoolExecutor worker(XiaomiFinderAdapter adapter) throws Exception {
        Field field = XiaomiFinderAdapter.class.getDeclaredField("monitorWorker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(adapter);
    }

    private static void idle(ThreadPoolExecutor worker) throws Exception {
        CountDownLatch barrier = new CountDownLatch(1);
        worker.execute(barrier::countDown);
        await(barrier);
    }

    private static CountDownLatch block(ThreadPoolExecutor worker) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        worker.execute(() -> {
            entered.countDown();
            try { await(resume); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        await(entered);
        return resume;
    }

    public static void main(String[] args) throws Exception {
        Wire wire = new Wire();
        XiaomiFinderAdapter adapter = new XiaomiFinderAdapter(wire);
        ThreadPoolExecutor worker = worker(adapter);
        CountDownLatch resume = null;
        try {
            adapter.update(projection("session-one"));
            Object oldCard = new Object(), newCard = new Object();
            check(adapter.connect(oldCard, "synthetic-peer") == 0, "old card connected");
            check(adapter.send(oldCard, CONTEXT, "synthetic-peer", START) == 0, "old card started");
            resume = block(worker);
            adapter.release(oldCard);
            check(adapter.connect(newCard, "synthetic-peer") == 0, "replacement card connected");
            check(adapter.send(newCard, CONTEXT, "synthetic-peer", START) == 0, "replacement card started");
            resume.countDown(); resume = null;
            idle(worker);
            check(wire.stops.isEmpty() && wire.ringing, "obsolete release must not stop replacement card");

            adapter.release(newCard);
            idle(worker);
            check(wire.stops.equals(List.of("session-one")) && !wire.ringing, "last live release still stops");

            Object departing = new Object(), nextSession = new Object();
            check(adapter.connect(departing, "synthetic-peer") == 0, "departing card connected");
            check(adapter.send(departing, CONTEXT, "synthetic-peer", START) == 0, "departing card started");
            resume = block(worker);
            adapter.release(departing);
            adapter.update(projection("session-two"));
            check(adapter.connect(nextSession, "synthetic-peer") == 0, "new session card connected");
            check(adapter.send(nextSession, CONTEXT, "synthetic-peer", START) == 0, "new session card started");
            resume.countDown(); resume = null;
            idle(worker);
            check(wire.stops.equals(List.of("session-one")) && wire.ringing, "old session release discarded before transport");
            adapter.close();
            check(worker.awaitTermination(5, TimeUnit.SECONDS), "close worker terminates");
            check(wire.stops.equals(List.of("session-one", "session-two")) && !wire.ringing, "close still stops selected session");
        } finally {
            if (resume != null) resume.countDown();
            adapter.close();
        }
        System.out.println("PASS device-finder card-release races: " + checks + " assertions");
    }
}
