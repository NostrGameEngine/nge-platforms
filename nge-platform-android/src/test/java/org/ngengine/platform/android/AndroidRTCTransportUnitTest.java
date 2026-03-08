package org.ngengine.platform.android;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCDataChannel ;

public class AndroidRTCTransportUnitTest {
    private static final int STRESS_MESSAGES = 256;

    @Test
    @SuppressWarnings({ "deprecation", "rawtypes", "unchecked" })
    public void deprecatedWriteDelegatesToDefaultChannel() throws Exception {
        AndroidRTCTransport transport = new AndroidRTCTransport();
        setField(transport, "connId", "android-unit");

        RecordingChannel channel = new RecordingChannel("nostr4j-android-unit");
        Map map = (Map) getField(transport, "channelWrappers");
        map.put(new Object(), channel);

        byte[] payload = new byte[] { 9, 8, 7 };
        transport.write(ByteBuffer.wrap(payload)).await();

        assertEquals(1, channel.writeCount.get());
        assertArrayEquals(payload, channel.lastWrite);
        assertSame(channel, transport.getDataChannel(RTCTransport.DEFAULT_CHANNEL));
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void closeRejectsPendingReadyTasks() throws Exception {
        AndroidRTCTransport transport = new AndroidRTCTransport();
        Map rejectors = (Map) getField(transport, "pendingReadyRejectors");
        AtomicReference<Exception> rejected = new AtomicReference<>();
        rejectors.put(new Object(), (Consumer<Exception>) rejected::set);

        transport.close();

        assertNotNull("close() should reject pending ready tasks", rejected.get());
        assertEquals("Transport closed", rejected.get().getMessage());
    }

    @Test
    @SuppressWarnings({ "deprecation", "rawtypes", "unchecked" })
    public void deprecatedWritePreservesOrderUnderStressInBothDirections() throws Exception {
        AndroidRTCTransport transportA = new AndroidRTCTransport();
        AndroidRTCTransport transportB = new AndroidRTCTransport();
        setField(transportA, "connId", "android-a");
        setField(transportB, "connId", "android-b");

        RecordingChannel channelA = new RecordingChannel("nostr4j-android-a");
        RecordingChannel channelB = new RecordingChannel("nostr4j-android-b");

        ((Map) getField(transportA, "channelWrappers")).put(new Object(), channelA);
        ((Map) getField(transportB, "channelWrappers")).put(new Object(), channelB);

        for (int i = 0; i < STRESS_MESSAGES; i++) {
            transportA.write(ByteBuffer.wrap(("a2b:" + i).getBytes(StandardCharsets.UTF_8))).await();
            transportB.write(ByteBuffer.wrap(("b2a:" + i).getBytes(StandardCharsets.UTF_8))).await();
        }

        assertEquals(STRESS_MESSAGES, channelA.writes.size());
        assertEquals(STRESS_MESSAGES, channelB.writes.size());
        for (int i = 0; i < STRESS_MESSAGES; i++) {
            assertEquals("a2b:" + i, new String(channelA.writes.get(i), StandardCharsets.UTF_8));
            assertEquals("b2a:" + i, new String(channelB.writes.get(i), StandardCharsets.UTF_8));
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

    private static final class RecordingChannel extends RTCDataChannel {
        private final AtomicInteger writeCount = new AtomicInteger();
        private volatile byte[] lastWrite;
        private final List<byte[]> writes = new ArrayList<>();

        RecordingChannel(String name) {
            super(name, "", true, true, -1, null);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return ImmediateAsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer message) {
            byte[] data = new byte[message.remaining()];
            message.duplicate().get(data);
            lastWrite = data;
            writeCount.incrementAndGet();
            writes.add(data);
            return ImmediateAsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Number> getMaxMessageSize() {
            return ImmediateAsyncTask.completed(0);
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
            if (error instanceof Exception) throw (Exception) error;
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
        public AsyncTask<T> catchException(Consumer<Throwable> func2) {
            if (error != null) func2.accept(error);
            return this;
        }
    }
}
