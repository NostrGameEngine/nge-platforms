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

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.transport.RTCTransport;

public class JVMRTCTransportUnitTest {

    @Test
    @SuppressWarnings({ "deprecation", "rawtypes", "unchecked" })
    public void deprecatedWriteUsesDefaultChannel() throws Exception {
        JVMRTCTransport transport = newTransportOrSkip();
        setField(transport, "connId", "unit-a");

        RecordingChannel channel = new RecordingChannel("nostr4j-unit-a");
        Map map = (Map) getField(transport, "channels");
        map.put(new Object(), channel);

        byte[] payload = new byte[] { 1, 2, 3, 4 };
        transport.write(ByteBuffer.wrap(payload)).await();

        assertEquals(1, channel.writeCount.get());
        assertArrayEquals(payload, channel.lastWrite);
        assertSame(channel, transport.getDataChannel(RTCTransport.DEFAULT_CHANNEL));
        assertSame(channel, transport.getDataChannel("nostr4j-unit-a"));
    }

    private static JVMRTCTransport newTransportOrSkip() {
        try {
            return new JVMRTCTransport();
        } catch (Throwable t) {
            Assume.assumeNoException("libdatachannel runtime not available for JVM RTC tests", t);
            throw new AssertionError(t);
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class RecordingChannel extends RTCTransport.RTCDataChannel {

        private final AtomicInteger writeCount = new AtomicInteger();
        private volatile byte[] lastWrite;

        RecordingChannel(String name) {
            super(name, "", true, true, -1, null);
        }

        @Override
        public AsyncTask<RTCTransport.RTCDataChannel> ready() {
            return ImmediateAsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer message) {
            byte[] data = new byte[message.remaining()];
            message.duplicate().get(data);
            lastWrite = data;
            writeCount.incrementAndGet();
            return ImmediateAsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Number> getMaxMessageSize() {
            return ImmediateAsyncTask.completed(-1);
        }

        @Override
        public AsyncTask<Number> getAvailableAmount() {
            return ImmediateAsyncTask.completed(0);
        }

        @Override
        public AsyncTask<Number> getBufferedAmount() {
            return ImmediateAsyncTask.completed(0);
        }

        @Override
        public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
            return ImmediateAsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> close() {
            return ImmediateAsyncTask.completed(null);
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
            if (error == null) return value;
            if (error instanceof Exception e) throw e;
            throw new RuntimeException(error);
        }

        @Override
        public <R> AsyncTask<R> then(ThrowableFunction<T, R> func2) {
            if (error != null) return failed(error);
            try {
                return completed(func2.apply(value));
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> func2) {
            if (error != null) return failed(error);
            try {
                return func2.apply(value);
            } catch (Throwable t) {
                return failed(t);
            }
        }

        @Override
        public AsyncTask<T> catchException(java.util.function.Consumer<Throwable> func2) {
            if (error != null) func2.accept(error);
            return this;
        }
    }
}
