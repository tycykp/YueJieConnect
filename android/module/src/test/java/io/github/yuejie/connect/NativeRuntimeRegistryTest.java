package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeRuntimeRegistryTest {
    private static final String CURRENT="a".repeat(64), OLD="b".repeat(64);
    @Test public void missingOrCachedRuntimeCannotCallCurrentService(){
        NativeRuntimeRegistry registry=new NativeRuntimeRegistry();
        assertFalse(registry.observe(10001,10,"com.oplus.linker","",CURRENT,0));
        assertFalse(registry.observe(10001,10,"com.oplus.linker",OLD,CURRENT,1));
        assertTrue(registry.observe(10001,11,"com.oplus.linker",CURRENT,CURRENT,2));
        assertFalse(registry.observe(10001,10,"com.oplus.linker",OLD,CURRENT,3));
    }
    @Test public void invalidExpectedDigestNeverAuthenticatesAbsentBuildConfig(){
        NativeRuntimeRegistry registry=new NativeRuntimeRegistry();
        assertFalse(registry.observe(10001,10,"com.oplus.linker","unavailable","unavailable",0));
        assertFalse(registry.observe(10001,10,"com.oplus.linker",CURRENT.toUpperCase(),CURRENT,0));
        assertFalse(registry.observe(10001,10,"com.oplus.linker",null,CURRENT,0));
    }
    @Test public void actualIpcTokenBindsRevisionToExactKernelCaller(){
        NativeRuntimeRegistry registry=new NativeRuntimeRegistry();
        assertTrue(registry.observe(10001,10,"com.oplus.linker",CURRENT,CURRENT,0));
        assertFalse(registry.accepts(10001,10,"token",CURRENT,1));
        registry.opened(10001,10,"token",2);
        assertTrue(registry.accepts(10001,10,"token",CURRENT,3));
        assertFalse(registry.accepts(10001,11,"token",CURRENT,3));
        assertFalse(registry.accepts(10002,10,"token",CURRENT,3));
        assertFalse(registry.accepts(10001,10,"other",CURRENT,3));
        assertFalse(registry.accepts(10001,10,"token",OLD,3));
        assertTrue(registry.observe(10001,10,"com.oplus.linker",CURRENT,CURRENT,4));
        assertTrue(registry.accepts(10001,10,"token",CURRENT,5));
        registry.opened(10001,10,"renewed",6);
        assertFalse(registry.accepts(10001,10,"token",CURRENT,7));
        assertTrue(registry.accepts(10001,10,"renewed",CURRENT,7));
        assertFalse(registry.observe(10001,10,"com.oplus.linker",OLD,CURRENT,8));
        assertFalse(registry.accepts(10001,10,"renewed",CURRENT,9));
    }
}
