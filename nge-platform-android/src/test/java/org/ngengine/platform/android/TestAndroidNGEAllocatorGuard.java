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
package org.ngengine.platform.android;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestAndroidNGEAllocatorGuard {
    @Before
    public void setUp() {
        AndroidNGEAllocatorGuard.resetStateForTests();
    }

    @After
    public void tearDown() {
        AndroidNGEAllocatorGuard.setTestHooks(null, null, null);
        AndroidNGEAllocatorGuard.resetStateForTests();
    }

    @Test
    public void gcIntervalUsesNanoseconds() {
        AtomicLong now = new AtomicLong(999_000_000L);
        AtomicInteger gcCalls = new AtomicInteger();
        long pressure = AndroidNGEAllocatorGuard.getSoftBudgetForTests() + 1L;
        AndroidNGEAllocatorGuard.setTestHooks(() -> pressure, now::get, gcCalls::incrementAndGet);

        AndroidNGEAllocatorGuard.beforeAlloc(1L);
        Assert.assertEquals(0, gcCalls.get());
        now.set(1_000_000_000L);
        AndroidNGEAllocatorGuard.beforeAlloc(1L);
        Assert.assertEquals(2, gcCalls.get());
    }

    @Test
    public void concurrentRequestsShareOneGcInterval() throws InterruptedException {
        AtomicInteger gcCalls = new AtomicInteger();
        long pressure = AndroidNGEAllocatorGuard.getSoftBudgetForTests() + 1L;
        AndroidNGEAllocatorGuard.setTestHooks(() -> pressure, () -> 2_000_000_000L, gcCalls::incrementAndGet);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    AndroidNGEAllocatorGuard.beforeAlloc(1L);
                });
            }
            Assert.assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            Assert.assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
        Assert.assertEquals(2, gcCalls.get());
    }
}
