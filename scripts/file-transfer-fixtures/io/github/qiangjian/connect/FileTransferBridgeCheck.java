package io.github.qiangjian.connect;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real production Bridge + two Endpoints/Engines. Control/Android calls are isolated fixtures. */
public class FileTransferBridgeCheck {
    File root,oneRoot,twoRoot;
    BridgeClient one,two;
    FileTransferBridge sender,receiver;
    FileTransferBridge.Registration receiverRegistration;
    final AtomicBoolean allowed=new AtomicBoolean(true);
    volatile FileTransferNegotiation.Prepared incoming;
    volatile CompletableFuture<FileTransferEngine.Authorization> authorization;
    volatile byte[] received;
    final AtomicInteger prepared=new AtomicInteger(),revoked=new AtomicInteger(),deleted=new AtomicInteger();
    final AtomicBoolean nativeCurrent=new AtomicBoolean(true),disposed=new AtomicBoolean();
    CompletableFuture<Void> accepted;
    @Before public void setup()throws Exception{
        root=Files.createTempDirectory("qj-file-bridge-").toFile();oneRoot=new File(root,"one");twoRoot=new File(root,"two");
        oneRoot.mkdir();twoRoot.mkdir();one=new BridgeClient("synthetic-phone");two=new BridgeClient("synthetic-pad");BridgeClient.pair(one,two);
        sender=new FileTransferBridge(new Context(oneRoot),one,allowed::get);
        receiver=new FileTransferBridge(new Context(twoRoot),two,allowed::get);
        sender.registerReceiver(FileTransferNegotiation.Purpose.CLIPBOARD,(p,e,n)->{});
        receiverRegistration=receiver.registerReceiver(FileTransferNegotiation.Purpose.CLIPBOARD,new FileTransferBridge.NativeReceiver(){
            public void prepared(FileTransferNegotiation.Prepared candidate,FileTransferEndpoint endpoint,FileTransferNegotiation negotiation){incoming=candidate;prepared.incrementAndGet();}
            public void revoked(FileTransferNegotiation.Prepared candidate){revoked.incrementAndGet();}
        });
        assertTrue(sender.ready());assertTrue(receiver.ready());
    }
    void until(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()){if(System.nanoTime()>=end)fail("condition_timeout");Thread.sleep(5);}
    }
    FileTransferEngine.Key allocate()throws Exception{return sender.allocate(FileTransferNegotiation.Purpose.CLIPBOARD,"c".repeat(32)).get(2,TimeUnit.SECONDS);}
    FileTransferSource snapshot(byte[] bytes)throws Exception{return FileTransferSource.snapshot(new File(oneRoot,"out"),new ByteArrayInputStream(bytes),"synthetic.bin","application/octet-stream",bytes.length);}
    void nativeAccept()throws Exception{
        assertNotNull(incoming);
        accepted=new CompletableFuture<>();
        assertTrue(receiver.negotiation().approve(incoming,k->nativeCurrent.get(),accepted,()->{
            if(disposed.compareAndSet(false,true)){received=null;deleted.incrementAndGet();}
            receiver.endpoint().revoke(incoming.key());return true;
        }));
        authorization=receiver.endpoint().authorizeTracked(incoming.key(),incoming.metadata().offer(),new FileTransferEngine.Sink(){
            public void publishVerified(File file,FileTransferEngine.Offer offer,FileTransferEngine.SessionGate gate,FileTransferEngine.Key key)throws IOException{
                received=Files.readAllBytes(file.toPath());
                if(!offer.sha256().equals(FileTransferEngine.hex(FileTransferEngine.sha256().digest(received))))throw new IOException("native_file_hash_mismatch");
            }
            public void rollback(){if(disposed.compareAndSet(false,true)){received=null;deleted.incrementAndGet();}}
        });
        authorization.get(2,TimeUnit.SECONDS);accepted.complete(null);
    }
    @After public void cleanup()throws Exception{
        sender.close();receiver.close();sender.whenRetired().get(5,TimeUnit.SECONDS);receiver.whenRetired().get(5,TimeUnit.SECONDS);
        try(var paths=Files.walk(root.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList()){
            if(!Files.isDirectory(path))fail("temporary_file_leaked");Files.delete(path);
        }}
    }
    @Test public void realPrepareWaitsNativeAcceptanceThenCrossBlockShaAndCanonicalSteps()throws Exception{
        byte[] bytes=new byte[7001];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);
        var key=allocate();var result=sender.send(key,snapshot(bytes),k->true);
        until(()->incoming!=null);Thread.sleep(300);
        assertEquals(1,prepared.get());assertTrue(one.operations.stream().allMatch("file_prepare"::equals));assertNull(received);assertFalse(result.isDone());
        nativeAccept();var receipt=result.get(5,TimeUnit.SECONDS);
        assertArrayEquals(bytes,received);assertEquals(key,receipt.key());assertEquals(bytes.length,receipt.size());
        assertTrue(one.operations.containsAll(List.of("file_offer","file_chunk","file_commit")));
        assertEquals(one.requests.size(),new HashSet<>(one.requests).size());
        assertTrue(sender.release(key).get(2,TimeUnit.SECONDS));assertArrayEquals(bytes,received);
        assertFalse(one.operations.contains("file_cancel"));
    }
    @Test public void allocationIsNecessaryAndRawClientCannotBypassConsent()throws Exception{
        var key=allocate();
        var source=snapshot(new byte[]{1});var result=sender.fileClient().send(key,source);
        try{result.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertFalse(one.operations.contains("file_offer"));assertNull(incoming);
        var fake=FileTransferEngine.Key.create(key.peer(),key.session());
        assertFalse(sender.bindOutgoing(fake,FileTransferNegotiation.Purpose.CLIPBOARD,"d".repeat(32)));
    }
    @Test public void badTransferOrSessionAckCannotApproveOrSendFileBytes()throws Exception{
        var key=allocate();one.poison="transfer";var result=sender.send(key,snapshot(new byte[]{1}),k->true);
        try{result.get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertFalse(one.operations.contains("file_offer"));assertNull(received);
    }
    @Test public void wrongCommitHashCannotBecomeSuccessfulFileReceipt()throws Exception{
        var key=allocate();one.poison="commit_hash";var result=sender.send(key,snapshot(new byte[]{1,2,3}),k->true);
        until(()->incoming!=null);nativeAccept();
        try{result.get(5,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertTrue(one.operations.contains("file_commit"));
    }
    @Test public void cancelledPrepareRevokesNativePlaceholderAndCannotBeRevived()throws Exception{
        var key=allocate();var decision=sender.awaitReceiver(key,snapshotOffer(new byte[]{1}));until(()->incoming!=null);
        decision.cancel(true);until(()->revoked.get()>0);
        assertNull(receiver.negotiation().find(FileTransferNegotiation.Purpose.CLIPBOARD,"c".repeat(32)));
        assertFalse(one.operations.contains("file_offer"));assertFalse(decision.complete(null));
        assertTrue(sender.release(key).get(3,TimeUnit.SECONDS));
    }
    private FileTransferEngine.Offer snapshotOffer(byte[] bytes){return new FileTransferEngine.Offer("synthetic.bin","application/octet-stream",bytes.length,FileTransferEngine.hex(FileTransferEngine.sha256().digest(bytes)));}
    @Test public void nativeRevocationWhileWaitingStopsApprovalWithoutSendingBytes()throws Exception{
        var key=allocate();var result=sender.send(key,snapshot(new byte[]{1,2,3}),k->nativeCurrent.get());until(()->incoming!=null);
        nativeCurrent.set(false);sender.cancel(key);
        try{result.get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertFalse(one.operations.contains("file_offer"));assertNull(received);
    }
    @Test public void allocationAndReleasedTombstonesHaveFourActiveSlotBound()throws Exception{
        List<FileTransferEngine.Key> keys=new ArrayList<>();for(int i=0;i<4;i++)keys.add(sender.allocateKey().get(2,TimeUnit.SECONDS));
        try{sender.allocateKey().get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        var old=keys.remove(0);assertTrue(sender.release(old).get(2,TimeUnit.SECONDS));
        assertFalse(sender.bindOutgoing(old,FileTransferNegotiation.Purpose.CLIPBOARD,"d".repeat(32)));
        assertNotEquals(old,sender.allocateKey().get(2,TimeUnit.SECONDS));
    }
    @Test public void grantRevocationInvalidatesExactSessionAndClearsRegisteredExecutors()throws Exception{
        var key=allocate();var result=sender.send(key,snapshot(new byte[]{1}),k->true);until(()->incoming!=null);
        allowed.set(false);sender.sessionChanged();receiver.sessionChanged();
        try{result.get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        until(()->one.executors.isEmpty()&&two.executors.isEmpty());assertFalse(sender.current(key));
    }
    @Test public void oldRegistrationCannotRevokeReplacementNativeReceiver()throws Exception{
        FileTransferBridge.Registration old=receiverRegistration;old.close();
        AtomicInteger replacementRevoked=new AtomicInteger();
        receiver.registerReceiver(FileTransferNegotiation.Purpose.CLIPBOARD,new FileTransferBridge.NativeReceiver(){
            public void prepared(FileTransferNegotiation.Prepared candidate,FileTransferEndpoint endpoint,FileTransferNegotiation negotiation){incoming=candidate;}
            public void revoked(FileTransferNegotiation.Prepared candidate){replacementRevoked.incrementAndGet();}
        });
        var key=allocate();var waiting=sender.awaitReceiver(key,snapshotOffer(new byte[]{1}));until(()->incoming!=null);
        old.close();
        assertEquals(0,replacementRevoked.get());
        assertSame(incoming,receiver.negotiation().find(FileTransferNegotiation.Purpose.CLIPBOARD,"c".repeat(32)));
        assertFalse(waiting.isDone());
    }
}
