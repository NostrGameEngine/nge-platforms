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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class JVMByteBufferPlatformTest {

    private final JVMAsyncPlatform platform = new JVMAsyncPlatform();

    @Test
    public void sha256ConsumesDirectBufferWithoutChangingCallerPosition() {
        byte[] input = "direct SHA-256 payload".getBytes(StandardCharsets.UTF_8);
        ByteBuffer direct = nativeBuffer(input);
        int position = direct.position();

        ByteBuffer actual = platform.sha256(direct);

        assertEquals(position, direct.position());
        assertArrayEquals(platform.sha256(input), remainingBytes(actual));
    }

    @Test
    public void hmacConsumesDirectPayloadsWithoutChangingCallerPositions() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] data1 = "first direct HMAC payload".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "second direct HMAC payload".getBytes(StandardCharsets.UTF_8);
        ByteBuffer keyBuffer = nativeBuffer(key);
        ByteBuffer data1Buffer = nativeBuffer(data1);
        ByteBuffer data2Buffer = nativeBuffer(data2);

        ByteBuffer actual = platform.hmac(keyBuffer, data1Buffer, data2Buffer);

        assertEquals(0, keyBuffer.position());
        assertEquals(0, data1Buffer.position());
        assertEquals(0, data2Buffer.position());
        assertArrayEquals(platform.hmac(key, data1, data2), remainingBytes(actual));
    }

    @Test
    public void hmacBufferSupportsAbsentSecondPayload() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] data = "single direct HMAC payload".getBytes(StandardCharsets.UTF_8);

        ByteBuffer actual = platform.hmac(nativeBuffer(key), nativeBuffer(data), null);

        assertArrayEquals(platform.hmac(key, data, null), remainingBytes(actual));
    }

    private ByteBuffer nativeBuffer(byte[] data) {
        ByteBuffer buffer = platform.getNativeAllocator().malloc(data.length);
        assertTrue(buffer.isDirect());
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    private static byte[] remainingBytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.duplicate();
        byte[] data = new byte[source.remaining()];
        source.get(data);
        return data;
    }
}
