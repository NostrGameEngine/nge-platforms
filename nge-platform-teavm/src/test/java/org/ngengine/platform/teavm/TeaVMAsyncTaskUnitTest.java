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
package org.ngengine.platform.teavm;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;

public class TeaVMAsyncTaskUnitTest {

    @Test
    public void composeAttachedAfterAlreadyResolvedTaskExecutes() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncTask<Integer> resolved = platform.wrapPromise((resolve, reject) -> resolve.accept(4));
        assertEquals(Integer.valueOf(4), resolved.await());

        AsyncTask<Integer> composed = resolved.compose(v -> AsyncTask.completed(v + 6));
        assertEquals(Integer.valueOf(10), composed.await());
    }

    @Test
    public void wrapPromiseSynchronousThrowReturnsFailedTask() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncTask<String> task = platform.wrapPromise((resolve, reject) -> {
            throw new IllegalStateException("sync failure");
        });

        assertTrue(task.isDone());
        assertTrue(task.isFailed());
        try {
            task.await();
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("sync failure"));
        }
    }

    @Test
    public void promisifyWithExecutorRunFailureReturnsFailedTask() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncExecutor failingExecutor = new AsyncExecutor() {
            @Override
            public <T> AsyncTask<T> run(java.util.concurrent.Callable<T> r) {
                throw new RuntimeException("executor down");
            }

            @Override
            public <T> AsyncTask<T> runLater(java.util.concurrent.Callable<T> r, long delay, TimeUnit unit) {
                throw new RuntimeException("executor down");
            }

            @Override
            public void close() {}
        };

        AsyncTask<String> task = platform.promisify((resolve, reject) -> resolve.accept("ok"), failingExecutor);
        assertTrue(task.isDone());
        assertTrue(task.isFailed());
        try {
            task.await();
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("executor down"));
        }
    }

    private static TeaVMPlatform newPlatformOrSkip() {
        try {
            TeaVMPlatform platform = new TeaVMPlatform();
            platform.wrapPromise((resolve, reject) -> resolve.accept("probe")).await();
            return platform;
        } catch (Throwable t) {
            Assume.assumeNoException("TeaVM runtime not available for async task tests", t);
            throw new AssertionError(t);
        }
    }
}
