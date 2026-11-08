package io.github.qiangjian.connect;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class CallerPolicyTest {
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
}
