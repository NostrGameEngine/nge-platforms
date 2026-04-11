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

import static org.junit.Assert.assertEquals;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ThrowableFunction;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMWebsocketTransportUnitTest {

    private static final int STRESS_MESSAGES = 256;

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void sendPreservesOrderUnderStressInBothDirections() throws Exception {
        TeaVMPlatform platform = new TeaVMTestPlatform();
        TeaVMWebsocketTransport transportA = new TeaVMWebsocketTransport(platform);
        TeaVMWebsocketTransport transportB = new TeaVMWebsocketTransport(platform);
        JSObject socketA = newBrowserSocketStub();
        JSObject socketB = newBrowserSocketStub();
        transportA.setWs(socketA);
        transportB.setWs(socketB);

        for (int i = 0; i < STRESS_MESSAGES; i++) {
            transportA.send("a2b:" + i).await();
            transportB.send("b2a:" + i).await();
        }

        assertEquals(STRESS_MESSAGES, getSentSize(socketA));
        assertEquals(STRESS_MESSAGES, getSentSize(socketB));
        for (int i = 0; i < STRESS_MESSAGES; i++) {
            assertEquals("a2b:" + i, getSentItem(socketA, i));
            assertEquals("b2a:" + i, getSentItem(socketB, i));
        }
    }

    @JSBody(
        script = "var sent=[]; return {sent: sent, getReadyState:function(){return 1;}, send:function(data){sent.push(String(data));}};"
    )
    private static native JSObject newBrowserSocketStub();

    @JSBody(params = { "socket" }, script = "return socket.sent.length;")
    private static native int getSentSize(Object socket);

    @JSBody(params = { "socket", "index" }, script = "return socket.sent[index];")
    private static native String getSentItem(Object socket, int index);

    private static final class TeaVMTestPlatform extends TeaVMPlatform {

        @Override
        public <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor) {
            return wrapPromise(func);
        }

        @Override
        public <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
            final Object[] result = new Object[1];
            final Throwable[] error = new Throwable[1];
            final boolean[] completed = new boolean[] { false };
            try {
                func.accept(
                    value -> {
                        if (!completed[0]) {
                            result[0] = value;
                            completed[0] = true;
                        }
                    },
                    err -> {
                        if (!completed[0]) {
                            error[0] = err;
                            completed[0] = true;
                        }
                    }
                );
            } catch (Throwable t) {
                error[0] = t;
                completed[0] = true;
            }
            if (!completed[0]) {
                return ImmediateAsyncTask.failed(new IllegalStateException("Test promise did not complete synchronously"));
            }
            if (error[0] != null) {
                return ImmediateAsyncTask.failed(error[0]);
            }
            return ImmediateAsyncTask.completed((T) result[0]);
        }

        @Override
        public AsyncExecutor newAsyncExecutor(Object hint) {
            return new AsyncExecutor() {
                @Override
                public <T> AsyncTask<T> run(Callable<T> r) {
                    try {
                        return ImmediateAsyncTask.completed(r.call());
                    } catch (Throwable t) {
                        return ImmediateAsyncTask.failed(t);
                    }
                }

                @Override
                public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit) {
                    return run(r);
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public Runnable registerFinalizer(Object obj, Runnable finalizer) {
            return () -> {
                // TeaVM JUnit runtime does not provide the full finalizer bridge used in app runtime.
            };
        }
    }

    private static final class ImmediateAsyncTask<T> implements AsyncTask<T> {

        private final T value;
        private final Throwable error;

        private ImmediateAsyncTask(T value, Throwable error) {
            this.value = value;
            this.error = error;
        }

        static <T> ImmediateAsyncTask<T> completed(T value) {
            return new ImmediateAsyncTask<>(value, null);
        }

        static <T> ImmediateAsyncTask<T> failed(Throwable error) {
            return new ImmediateAsyncTask<>(null, error);
        }

        @Override
        public void cancel() {}

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public boolean isFailed() {
            return error != null;
        }

        @Override
        public boolean isSuccess() {
            return error == null;
        }

        @Override
        public T await() throws Exception {
            if (error == null) {
                return value;
            }
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new RuntimeException(error);
        }

        @Override
        public <R> AsyncTask<R> then(ThrowableFunction<T, R> func2) {
            if (error != null) {
                return failed(error);
            }
            try {
                return completed(func2.apply(value));
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> func2) {
            if (error != null) {
                return failed(error);
            }
            try {
                return func2.apply(value);
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public AsyncTask<T> catchException(Consumer<Throwable> func2) {
            if (error != null) {
                func2.accept(error);
            }
            return this;
        }
    }
}
