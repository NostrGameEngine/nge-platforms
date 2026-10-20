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
package org.ngengine.platform.ios;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import tel.schich.libdatachannel.LibDataChannelArchDetect;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

public class STUNCheckerTest {

    @Test
    public void acceptsBindingSuccessAndChecksTheRequestFormat() throws Exception {
        try (Server server = new Server(STUNCheckerTest::response)) {
            assertTrue(STUNChecker.check(server.endpoint()));
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    public void acceptsIpv6MappedAddressesAndValidFingerprints() throws Exception {
        try (
            Server server = new Server(request -> {
                byte[] reply = Arrays.copyOf(response(request), 52);
                ByteBuffer message = ByteBuffer.wrap(reply);
                message.putShort(2, (short) 32);
                message.putShort(22, (short) 20);
                reply[25] = 2;
                message.putShort(44, (short) 0x8028).putShort(46, (short) 4);
                CRC32 crc = new CRC32();
                crc.update(reply, 0, 44);
                message.putInt(48, (int) (crc.getValue() ^ 0x5354554eL));
                return reply;
            })
        ) {
            assertTrue(STUNChecker.check(server.endpoint()));
        }
    }

    @Test
    public void rejectsMalformedUnrelatedAndErrorResponses() throws Exception {
        for (int variant = 0; variant < 10; variant++) {
            final int malformed = variant;
            try (
                Server server = new Server(request -> {
                    byte[] reply = response(request);
                    if (malformed == 0) reply[8] ^= 1; // Wrong transaction ID.
                    if (malformed == 1) reply[4] ^= 1; // Wrong magic cookie.
                    if (malformed == 2) reply[1] = 0x11; // Binding error, not success.
                    if (malformed == 3) reply[3] = 16; // Incorrect body length.
                    if (malformed == 4) return Arrays.copyOf(reply, 10);
                    if (malformed == 5) reply[25] = 3; // Unsupported address family.
                    if (malformed == 6) reply[21] = 0x22; // Missing XOR-MAPPED-ADDRESS.
                    if (malformed == 7) {
                        ByteBuffer.wrap(reply).putShort(20, (short) 0x8028).putShort(22, (short) 4);
                        return reply; // Invalid fingerprint and trailing data.
                    }
                    if (malformed == 8) {
                        reply = Arrays.copyOf(reply, 40);
                        ByteBuffer.wrap(reply).putShort(2, (short) 20).putShort(32, (short) 0x8028).putShort(34, (short) 4);
                    }
                    if (malformed == 9) ByteBuffer.wrap(reply).putShort(22, (short) 65535);
                    return reply;
                })
            ) {
                assertFalse("Malformed reply " + variant, STUNChecker.check(server.endpoint()));
                assertEquals(2, server.requests.get());
            }
        }
    }

    @Test
    public void retriesOnceAndDoesNotCacheEitherResult() throws Exception {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<byte[]> first = new AtomicReference<>();
        try (
            Server server = new Server(request -> {
                int n = count.incrementAndGet();
                if (n == 1) {
                    first.set(request);
                    return null;
                }
                if (n == 2) {
                    assertArrayEquals("A retransmission keeps its transaction ID", first.get(), request);
                    return response(request);
                }
                if (n <= 4) return null;
                return response(request);
            })
        ) {
            assertTrue(STUNChecker.check(server.endpoint()));
            assertFalse(STUNChecker.check(server.endpoint()));
            assertTrue(STUNChecker.check(server.endpoint()));
            assertEquals(5, server.requests.get());
        }
    }

    @Test
    public void timeoutIsBoundedAndInvalidEndpointsAreRejected() throws Exception {
        try (Server server = new Server(request -> null)) {
            long start = System.nanoTime();
            assertFalse(STUNChecker.check(server.endpoint()));
            assertTrue(
                "A silent UDP endpoint must not wait indefinitely",
                System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)
            );
            assertEquals(2, server.requests.get());
        }
        for (String invalid : Arrays.asList(
            null,
            "",
            "127.0.0.1:0",
            "127.0.0.1:65536",
            "127.0.0.1:abc",
            "user@127.0.0.1",
            "127.0.0.1/path",
            "127.0.0.1?transport=tcp",
            "127.0.0.1#fragment",
            "stun://127.0.0.1"
        )) {
            assertFalse(String.valueOf(invalid), STUNChecker.check(invalid));
        }
        Thread.currentThread().interrupt();
        try {
            assertFalse(STUNChecker.check("127.0.0.1:3478"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void nativeTransportFiltersStunOncePerConnectionAndAllowsAnEmptyList() throws Exception {
        LibDataChannelArchDetect.initialize();
        AsyncExecutor executor = new AsyncExecutor() {
            public <T> AsyncTask<T> runLater(Callable<T> task, long delay, TimeUnit unit) {
                return null;
            }

            public <T> AsyncTask<T> run(Callable<T> task) {
                throw new AssertionError("No async work expected");
            }

            public void close() {}
        };
        try (Server good = new Server(STUNCheckerTest::response); Server silent = new Server(request -> null)) {
            IosRTCTransport transport = new IosRTCTransport();
            try {
                transport.start(
                    Duration.ofSeconds(30),
                    executor,
                    "stun-filter-test",
                    List.of(silent.endpoint(), good.endpoint())
                );
                Field field = IosRTCTransport.class.getDeclaredField("config");
                field.setAccessible(true);
                PeerConnectionConfiguration config = (PeerConnectionConfiguration) field.get(transport);
                assertEquals(List.of(new URI("stun:" + good.endpoint())), config.iceServers());
                assertEquals(1, good.requests.get());
                assertEquals(2, silent.requests.get());
            } finally {
                transport.close();
            }
            IosRTCTransport direct = new IosRTCTransport();
            try {
                direct.start(Duration.ofSeconds(30), executor, "stun-empty-test", List.of(silent.endpoint()));
                Field field = IosRTCTransport.class.getDeclaredField("config");
                field.setAccessible(true);
                PeerConnectionConfiguration config = (PeerConnectionConfiguration) field.get(direct);
                assertTrue(config.iceServers().isEmpty());
                assertEquals(4, silent.requests.get());
            } finally {
                direct.close();
            }
        }
    }

    private static byte[] response(byte[] request) {
        assertEquals(20, request.length);
        ByteBuffer input = ByteBuffer.wrap(request);
        assertEquals(1, input.getShort());
        assertEquals(0, input.getShort());
        assertEquals(0x2112a442, input.getInt());
        byte[] reply = Arrays.copyOf(request, 32);
        ByteBuffer message = ByteBuffer.wrap(reply);
        message.putShort(0, (short) 0x0101).putShort(2, (short) 12);
        message.putShort(20, (short) 0x0020).putShort(22, (short) 8);
        reply[25] = 1;
        message.putShort(26, (short) (12345 ^ 0x2112));
        message.putInt(28, 0x7f000001 ^ 0x2112a442);
        return reply;
    }

    private static final class Server implements AutoCloseable {

        private final DatagramSocket socket;
        private final Thread thread;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Server(UnaryOperator<byte[]> responder) throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            socket.setSoTimeout(100);
            thread =
                new Thread(
                    () -> {
                        while (!socket.isClosed()) {
                            try {
                                DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                                socket.receive(packet);
                                requests.incrementAndGet();
                                byte[] reply = responder.apply(Arrays.copyOf(packet.getData(), packet.getLength()));
                                if (reply != null) socket.send(
                                    new DatagramPacket(reply, reply.length, packet.getSocketAddress())
                                );
                            } catch (SocketTimeoutException idle) {
                                // Recheck whether the test closed the server.
                            } catch (SocketException closed) {
                                if (!socket.isClosed()) failure.set(closed);
                                break;
                            } catch (Throwable error) {
                                failure.set(error);
                                break;
                            }
                        }
                    },
                    "stun-test-server"
                );
            thread.start();
        }

        String endpoint() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        public void close() throws Exception {
            socket.close();
            thread.join(2000);
            assertFalse("UDP test server must terminate", thread.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
