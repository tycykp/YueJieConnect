package io.github.yuejie.connect;

import java.security.SecureRandom;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeScreenProofsTest {
    @Test public void mediaRequiresRenderedReceiptFromTheClaimedCaptureProcess(){
        long[] now={1};
        NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);
        String s="a".repeat(64);
        proofs.scope(s,"yj:peer",true);
        String token=proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",7);
        proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD");
        rejected(()->proofs.rendered(receiver,s,7));
        proofs.nativeAllowed(receiver,s,7);
        assertFalse(proofs.renderedForIssuer(issuer,s,7));
        rejected(()->proofs.rendered(issuer,s,7));
        proofs.rendered(receiver,s,7);
        assertTrue(proofs.renderedForIssuer(issuer,s,7));
        assertFalse(proofs.renderedForIssuer(receiver,s,7));
        assertFalse(proofs.renderedForIssuer(issuer,s,8));
        now[0]+=4000;
        assertFalse(proofs.renderedForIssuer(issuer,s,7));
        rejected(()->proofs.rendered(receiver,s,7));
    }
    @Test public void dragIssuerCanOnlyObserveItsCurrentApprovedCapture(){
        long[] now={1};NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);String s="a".repeat(64);
        proofs.scope(s,"yj:peer",true);String token=proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",7);
        proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD");assertEquals(0,proofs.activeForIssuer(issuer,s));
        proofs.nativeAllowed(receiver,s,7);assertEquals(7,proofs.activeForIssuer(issuer,s));
        assertEquals(0,proofs.activeForIssuer(new NativeScreenProofs.Caller(11,23),s));
        assertEquals(0,proofs.activeForIssuer(issuer,"b".repeat(64)));
        now[0]+=4000;assertEquals(0,proofs.activeForIssuer(issuer,s));
    }
    private final NativeScreenProofs.Caller issuer=new NativeScreenProofs.Caller(11,22),receiver=new NativeScreenProofs.Caller(33,44);
    private void rejected(Runnable call){try{call.run();fail("request accepted");}catch(IllegalStateException expected){}}
    @Test public void exactNativeClaimIsOneUseAndDoesNotGrantPermission(){
        long[] now={1};NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);
        proofs.scope("a".repeat(64),"yj:peer",true);
        String token=proofs.issue(issuer,"a".repeat(64),"yj:peer","native-target","SYNERGY_PAD",1);
        assertTrue(token.matches("[0-9a-f]{64}"));
        rejected(()->proofs.claim(receiver,issuer,token,"other-target","SYNERGY_PAD"));
        rejected(()->proofs.claim(receiver,new NativeScreenProofs.Caller(11,23),token,"native-target","SYNERGY_PAD"));
        var claim=proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD");
        assertEquals(issuer,claim.issuer());assertEquals(receiver,claim.receiver());
        assertFalse(proofs.approved(receiver,claim.session(),1));
        rejected(()->proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD"));
        rejected(()->proofs.nativeAllowed(new NativeScreenProofs.Caller(33,45),claim.session(),1));
        proofs.nativeAllowed(receiver,claim.session(),1);assertTrue(proofs.approved(receiver,claim.session(),1));
        proofs.revoke(issuer,claim.session(),1);assertFalse(proofs.approved(receiver,claim.session(),1));
        rejected(()->proofs.issue(issuer,claim.session(),"yj:peer","native-target","SYNERGY_PAD",1));
    }
    @Test public void deadlineRevocationAndSessionFenceCannotReviveOldStreams(){
        long[] now={1};NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);
        String session="a".repeat(64);proofs.scope(session,"yj:peer",true);
        final String token=proofs.issue(issuer,session,"yj:peer","native-target","SYNERGY_PAD",1);
        now[0]=5001;rejected(()->proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD"));
        proofs.scope(session,"yj:peer",false);proofs.scope(session,"yj:peer",true);
        rejected(()->proofs.issue(issuer,session,"yj:peer","native-target","SYNERGY_PAD",1));
        final String old=proofs.issue(issuer,session,"yj:peer","native-target","SYNERGY_PAD",2);
        proofs.scope("b".repeat(64),"yj:peer",true);
        rejected(()->proofs.claim(receiver,issuer,old,"native-target","SYNERGY_PAD"));
        assertFalse(proofs.approved(receiver,session,2));
    }
    @Test public void capacityNeverEvictsAUsedStreamIdentity(){
        long[] now={1};NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);String s="a".repeat(64);
        proofs.scope(s,"yj:peer",true);
        for(int i=1;i<=64;i++){proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",i);now[0]+=5000;}
        rejected(()->proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",65));
        rejected(()->proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",1));
    }
    @Test public void nativePermissionNeedsLiveBoundProcessLeaseAndCannotReviveAfterExpiry(){
        long[] now={1};NativeScreenProofs proofs=new NativeScreenProofs(new SecureRandom(),()->now[0]);String s="a".repeat(64);
        proofs.scope(s,"yj:peer",true);String token=proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",1);
        proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD");now[0]=120001;
        rejected(()->proofs.nativeAllowed(receiver,s,1));
        token=proofs.issue(issuer,s,"yj:peer","native-target","SYNERGY_PAD",2);
        proofs.claim(receiver,issuer,token,"native-target","SYNERGY_PAD");proofs.nativeAllowed(receiver,s,2);
        now[0]+=3000;proofs.renew(receiver,s,2);assertTrue(proofs.approved(receiver,s,2));
        now[0]+=4000;assertFalse(proofs.approved(receiver,s,2));
        rejected(()->proofs.renew(receiver,s,2));rejected(()->proofs.nativeAllowed(receiver,s,2));
    }
}
