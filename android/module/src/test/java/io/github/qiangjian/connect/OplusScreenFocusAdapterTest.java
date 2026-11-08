package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual focus observer with a controlled OEM/display boundary; no device focus is fabricated. */
public class OplusScreenFocusAdapterTest {
    private final Object owner=new Object();
    private final ScreenCastSession.Scope scope=new ScreenCastSession.Scope("c".repeat(64),7);
    private final AtomicBoolean rendered=new AtomicBoolean(true),nativeCurrent=new AtomicBoolean(true);
    private final List<OplusScreenFocusAdapter.NativeFocus> focuses=new ArrayList<>(),revoked=new ArrayList<>();
    private OplusScreenFocusAdapter adapter(){return new OplusScreenFocusAdapter(
            (actual,bus) -> actual==owner && "native-bus".equals(bus) ? new OplusScreenFocusAdapter.Route(scope,rendered::get) : null,
            new OplusScreenFocusAdapter.Listener(){public void focused(OplusScreenFocusAdapter.NativeFocus focus){focuses.add(focus);}public void revoked(OplusScreenFocusAdapter.NativeFocus focus){revoked.add(focus);}},
            (actual,display) -> nativeCurrent.get()&&actual==owner&&display==42 ? new OplusScreenFocusAdapter.DisplayState("native-bus",42,1080,2400) : null);}
    @Test public void actualOwnerUidDisplayAndGeometryDefineFocusAndNewEpoch(){
        try(var adapter=adapter()){
            adapter.observed(new Object(),10001,42);adapter.observed(owner,10001,43);assertTrue(focuses.isEmpty());
            adapter.observed(owner,10001,42);var first=focuses.get(0);assertSame(owner,first.nativeOwner());assertEquals(scope,first.scope());
            assertEquals(10001,first.focus().targetUid());assertEquals(42,first.focus().displayId());assertEquals(1080,first.focus().width());
            adapter.observed(owner,10002,42);assertFalse(first.current());assertEquals(1,revoked.size());
            assertTrue(focuses.get(1).focus().epoch()>first.focus().epoch());assertEquals(10002,adapter.currentFocus(owner).targetUid());
        }
    }
    @Test public void nativeObservationCanWaitForFirstRenderButCannotResumeAfterRevocation(){
        rendered.set(false);try(var adapter=adapter()){
            adapter.observed(owner,10001,42);assertTrue(focuses.isEmpty());assertNull(adapter.currentFocus(owner));
            rendered.set(true);adapter.tick();assertEquals(1,focuses.size());assertNotNull(adapter.currentFocus(owner));
            rendered.set(false);adapter.tick();assertEquals(1,revoked.size());rendered.set(true);adapter.tick();
            assertNull(adapter.currentFocus(owner));assertFalse(focuses.get(0).current());
        }
    }
    @Test public void nativeDisplayOwnerLossAndSessionChangeInvalidateOldSupplier(){
        try(var adapter=adapter()){
            adapter.observed(owner,10001,42);var first=focuses.get(0);nativeCurrent.set(false);adapter.tick();assertFalse(first.current());
            nativeCurrent.set(true);adapter.observed(owner,10001,42);adapter.sessionChanged();assertNull(adapter.currentFocus(owner));
            assertFalse(focuses.get(1).current());assertEquals(2,revoked.size());
        }
    }
}
