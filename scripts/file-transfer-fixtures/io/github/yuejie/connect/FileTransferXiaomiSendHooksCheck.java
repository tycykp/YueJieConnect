package io.github.yuejie.connect;

import android.content.*;
import android.net.Uri;
import com.xiaomi.mirror.d0;
import com.xiaomi.mirror.connection.G;
import com.xiaomi.mirror.message.DragMessage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

/** Host-only contract fixture. It exercises the production hooks/client/engine, never OEM UI. */
public class FileTransferXiaomiSendHooksCheck {
    File root;
    FileTransferEngine receiver;
    FileTransferSendCoordinator coordinator;
    XiaomiFileSendHooks hooks;
    d0 owner;
    ClipData clip;
    final Map<FileTransferEngine.Key, FileTransferEngine.Offer> offers = new LinkedHashMap<>();
    final Map<FileTransferEngine.Key, CompletableFuture<Void>> decisions = new LinkedHashMap<>();
    final Map<FileTransferEngine.Key, FileTransferEngine.Receipt> receipts = new HashMap<>();
    final Map<FileTransferEngine.Key, String> errors = new HashMap<>();
    final Map<FileTransferEngine.Key, byte[]> published = new HashMap<>();
    final List<String> operations = new ArrayList<>();
    final List<Runnable> preparing = new ArrayList<>();
    boolean live = true, queuePreparation, screenBound;
    int screenDrops;
    int selections, rejectIndex = -1;
    long sequence;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("yj-file-send-hooks-").toFile();
        ContentResolver.DATA.clear(); q4.A.starts = q4.A.finishes = 0;
        G.J().peer = new Object(); G.J().other = new Object();
        receiver = new FileTransferEngine(new File(root, "in"), key -> live, System::currentTimeMillis);
        FileTransferProtocol protocol = new FileTransferProtocol(receiver);
        FileTransferClient client = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key key) { return live; }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key key, String op, JSONObject payload) {
                operations.add(op);
                try { return CompletableFuture.completedFuture(protocol.execute(key.peer(), key.session(), op, payload)); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
        }, Runnable::run);
        coordinator = new FileTransferSendCoordinator(client, (key, offer) -> {
            offers.put(key, offer);
            CompletableFuture<Void> decision = new CompletableFuture<>(); decisions.put(key, decision); return decision;
        }, task -> { if (queuePreparation) preparing.add(task); else task.run(); });
        hooks = new XiaomiFileSendHooks(FileTransferHookHarness.module(), new Context(root), getClass().getClassLoader(),
                coordinator, new XiaomiFileSendHooks.Routes() {
            public boolean screenCurrent(Object view) { return live && screenBound; }
            public void screenDropped(Object view, long session) { screenDrops++; }
            public FileTransferEngine.Key select(Object delegate, Object peer, long session, int screen,
                    XiaomiFileSendHooks.NativeFile file) {
                selections++;
                assertSame(G.J().peer, peer); assertEquals(7, screen);
                if (file.index() == rejectIndex) return null;
                return new FileTransferEngine.Key("synthetic-pad", "a".repeat(64), String.format("%032x", ++sequence));
            }
            public boolean current(Object delegate, long session, FileTransferEngine.Key key) { return live; }
            public void finished(FileTransferEngine.Key key, FileTransferEngine.Receipt receipt, String error) {
                assertFalse("one terminal callback per reservation", receipts.containsKey(key) || errors.containsKey(key));
                if (receipt != null) { assertNull(error); receipts.put(key, receipt); }
                else { assertNotNull(error); errors.put(key, error); }
            }
        });
        hooks.install(); owner = new d0(); clip = synthetic(7001);
    }
    ClipData synthetic(int... sizes) {
        Uri[] uris = new Uri[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            uris[i] = Uri.parse("content://synthetic/" + ContentResolver.DATA.size());
            byte[] bytes = new byte[sizes[i]];
            for (int at = 0; at < bytes.length; at++) bytes[at] = (byte) (at * 31);
            ContentResolver.DATA.put(uris[i], bytes);
        }
        return new ClipData(uris);
    }
    void start(d0 source, ClipData data, int pid) throws Exception {
        FileTransferHookHarness.call(d0.class.getMethod("onDragStart", ClipData.class, int.class, int.class, int.class),
                source, data, 1001, pid, 257);
    }
    void sendControl(long session, String peer, int action) {
        DragMessage message = new DragMessage(); message.action = action; message.screenId = 7;
        FileTransferHookHarness.send(message, session, peer);
    }
    void accept(FileTransferEngine.Key key) throws Exception {
        receiver.authorize(key, offers.get(key), new FileTransferEngine.Sink() {
            public void publishVerified(File file, FileTransferEngine.Offer actual,
                    FileTransferEngine.SessionGate gate, FileTransferEngine.Key id) throws IOException {
                published.put(id, Files.readAllBytes(file.toPath()));
            }
            public void rollback() { published.remove(key); }
        });
        decisions.get(key).complete(null);
    }
    @After public void cleanup() throws Exception {
        hooks.close(); coordinator.close(); receiver.close();
        for (Runnable task : preparing) task.run();
        assertEquals(0, FileTransferHookHarness.hooks());
        try (var paths = Files.walk(root.toPath())) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isDirectory(path)) fail("source or receive temporary file leaked");
                Files.delete(path);
            }
        }
    }
    @Test public void actualStartUsesOriginalClipThenRealCommitWithoutInventingFinish() throws Exception {
        start(owner, clip, 1002);
        assertEquals(1, q4.A.starts); assertEquals(0, q4.A.finishes); assertTrue(operations.isEmpty());
        FileTransferEngine.Key key = offers.keySet().iterator().next();
        assertEquals(7001, offers.get(key).size()); assertFalse(decisions.get(key).isDone());
        accept(key);
        assertArrayEquals(ContentResolver.DATA.get(clip.getItemAt(0).getUri()), published.get(key));
        assertEquals(offers.get(key).sha256(), receipts.get(key).sha256());
        assertEquals(List.of("file_offer", "file_chunk", "file_chunk", "file_chunk", "file_chunk", "file_commit"), operations);
        assertEquals(1, q4.A.starts); assertEquals(0, q4.A.finishes);
    }
    @Test public void nativeSendOutsideTheCapturedSourceCannotInferOwner() {
        sendControl(88, "native-synthetic-pad", 0);
        assertEquals(1, q4.A.starts); assertEquals(0, selections); assertTrue(offers.isEmpty());
    }
    @Test public void mismatchedPeerOrForeignUriKeepsNativePath() throws Exception {
        owner.overridePeer = "native-other"; start(owner, clip, 1002);
        assertEquals(0, selections); assertTrue(offers.isEmpty());
        owner.overridePeer = null; owner.replaceSource = true; start(owner, clip, 1002);
        assertEquals(0, selections); assertTrue(offers.isEmpty()); assertEquals(2, q4.A.starts);
    }
    @Test public void partialSelectionReleasesReservationWithoutSendingAnyPart() throws Exception {
        rejectIndex = 1; start(owner, synthetic(2, 3), 1002);
        assertEquals(2, selections); assertEquals(1, errors.size());
        assertTrue(offers.isEmpty()); assertTrue(operations.isEmpty()); assertEquals(1, q4.A.starts);
    }
    @Test public void wrongPeerCancelCannotCancelMatchingPeerButCleanDoes() throws Exception {
        start(owner, clip, 1002);
        FileTransferEngine.Key key = decisions.keySet().iterator().next();
        sendControl(owner.b, "native-other", 5); assertFalse(decisions.get(key).isDone());
        sendControl(owner.b, "native-synthetic-pad", 9); assertTrue(decisions.get(key).isCancelled());
        assertEquals("file_cancelled", errors.get(key)); assertTrue(receipts.isEmpty()); assertTrue(operations.isEmpty());
    }
    @Test public void sourcePermissionReleaseCancelsOnlyOriginalSource() throws Exception {
        start(owner, clip, 1002); var decision = decisions.values().iterator().next();
        var method = d0.class.getMethod("onDelegatePermissionReleased", List.class);
        FileTransferHookHarness.call(method, owner, List.of(Uri.parse("content://synthetic/unrelated")));
        assertFalse(decision.isDone());
        FileTransferHookHarness.call(method, owner, List.of(clip.getItemAt(0).getUri()));
        assertTrue(decision.isCancelled()); assertTrue(operations.isEmpty());
    }
    @Test public void changingNativePeerObjectCancelsEvenWithSameIdAndAuthentication() throws Exception {
        start(owner, clip, 1002); var decision = decisions.values().iterator().next();
        G.J().peer = new Object(); hooks.sessionChanged();
        assertTrue(decision.isCancelled()); assertTrue(receipts.isEmpty());
    }
    @Test public void ignoredOwnPidStartCannotCancelAnExistingForeignSource() throws Exception {
        start(owner, clip, 1002); var decision = decisions.values().iterator().next();
        start(owner, clip, 7000);
        assertFalse(decision.isDone()); assertEquals(1, q4.A.starts); assertEquals(1, selections);
    }
    @Test public void sourceDescriptorIsCopiedBeforeNativeMessageCanRecycle() throws Exception {
        queuePreparation = true; start(owner, clip, 1002);
        assertTrue(offers.isEmpty()); assertEquals(1, preparing.size());
        owner.last.fileList.get(0).name = "recycled.bin"; owner.last.fileList.clear();
        preparing.remove(0).run();
        var key = offers.keySet().iterator().next();
        assertEquals("synthetic-0.bin", offers.get(key).name()); accept(key);
        assertEquals(7001, receipts.get(key).size());
    }
    @Test public void fourPendingSourcesBoundCapacityAndFifthLeavesNativePath() throws Exception {
        for (int i = 0; i < 5; i++) start(new d0(), synthetic(i), 1002);
        assertEquals(4, offers.size()); assertEquals(1, errors.size()); assertEquals(5, q4.A.starts);
        assertTrue(operations.isEmpty());
    }
    @Test public void newDragCancelsOldOwnerAndLateConsentCannotResurrectIt() throws Exception {
        start(owner, clip, 1002); var old = decisions.values().iterator().next();
        start(owner, synthetic(0), 1002);
        assertTrue(old.isCancelled()); assertFalse(old.complete(null));
        assertEquals(2, offers.size()); assertEquals(1, errors.size()); assertTrue(operations.isEmpty());
        var key = offers.keySet().stream().skip(1).findFirst().get(); accept(key);
        assertEquals(0, receipts.get(key).size()); assertEquals(0, q4.A.finishes);
    }
    void screenEvent(k4.R0 handler,int action)throws Exception{
        FileTransferHookHarness.call(k4.R0.class.getMethod("onDrag",android.view.View.class,android.view.DragEvent.class),
                handler,handler.a,new android.view.DragEvent(action,clip,null));
    }
    void screenMessage(k4.R0 handler,int action)throws Exception{
        DragMessage message=new DragMessage();message.action=action;message.screenId=7;
        if(action==0)message.clipData=V3.V.G().x0(1001,clip);
        FileTransferHookHarness.call(com.xiaomi.mirror.sink.SinkView.class.getMethod("m1",com.xiaomi.mirror.message.Message.class,long.class),
                handler.a,message,handler.b);
    }
    @Test public void floatingWindowSourceTransfersOriginalBytesAfterAsyncConversionAndDrop()throws Exception{
        screenBound=true;var handler=new k4.R0();screenEvent(handler,1);assertTrue(offers.isEmpty());
        screenMessage(handler,0);var key=offers.keySet().iterator().next();assertTrue(operations.isEmpty());
        screenMessage(handler,4);screenMessage(handler,6);screenEvent(handler,4);
        assertEquals(1,screenDrops);assertFalse(decisions.get(key).isDone());accept(key);
        assertArrayEquals(ContentResolver.DATA.get(clip.getItemAt(0).getUri()),published.get(key));
        assertEquals(7001,receipts.get(key).size());
    }
    @Test public void floatingWindowCloseCancelsPendingConsentAndSource()throws Exception{
        screenBound=true;var handler=new k4.R0();screenEvent(handler,1);screenMessage(handler,0);
        var key=offers.keySet().iterator().next();
        FileTransferHookHarness.call(k4.R0.class.getMethod("e"),handler);
        assertTrue(decisions.get(key).isCancelled());assertTrue(operations.isEmpty());assertEquals("file_cancelled",errors.get(key));
    }
    @Test public void floatingWindowWithoutStreamingProofCannotSelectFiles()throws Exception{
        var handler=new k4.R0();screenEvent(handler,1);screenMessage(handler,0);screenMessage(handler,4);
        assertTrue(offers.isEmpty());assertEquals(0,selections);assertEquals(0,screenDrops);
    }
}
