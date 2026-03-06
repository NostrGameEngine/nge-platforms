/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
