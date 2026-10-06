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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.Test;

public class JVMWebsocketTransportCloseHandshakeTest {

    private static final URI GUARDED_URI = URI.create("ws://8.8.8.8:80/close-handshake");
    private static final int TIMEOUT_SECONDS = 5;
    private static final byte[] SERVER_CLOSE = { (byte) 0x88, 2, 3, (byte) 0xe8 };

    @Test(timeout = 20000)
    public void serverCloseReceivesReciprocalCloseBeforeTcpEof() throws Exception {
        assertServerClose(false);
    }

    @Test(timeout = 20000)
    public void closeInUpgradeResponseReceivesReciprocalCloseBeforeTcpEof() throws Exception {
        assertServerClose(true);
    }

    private static void assertServerClose(boolean earlyClose) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        TestPlatform platform = null;
        JVMWebsocketTransport transport = null;
        try (LoopbackPeer peer = new LoopbackPeer(earlyClose)) {
            platform = new TestPlatform(new LoopbackClient(client, peer.uri()));
            transport = new JVMWebsocketTransport(platform, Runnable::run);
            CompletableFuture<Void> connected = new CompletableFuture<>();
            transport.connect(GUARDED_URI.toString()).then(value -> {
                connected.complete(null);
                return null;
            }).catchException(connected::completeExceptionally);
            if (!earlyClose) {
                connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                peer.beginClose.countDown();
            }

            CloseFrame reply = peer.reply.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("Close must be a final control frame with no reserved bits", 0x88, reply.firstByte);
            assertTrue("Client frames must be masked", reply.masked);
            assertEquals("Reply must contain the normal status and an empty reason", 2, reply.payload.length);
            int status = ((reply.payload[0] & 0xff) << 8) | (reply.payload[1] & 0xff);
            assertEquals(WebSocket.NORMAL_CLOSURE, status);
            assertFalse(transport.isConnected());
            // An early server close may reject connect, but must never leave it pending.
            connected.handle((value, error) -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            try {
                if (transport != null) {
                    transport.close("test cleanup");
                }
            } finally {
                client.shutdownNow();
                if (platform != null) {
                    platform.executor.shutdownNow();
                }
            }
        }
    }

    private static final class LoopbackPeer implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicReference<Socket> accepted = new AtomicReference<>();
        private final CountDownLatch beginClose = new CountDownLatch(1);
        private final CompletableFuture<CloseFrame> reply = new CompletableFuture<>();
        private final Thread worker;
        private final boolean earlyClose;

        private LoopbackPeer(boolean earlyClose) throws IOException {
            this.earlyClose = earlyClose;
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            worker = Thread.ofVirtual().name("websocket-close-peer").start(this::run);
        }

        private URI uri() {
            return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/close-handshake");
        }

        private void run() {
            try (Socket socket = server.accept()) {
                accepted.set(socket);
                socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] response = upgrade(input);
                if (earlyClose) {
                    // Deliver HTTP 101 and the Close together, without waiting for buildAsync.
                    ByteArrayOutputStream combined = new ByteArrayOutputStream();
                    combined.write(response);
                    combined.write(SERVER_CLOSE);
                    output.write(combined.toByteArray());
                } else {
                    output.write(response);
                    output.flush();
                    if (!beginClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for the connected transport");
                    }
                    output.write(SERVER_CLOSE);
                }
                output.flush();
                CloseFrame frame = readFrame(input);
                assertEquals("Transport must release the socket after replying", -1, input.read());
                reply.complete(frame);
            } catch (Throwable error) {
                reply.completeExceptionally(error);
            }
        }

        @Override
        public void close() throws Exception {
            beginClose.countDown();
            try {
                server.close();
            } finally {
                try {
                    Socket socket = accepted.get();
                    if (socket != null) {
                        socket.close();
                    }
                } finally {
                    worker.interrupt();
                    worker.join(TIMEOUT_SECONDS * 1000L);
                    assertFalse("Loopback peer must stop", worker.isAlive());
                }
            }
        }
    }

    private static byte[] upgrade(InputStream input) throws Exception {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        int terminator = 0;
        while (terminator != 0x0d0a0d0a) {
            int next = readByte(input);
            request.write(next);
            if (request.size() > 8192) {
                throw new IOException("WebSocket upgrade headers exceed test limit");
            }
            terminator = (terminator << 8) | next;
        }
        String[] lines = request.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        assertEquals("GET /close-handshake HTTP/1.1", lines[0]);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        assertEquals("websocket", headers.get("upgrade").toLowerCase(Locale.ROOT));
        assertEquals("upgrade", headers.get("connection").toLowerCase(Locale.ROOT));
        assertEquals("13", headers.get("sec-websocket-version"));
        String key = headers.get("sec-websocket-key");
        assertEquals(16, Base64.getDecoder().decode(key).length);
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)
        );
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
            Base64.getEncoder().encodeToString(digest) + "\r\n\r\n";
        return response.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static CloseFrame readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            throw new EOFException("TCP closed before the reciprocal WebSocket Close frame");
        }
        int second = readByte(input);
        int length = second & 0x7f;
        if (length > 125) {
            throw new IOException("A Close control frame cannot use an extended payload length");
        }
        boolean masked = (second & 0x80) != 0;
        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < mask.length; i++) {
                mask[i] = (byte) readByte(input);
            }
        }
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (readByte(input) ^ (masked ? mask[i % mask.length] : 0));
        }
        return new CloseFrame(first, masked, payload);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated WebSocket handshake or frame");
        }
        return value;
    }

    private record CloseFrame(int firstByte, boolean masked, byte[] payload) {}

    private static final class TestPlatform extends JVMAsyncPlatform {
        private final HttpClient client;

        private TestPlatform(HttpClient client) {
            this.client = client;
        }

        @Override
        HttpClient getWebsocketHttpClient() {
            return client;
        }
    }

    // Keep the production URI guard active without changing static safety flags or system properties.
    // Only this controlled builder replaces the fixture URI with the real loopback peer's address.
    private static final class LoopbackClient extends HttpClient {
        private final HttpClient delegate;
        private final URI loopback;

        private LoopbackClient(HttpClient delegate, URI loopback) {
            this.delegate = delegate;
            this.loopback = loopback;
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            WebSocket.Builder builder = delegate.newWebSocketBuilder();
            return new WebSocket.Builder() {
                @Override
                public WebSocket.Builder header(String name, String value) {
                    builder.header(name, value);
                    return this;
                }

                @Override
                public WebSocket.Builder connectTimeout(Duration timeout) {
                    builder.connectTimeout(timeout);
                    return this;
                }

                @Override
                public WebSocket.Builder subprotocols(String first, String... rest) {
                    builder.subprotocols(first, rest);
                    return this;
                }

                @Override
                public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                    assertEquals(GUARDED_URI, uri);
                    return builder.buildAsync(loopback, listener);
                }
            };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }

        @Override
        public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }

        @Override
        public Redirect followRedirects() { return delegate.followRedirects(); }

        @Override
        public Optional<ProxySelector> proxy() { return delegate.proxy(); }

        @Override
        public SSLContext sslContext() { return delegate.sslContext(); }

        @Override
        public SSLParameters sslParameters() { return delegate.sslParameters(); }

        @Override
        public Optional<Authenticator> authenticator() { return delegate.authenticator(); }

        @Override
        public Version version() { return delegate.version(); }

        @Override
        public Optional<Executor> executor() { return delegate.executor(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }
    }
}
