package org.ngengine.platform.jvm;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.Test;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class JVMWebsocketTransportUnitTest {
    private static final int STRESS_MESSAGES = 256;

    @Test
    public void sendPreservesOrderUnderStressInBothDirections() throws Exception {
        JVMWebsocketTransport transportA = new JVMWebsocketTransport(new JVMAsyncPlatform(), Runnable::run);
        JVMWebsocketTransport transportB = new JVMWebsocketTransport(new JVMAsyncPlatform(), Runnable::run);
        RecordingWebSocket socketA = new RecordingWebSocket();
        RecordingWebSocket socketB = new RecordingWebSocket();
        setField(transportA, "openWebSocket", socketA);
        setField(transportB, "openWebSocket", socketB);

        for (int i = 0; i < STRESS_MESSAGES; i++) {
            transportA.send("a2b:" + i).await();
            transportB.send("b2a:" + i).await();
        }

        assertEquals(STRESS_MESSAGES, socketA.sentTexts.size());
        assertEquals(STRESS_MESSAGES, socketB.sentTexts.size());
        for (int i = 0; i < STRESS_MESSAGES; i++) {
            assertEquals("a2b:" + i, socketA.sentTexts.get(i));
            assertEquals("b2a:" + i, socketB.sentTexts.get(i));
        }
    }

    @Test
    public void inboundTextCallbacksPreserveOrderUnderStress() throws Exception {
        JVMWebsocketTransport transport = new JVMWebsocketTransport(new JVMAsyncPlatform(), Runnable::run);
        List<String> received = new ArrayList<>();
        transport.addListener(new WebsocketTransportListener() {
            @Override
            public void onConnectionMessage(String msg) {
                received.add(msg);
            }

            @Override
            public void onConnectionOpen() {}

            @Override
            public void onConnectionClosedByServer(String reason) {}

            @Override
            public void onConnectionClosedByClient(String reason) {}

            @Override
            public void onConnectionError(Throwable e) {}
        });
        RecordingWebSocket socket = new RecordingWebSocket();

        for (int i = 0; i < STRESS_MESSAGES; i++) {
            transport.onText(socket, "rx:" + i, true);
        }

        assertEquals(STRESS_MESSAGES, received.size());
        for (int i = 0; i < STRESS_MESSAGES; i++) {
            assertEquals("rx:" + i, received.get(i));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class RecordingWebSocket implements WebSocket {
        private final List<String> sentTexts = new ArrayList<>();
        private volatile boolean outputClosed = false;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(String.valueOf(data));
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {}

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return outputClosed;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {}
    }
}
