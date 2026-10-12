package org.ngengine.platform;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TestDebugCode {
    @Test
    public void diagnosticsFollowAssertions() {
        var calls = new AtomicInteger();
        assertTrue(NGEUtils.dbg(calls::incrementAndGet));
        assertEquals(NGEUtils.class.desiredAssertionStatus() ? 1 : 0, calls.get());
    }

    @Test
    public void assertionWrappedDiagnosticsFollowCallerAssertions() {
        var calls = new AtomicInteger();
        assert NGEUtils.dbg(calls::incrementAndGet);
        assertEquals(
            TestDebugCode.class.desiredAssertionStatus() && NGEUtils.class.desiredAssertionStatus() ? 1 : 0,
            calls.get()
        );
    }
}
