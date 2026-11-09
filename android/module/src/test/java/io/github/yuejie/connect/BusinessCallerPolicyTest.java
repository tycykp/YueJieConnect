package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class BusinessCallerPolicyTest {
    @Test public void fileUriAndMediaExecutorsRemainInTheVerifiedNativePackages(){
        for(String operation:new String[]{"file_prepare","file_offer","file_chunk","file_commit","file_cancel","clipboard_uri_write","media_control"}){
            assertTrue(CallerPolicy.executorAllowed(CallerPolicy.MIRROR,operation));
            assertTrue(CallerPolicy.executorAllowed(CallerPolicy.OPLUS,operation));
            assertFalse(CallerPolicy.executorAllowed(CallerPolicy.XIAOMI,operation));
            assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_CAST,operation));
            assertFalse(CallerPolicy.executorAllowed(CallerPolicy.OPLUS_FINDER,operation));
            assertFalse(CallerPolicy.executorAllowed("com.android.phone",operation));
            assertFalse(CallerPolicy.moduleExecutorAllowed(operation));
        }
    }
}
