package org.ngengine.platform.jvm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestJVMNGEAllocatorGuard {

    private static final long MIB = 1024L * 1024L;

    @Before
    public void setUp() {
        JVMNGEAllocatorGuard.resetStateForTests();
        JVMNGEAllocatorGuard.setTestHooks(null, null, null);
    }

    @After
    public void tearDown() {
        JVMNGEAllocatorGuard.resetStateForTests();
        JVMNGEAllocatorGuard.setTestHooks(null, null, null);
    }

    @Test
    public void shouldAdaptBudgetUpThenDown() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong currentBytes = new AtomicLong();
        AtomicInteger gcCalls = new AtomicInteger(0);

        JVMNGEAllocatorGuard.setTestHooks(currentBytes::get, now::get, gcCalls::incrementAndGet);

        long initialBudget = JVMNGEAllocatorGuard.getSoftBudgetForTests();

        currentBytes.set((long) (initialBudget * 0.95f));
        JVMNGEAllocatorGuard.beforeAlloc(0L);
        now.set(3_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);
        now.set(6_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);

        long grownBudget = JVMNGEAllocatorGuard.getSoftBudgetForTests();
        Assert.assertTrue("Expected budget to grow under sustained high pressure", grownBudget > initialBudget);
        Assert.assertEquals("Should not request GC while still below budget", 0, gcCalls.get());

        currentBytes.set(0L);
        for (int i = 0; i < 8; i++) {
            now.addAndGet(3_000L);
            JVMNGEAllocatorGuard.beforeAlloc(0L);
        }

        long shrunkBudget = JVMNGEAllocatorGuard.getSoftBudgetForTests();
        Assert.assertTrue("Expected budget to shrink under sustained low pressure", shrunkBudget < grownBudget);
    }

    @Test
    public void shouldRequestGcOnBurstEvenWithAdaptiveBudget() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong currentBytes = new AtomicLong();
        AtomicInteger gcCalls = new AtomicInteger(0);

        JVMNGEAllocatorGuard.setTestHooks(currentBytes::get, now::get, gcCalls::incrementAndGet);

        long initialBudget = JVMNGEAllocatorGuard.getSoftBudgetForTests();

        currentBytes.set((long) (initialBudget * 0.95f));
        JVMNGEAllocatorGuard.beforeAlloc(0L);
        now.set(3_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);
        now.set(6_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);

        long grownBudget = JVMNGEAllocatorGuard.getSoftBudgetForTests();
        Assert.assertTrue(grownBudget > initialBudget);

        currentBytes.set(grownBudget + 64L * MIB);
        now.addAndGet(3_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);

        Assert.assertTrue("Expected explicit GC request on over-budget burst", gcCalls.get() >= 2);
    }

    @Test
    public void shouldRequestMaintenanceGcAfterSilence() {
        AtomicLong now = new AtomicLong(0L);
        AtomicLong currentBytes = new AtomicLong();
        AtomicInteger gcCalls = new AtomicInteger(0);

        JVMNGEAllocatorGuard.setTestHooks(currentBytes::get, now::get, gcCalls::incrementAndGet);

        long budget = JVMNGEAllocatorGuard.getSoftBudgetForTests();
        currentBytes.set((long) (budget * 0.50f));

        JVMNGEAllocatorGuard.beforeAlloc(0L);
        Assert.assertEquals(0, gcCalls.get());

        now.set(61_000L);
        JVMNGEAllocatorGuard.beforeAlloc(0L);

        Assert.assertTrue("Expected maintenance GC after long silence under non-trivial usage", gcCalls.get() >= 2);
    }
}
