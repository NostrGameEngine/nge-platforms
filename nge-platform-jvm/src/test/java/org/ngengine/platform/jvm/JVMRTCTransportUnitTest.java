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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

public class JVMRTCTransportUnitTest {

    private static final int STRESS_MESSAGES = 256;

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

    @Test
    @SuppressWarnings({ "deprecation", "rawtypes", "unchecked" })
    public void deprecatedWritePreservesOrderUnderStressInBothDirections() throws Exception {
        JVMRTCTransport transportA = newTransportOrSkip();
        JVMRTCTransport transportB = newTransportOrSkip();
        setField(transportA, "connId", "jvm-a");
        setField(transportB, "connId", "jvm-b");

        RecordingChannel channelA = new RecordingChannel("nostr4j-jvm-a");
        RecordingChannel channelB = new RecordingChannel("nostr4j-jvm-b");
        ((Map) getField(transportA, "channels")).put(new Object(), channelA);
        ((Map) getField(transportB, "channels")).put(new Object(), channelB);

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

    @Test(timeout = 30000)
    public void customChannelCreatedOnInitiatorIsAvailableOnResponder() throws Exception {
        JVMRTCTransport initiator = newTransportOrSkip();
        JVMRTCTransport responder = newTransportOrSkip();

        AsyncExecutor initiatorExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-initiator");
        AsyncExecutor responderExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-responder");

        String customLabel = "custom-" + System.nanoTime();
        CountDownLatch initiatorReady = new CountDownLatch(1);
        CountDownLatch responderReady = new CountDownLatch(1);

        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(20)
        );

        try {
            initiator.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        responder.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            initiatorReady.countDown();
                        }
                    }
                }
            );

            responder.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        initiator.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            responderReady.countDown();
                        }
                    }
                }
            );

            initiator.start(settings, initiatorExecutor, "offerer-" + System.nanoTime(), Collections.emptyList());
            responder.start(settings, responderExecutor, "answerer-" + System.nanoTime(), Collections.emptyList());

            String offer = initiator.listen().await();
            String answer = responder.connect(offer).await();
            initiator.connect(answer).await();

            AsyncTask<RTCDataChannel> responderAwaited = responder.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );
            AsyncTask<RTCDataChannel> initiatorCreated = initiator.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );

            RTCDataChannel initiatorChannel = initiatorCreated.await();
            RTCDataChannel responderChannel = responderAwaited.await();

            assertEquals(customLabel, initiatorChannel.getName());
            assertEquals(customLabel, responderChannel.getName());
            assertNotNull(initiator.getDataChannel(customLabel));
            assertNotNull(responder.getDataChannel(customLabel));
            assertTrue("Initiator custom channel should become ready", initiatorReady.await(10, TimeUnit.SECONDS));
            assertTrue("Responder custom channel should become ready", responderReady.await(10, TimeUnit.SECONDS));
        } finally {
            try {
                initiator.close();
            } finally {
                responder.close();
            }
            initiatorExecutor.close();
            responderExecutor.close();
        }
    }

    @Test(timeout = 40000)
    public void customChannelConcurrentBidirectionalSendDeliversToBothPeers() throws Exception {
        JVMRTCTransport initiator = newTransportOrSkip();
        JVMRTCTransport responder = newTransportOrSkip();

        AsyncExecutor initiatorExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-initiator-bidi");
        AsyncExecutor responderExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-responder-bidi");

        String customLabel = "custom-bidi-" + System.nanoTime();
        byte[] payloadFromInitiator = new byte[] { 11, 22, 33, 44 };
        byte[] payloadFromResponder = new byte[] { 55, 66, 77, 88 };

        CountDownLatch initiatorReady = new CountDownLatch(1);
        CountDownLatch responderReady = new CountDownLatch(1);
        CountDownLatch initiatorReceived = new CountDownLatch(1);
        CountDownLatch responderReceived = new CountDownLatch(1);
        AtomicInteger initiatorReceiveCount = new AtomicInteger();
        AtomicInteger responderReceiveCount = new AtomicInteger();

        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(20)
        );

        try {
            initiator.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        responder.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            initiatorReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                        if (!customLabel.equals(channel.getName())) {
                            return;
                        }
                        byte[] bytes = new byte[msg.remaining()];
                        msg.duplicate().get(bytes);
                        if (java.util.Arrays.equals(payloadFromResponder, bytes)) {
                            initiatorReceiveCount.incrementAndGet();
                            initiatorReceived.countDown();
                        }
                    }
                }
            );

            responder.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        initiator.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            responderReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                        if (!customLabel.equals(channel.getName())) {
                            return;
                        }
                        byte[] bytes = new byte[msg.remaining()];
                        msg.duplicate().get(bytes);
                        if (java.util.Arrays.equals(payloadFromInitiator, bytes)) {
                            responderReceiveCount.incrementAndGet();
                            responderReceived.countDown();
                        }
                    }
                }
            );

            initiator.start(settings, initiatorExecutor, "offerer-bidi-" + System.nanoTime(), Collections.emptyList());
            responder.start(settings, responderExecutor, "answerer-bidi-" + System.nanoTime(), Collections.emptyList());

            String offer = initiator.listen().await();
            String answer = responder.connect(offer).await();
            initiator.connect(answer).await();

            AsyncTask<RTCDataChannel> responderAwaited = responder.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );
            AsyncTask<RTCDataChannel> initiatorCreated = initiator.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );

            RTCDataChannel initiatorChannel = initiatorCreated.await();
            RTCDataChannel responderChannel = responderAwaited.await();

            assertTrue("Initiator custom channel should become ready", initiatorReady.await(10, TimeUnit.SECONDS));
            assertTrue("Responder custom channel should become ready", responderReady.await(10, TimeUnit.SECONDS));

            CountDownLatch sendersReady = new CountDownLatch(2);
            CountDownLatch startSend = new CountDownLatch(1);
            CountDownLatch sendCompleted = new CountDownLatch(2);
            AtomicReference<Throwable> sendError = new AtomicReference<>();

            Thread sendFromInitiator = new Thread(
                () -> {
                    try {
                        sendersReady.countDown();
                        startSend.await(5, TimeUnit.SECONDS);
                        initiatorChannel.write(ByteBuffer.wrap(payloadFromInitiator)).await();
                    } catch (Throwable t) {
                        sendError.compareAndSet(null, t);
                    } finally {
                        sendCompleted.countDown();
                    }
                },
                "rtc-send-initiator"
            );

            Thread sendFromResponder = new Thread(
                () -> {
                    try {
                        sendersReady.countDown();
                        startSend.await(5, TimeUnit.SECONDS);
                        responderChannel.write(ByteBuffer.wrap(payloadFromResponder)).await();
                    } catch (Throwable t) {
                        sendError.compareAndSet(null, t);
                    } finally {
                        sendCompleted.countDown();
                    }
                },
                "rtc-send-responder"
            );

            sendFromInitiator.start();
            sendFromResponder.start();

            assertTrue("Sender threads did not synchronize", sendersReady.await(5, TimeUnit.SECONDS));
            startSend.countDown();
            assertTrue("Concurrent sends did not complete", sendCompleted.await(10, TimeUnit.SECONDS));
            if (sendError.get() != null) {
                throw new AssertionError("Concurrent send failed", sendError.get());
            }

            assertTrue("Initiator should receive responder payload", initiatorReceived.await(10, TimeUnit.SECONDS));
            assertTrue("Responder should receive initiator payload", responderReceived.await(10, TimeUnit.SECONDS));
            assertEquals(1, initiatorReceiveCount.get());
            assertEquals(1, responderReceiveCount.get());
        } finally {
            try {
                initiator.close();
            } finally {
                responder.close();
            }
            initiatorExecutor.close();
            responderExecutor.close();
        }
    }

    @Test(timeout = 40000)
    public void customChannelConcurrentBidirectionalSendQueuedBeforeHandshakeDeliversToBothPeers() throws Exception {
        JVMRTCTransport initiator = newTransportOrSkip();
        JVMRTCTransport responder = newTransportOrSkip();

        AsyncExecutor initiatorExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-initiator-prehs");
        AsyncExecutor responderExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-responder-prehs");

        String customLabel = "custom-prehs-" + System.nanoTime();
        byte[] payloadFromInitiator = new byte[] { 1, 3, 5, 7 };
        byte[] payloadFromResponder = new byte[] { 2, 4, 6, 8 };

        CountDownLatch initiatorReady = new CountDownLatch(1);
        CountDownLatch responderReady = new CountDownLatch(1);
        CountDownLatch initiatorReceived = new CountDownLatch(1);
        CountDownLatch responderReceived = new CountDownLatch(1);
        AtomicInteger initiatorReceiveCount = new AtomicInteger();
        AtomicInteger responderReceiveCount = new AtomicInteger();

        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(20)
        );

        try {
            initiator.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        responder.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            initiatorReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                        if (!customLabel.equals(channel.getName())) {
                            return;
                        }
                        byte[] bytes = new byte[msg.remaining()];
                        msg.duplicate().get(bytes);
                        if (java.util.Arrays.equals(payloadFromResponder, bytes)) {
                            initiatorReceiveCount.incrementAndGet();
                            initiatorReceived.countDown();
                        }
                    }
                }
            );

            responder.addListener(
                new NoOpRtcTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        initiator.addRemoteIceCandidates(List.of(candidate));
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {
                        if (customLabel.equals(channel.getName())) {
                            responderReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                        if (!customLabel.equals(channel.getName())) {
                            return;
                        }
                        byte[] bytes = new byte[msg.remaining()];
                        msg.duplicate().get(bytes);
                        if (java.util.Arrays.equals(payloadFromInitiator, bytes)) {
                            responderReceiveCount.incrementAndGet();
                            responderReceived.countDown();
                        }
                    }
                }
            );

            initiator.start(settings, initiatorExecutor, "offerer-prehs-" + System.nanoTime(), Collections.emptyList());
            responder.start(settings, responderExecutor, "answerer-prehs-" + System.nanoTime(), Collections.emptyList());

            AsyncTask<RTCDataChannel> responderAwaited = responder.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );
            String offer = initiator.listen().await();
            AsyncTask<RTCDataChannel> initiatorCreated = initiator.createDataChannel(
                customLabel,
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );

            CountDownLatch sendersReady = new CountDownLatch(2);
            CountDownLatch startSend = new CountDownLatch(1);
            CountDownLatch sendCompleted = new CountDownLatch(2);
            AtomicReference<Throwable> sendError = new AtomicReference<>();

            Thread sendFromInitiator = new Thread(
                () -> {
                    try {
                        sendersReady.countDown();
                        startSend.await(5, TimeUnit.SECONDS);
                        RTCDataChannel channel = initiatorCreated.await();
                        channel.write(ByteBuffer.wrap(payloadFromInitiator)).await();
                    } catch (Throwable t) {
                        sendError.compareAndSet(null, t);
                    } finally {
                        sendCompleted.countDown();
                    }
                },
                "rtc-send-prehs-initiator"
            );

            Thread sendFromResponder = new Thread(
                () -> {
                    try {
                        sendersReady.countDown();
                        startSend.await(5, TimeUnit.SECONDS);
                        RTCDataChannel channel = responderAwaited.await();
                        channel.write(ByteBuffer.wrap(payloadFromResponder)).await();
                    } catch (Throwable t) {
                        sendError.compareAndSet(null, t);
                    } finally {
                        sendCompleted.countDown();
                    }
                },
                "rtc-send-prehs-responder"
            );

            sendFromInitiator.start();
            sendFromResponder.start();

            assertTrue("Sender threads did not synchronize", sendersReady.await(5, TimeUnit.SECONDS));
            startSend.countDown();

            String answer = responder.connect(offer).await();
            initiator.connect(answer).await();

            assertTrue("Concurrent pre-handshake sends did not complete", sendCompleted.await(10, TimeUnit.SECONDS));
            if (sendError.get() != null) {
                throw new AssertionError("Concurrent pre-handshake send failed", sendError.get());
            }

            assertTrue("Initiator custom channel should become ready", initiatorReady.await(10, TimeUnit.SECONDS));
            assertTrue("Responder custom channel should become ready", responderReady.await(10, TimeUnit.SECONDS));
            assertTrue("Initiator should receive responder payload", initiatorReceived.await(10, TimeUnit.SECONDS));
            assertTrue("Responder should receive initiator payload", responderReceived.await(10, TimeUnit.SECONDS));
            assertEquals(1, initiatorReceiveCount.get());
            assertEquals(1, responderReceiveCount.get());
        } finally {
            try {
                initiator.close();
            } finally {
                responder.close();
            }
            initiatorExecutor.close();
            responderExecutor.close();
        }
    }

    @Test(timeout = 20000)
    public void pendingChannelReadyFailsWhenTransportCloses() throws Exception {
        JVMRTCTransport initiator = newTransportOrSkip();
        AsyncExecutor initiatorExecutor = NGEUtils.getPlatform().newAsyncExecutor("jvm-rtc-test-close-pending");
        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(20)
        );

        try {
            initiator.start(settings, initiatorExecutor, "offerer-close-" + System.nanoTime(), Collections.emptyList());
            initiator.listen().await();

            AsyncTask<RTCDataChannel> pendingReady = initiator.createDataChannel(
                "custom-close-" + System.nanoTime(),
                "unit",
                true,
                true,
                0,
                Duration.ZERO
            );

            AtomicReference<Throwable> awaitError = new AtomicReference<>();
            CountDownLatch awaitDone = new CountDownLatch(1);
            CountDownLatch awaitStarted = new CountDownLatch(1);

            Thread waiter = new Thread(
                () -> {
                    try {
                        awaitStarted.countDown();
                        pendingReady.await();
                    } catch (Throwable t) {
                        awaitError.set(t);
                    } finally {
                        awaitDone.countDown();
                    }
                },
                "rtc-await-pending-ready"
            );

            waiter.start();
            assertTrue("Await thread did not start", awaitStarted.await(2, TimeUnit.SECONDS));
            initiator.close();

            assertTrue("Pending ready() await should stop after transport close", awaitDone.await(5, TimeUnit.SECONDS));
            assertNotNull("Pending ready() should fail on transport close", awaitError.get());
        } finally {
            initiator.close();
            initiatorExecutor.close();
        }
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

    private abstract static class NoOpRtcTransportListener implements RTCTransportListener {

        @Override
        public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {}

        @Override
        public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {}

        @Override
        public void onRTCChannelError(RTCDataChannel channel, Throwable e) {}

        @Override
        public void onRTCChannelReady(RTCDataChannel channel) {}

        @Override
        public void onRTCBufferedAmountLow(RTCDataChannel channel) {}

        @Override
        public void onRTCChannelClosed(RTCDataChannel channel) {}

        @Override
        public void onRTCDisconnected(String reason) {}

        @Override
        public void onRTCConnected() {}
    }
}
