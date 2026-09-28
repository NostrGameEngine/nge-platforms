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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
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

import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.AsyncTask;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.SkipPlatform;
import org.teavm.junit.TeaVMTestRunner;
import org.teavm.junit.TestPlatform;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMPromiseAwaitTest {

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void awaitCompletedTaskInsideSuccessCallback() throws Exception {
        TeaVMPlatform platform = new TeaVMPlatform();
        Consumer<Integer>[] resolve = new Consumer[1];
        AsyncTask<Integer> task = platform.wrapPromise((res, rej) -> resolve[0] = res);
        boolean[] called = { false };

        AsyncTask<Integer> chained = task.then(value -> {
            assertTrue(task.isDone());
            assertEquals(value, task.await());
            called[0] = true;
            return value + 1;
        });

        resolve[0].accept(42);
        assertTrue(called[0]);
        assertEquals(Integer.valueOf(43), chained.await());
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void awaitFailedTaskInsideFailureCallback() throws Exception {
        TeaVMPlatform platform = new TeaVMPlatform();
        Consumer<Throwable>[] reject = new Consumer[1];
        AsyncTask<Integer> task = platform.wrapPromise((res, rej) -> reject[0] = rej);
        IllegalStateException cause = new IllegalStateException("expected failure");
        boolean[] called = { false };

        task.catchException(error -> {
            assertTrue(task.isDone());
            assertSame(cause, error);
            try {
                task.await();
                fail("Expected task failure");
            } catch (ExecutionException e) {
                assertSame(cause, e.getCause());
                called[0] = true;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        reject[0].accept(cause);
        assertTrue(called[0]);
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    // The Wasm GC runner does not dispatch this test's Java timer callback.
    @SkipPlatform(TestPlatform.WEBASSEMBLY_GC)
    public void awaitPendingTasksUntilTheySettle() throws Exception {
        TeaVMPlatform platform = new TeaVMPlatform();
        Consumer<Integer>[] resolve = new Consumer[1];
        AsyncTask<Integer> success = platform.wrapPromise((res, rej) -> resolve[0] = res);
        assertFalse(success.isDone());
        TeaVMBinds.setTimeout(() -> resolve[0].accept(7), 1);
        assertEquals(Integer.valueOf(7), success.await());

        Consumer<Throwable>[] reject = new Consumer[1];
        AsyncTask<Integer> failure = platform.wrapPromise((res, rej) -> reject[0] = rej);
        IllegalStateException cause = new IllegalStateException("pending failure");
        assertFalse(failure.isDone());
        TeaVMBinds.setTimeout(() -> reject[0].accept(cause), 1);
        try {
            failure.await();
            fail("Expected task failure");
        } catch (ExecutionException e) {
            assertSame(cause, e.getCause());
        }
    }
}
