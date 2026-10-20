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
package org.ngengine.platform.android;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Uncached availability check for public UDP STUN servers, independent of ICE gathering. */
final class STUNChecker {

    private static final int MAGIC_COOKIE = 0x2112a442;
    private static final int TIMEOUT_MILLIS = 500;

    private STUNChecker() {}

    /** Checks a host[:port] endpoint without using its mapped address as an ICE candidate. */
    static boolean check(String server) {
        if (server == null || Thread.currentThread().isInterrupted()) return false;
        try {
            URI uri = new URI("stun://" + server);
            int port = uri.getPort() == -1 ? 3478 : uri.getPort();
            if (
                uri.getHost() == null ||
                port < 1 ||
                port > 65535 ||
                uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null ||
                !uri.getRawPath().isEmpty()
            ) return false;

            byte[] request = new byte[20];
            new SecureRandom().nextBytes(request);
            ByteBuffer.wrap(request).putShort((short) 0x0001).putShort((short) 0).putInt(MAGIC_COOKIE);
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (Thread.currentThread().isInterrupted()) return false;
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.connect(new InetSocketAddress(address, port));
                    byte[] response = new byte[2048];
                    for (int attempt = 0; attempt < 2 && !Thread.currentThread().isInterrupted(); attempt++) {
                        socket.send(new DatagramPacket(request, request.length));
                        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
                        for (int received = 0; received < 16; received++) {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0 || Thread.currentThread().isInterrupted()) break;
                            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                            DatagramPacket packet = new DatagramPacket(response, response.length);
                            try {
                                socket.receive(packet);
                            } catch (SocketTimeoutException timeout) {
                                break;
                            }
                            int length = packet.getLength();
                            if (length < 20) continue;
                            ByteBuffer message = ByteBuffer.wrap(response, 0, length);
                            int type = Short.toUnsignedInt(message.getShort());
                            int bodyLength = Short.toUnsignedInt(message.getShort());
                            if (
                                type != 0x0101 ||
                                bodyLength % 4 != 0 ||
                                bodyLength + 20 != length ||
                                message.getInt() != MAGIC_COOKIE ||
                                !Arrays.equals(request, 8, 20, response, 8, 20)
                            ) continue;

                            boolean mappedAddress = false;
                            boolean valid = true;
                            for (int offset = 20; offset < length;) {
                                if (length - offset < 4) {
                                    valid = false;
                                    break;
                                }
                                int attribute = Short.toUnsignedInt(message.getShort(offset));
                                int size = Short.toUnsignedInt(message.getShort(offset + 2));
                                int next = offset + 4 + ((size + 3) & ~3);
                                if (next > length) {
                                    valid = false;
                                    break;
                                }
                                if (attribute == 0x0020) {
                                    int family = size >= 2 ? Byte.toUnsignedInt(response[offset + 5]) : 0;
                                    if (
                                        !((family == 1 && size == 8) || (family == 2 && size == 20)) ||
                                        (Short.toUnsignedInt(message.getShort(offset + 6)) ^ 0x2112) == 0
                                    ) {
                                        valid = false;
                                        break;
                                    }
                                    mappedAddress = true;
                                }
                                if (attribute == 0x8028) {
                                    if (size != 4 || next != length) {
                                        valid = false;
                                        break;
                                    }
                                    CRC32 crc = new CRC32();
                                    crc.update(response, 0, offset);
                                    if ((crc.getValue() ^ 0x5354554eL) != Integer.toUnsignedLong(message.getInt(offset + 4))) {
                                        valid = false;
                                        break;
                                    }
                                }
                                offset = next;
                            }
                            if (valid && mappedAddress) return true;
                        }
                    }
                } catch (IOException | SecurityException unavailable) {
                    // Try another address family when the host resolves to multiple addresses.
                }
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException | SecurityException unavailable) {
            return false;
        }
        return false;
    }
}
