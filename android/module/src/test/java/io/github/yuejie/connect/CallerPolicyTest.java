package io.github.yuejie.connect;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class CallerPolicyTest {
    @Test public void nativePairingHostIsPinnedAndCannotExecuteBusinessOperations(){
        String signer="e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e";
        assertEquals(2,CallerPolicy.source(CallerPolicy.OPLUS_PAIRING,170060016,"17.6.16",signer,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_PAIRING,170060017,"17.6.16",signer,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_PAIRING,170060016,"17.6.16",signer,2));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_PAIRING,170060016,"17.6.16","fake",1));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_PAIRING,"clipboard_write"));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_PAIRING,"logical_open"));
    }
    private final String cert="c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025";
    @Test public void exactIdentityOnly() {
        assertEquals(1,CallerPolicy.source("com.milink.service",180020300L,"18.2.3.0.2610211842",cert,1));
        assertEquals(0,CallerPolicy.source("com.milink.service",180020300L,"18.2.3.0.2610211842","fake",1));
        assertEquals(0,CallerPolicy.source("com.milink.service",180020300L,"18.2.3.0.2610211842",cert,2));
        assertEquals(0,CallerPolicy.source("com.milink.service",180020301L,"18.2.3.0.2610211842",cert,1));
        assertEquals(0,CallerPolicy.source("impostor",180020300L,"18.2.3.0.2610211842",cert,1));
    }
    @Test public void colorOsCurrentSignerAndVersionNameRequired() {
        String op="e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e";
        assertEquals(2,CallerPolicy.source("com.oplus.linker",170070030L,"17.7.30",op,1));
        assertEquals(0,CallerPolicy.source("com.oplus.linker",170070030L,"17.7.31",op,1));
        assertEquals(0,CallerPolicy.source("com.oplus.linker",170070030L,"17.7.30",cert,1));
    }
    @Test public void mirrorPackagePinDoesNotGrantSharedUidAnIpcLease() {
        org.junit.Assert.assertTrue(CallerPolicy.mirrorPackage(180201L,"18.02.01-26.09.10",cert));
        org.junit.Assert.assertFalse(CallerPolicy.mirrorPackage(180202L,"18.02.01-26.09.10",cert));
        org.junit.Assert.assertFalse(CallerPolicy.mirrorPackage(180201L,"18.02.01-26.09.10","fake"));
        assertEquals(0,CallerPolicy.source(CallerPolicy.MIRROR,180201L,"18.02.01-26.09.10",cert,50));
        assertEquals(0,CallerPolicy.source(CallerPolicy.MIRROR,180201L,"18.02.01-26.09.10",cert,1));
    }
    @Test public void finderAndCastRequireTheirExactSinglePackageIdentity() {
        String finder="159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce";
        String platform="e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e";
        assertEquals(2,CallerPolicy.source(CallerPolicy.OPLUS_FINDER,100008,"10.0.8",finder,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_FINDER,100008,"10.0.8",platform,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_FINDER,100008,"10.0.8",finder,2));
        assertEquals(2,CallerPolicy.source(CallerPolicy.OPLUS_CAST,170017,"17.0.017",platform,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_CAST,170018,"17.0.017",platform,1));
        assertEquals(0,CallerPolicy.source(CallerPolicy.OPLUS_CAST,170017,"17.0.017",platform,2));
    }
    @Test public void authenticatedPackagesCannotRegisterOtherBusinessExecutors(){
        org.junit.Assert.assertTrue(CallerPolicy.executorAllowed(CallerPolicy.MIRROR,"clipboard_write"));
        org.junit.Assert.assertTrue(CallerPolicy.executorAllowed(CallerPolicy.OPLUS,"logical_open"));
        org.junit.Assert.assertTrue(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_FINDER,"device_finder"));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_FINDER,"clipboard_write"));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_CAST,"device_finder"));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.MIRROR,"unknown"));
        org.junit.Assert.assertTrue(CallerPolicy.moduleExecutorAllowed("notification_post"));
        org.junit.Assert.assertTrue(CallerPolicy.moduleExecutorAllowed("notification_remove"));
        org.junit.Assert.assertTrue(CallerPolicy.moduleExecutorAllowed("notification_action"));
        org.junit.Assert.assertFalse(CallerPolicy.moduleExecutorAllowed("clipboard_write"));
        org.junit.Assert.assertFalse(CallerPolicy.moduleExecutorAllowed("logical_open"));
        org.junit.Assert.assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_FINDER,"notification_action"));
    }
}
