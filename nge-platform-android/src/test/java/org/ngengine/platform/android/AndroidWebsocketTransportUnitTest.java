package org.ngengine.platform.android;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import okhttp3.WebSocket;
import okio.ByteString;

public class AndroidWebsocketTransportUnitTest {
    private static final int STRESS_MESSAGES = 256;

    @Test
    public void sendPreservesOrderUnderStressInBothDirections() throws Exception {
        AndroidWebsocketTransport transportA = new AndroidWebsocketTransport(new AndroidThreadedPlatform(null), Runnable::run);
        AndroidWebsocketTransport transportB = new AndroidWebsocketTransport(new AndroidThreadedPlatform(null), Runnable::run);
        RecordingWebSocket socketA = new RecordingWebSocket();
        RecordingWebSocket socketB = new RecordingWebSocket();
        setField(transportA, "webSocket", socketA);
        setField(transportB, "webSocket", socketB);
        setField(transportA, "isClosing", false);
        setField(transportB, "isClosing", false);

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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class RecordingWebSocket implements WebSocket {
        private final List<String> sentTexts = new ArrayList<>();

        @Override
        public boolean send(String text) {
            sentTexts.add(text);
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        @Override
        public void cancel() {}

        @Override
        public long queueSize() {
            return 0L;
        }

        @Override
        public okhttp3.Request request() {
            return new okhttp3.Request.Builder().url("ws://localhost").build();
        }
    }
}
