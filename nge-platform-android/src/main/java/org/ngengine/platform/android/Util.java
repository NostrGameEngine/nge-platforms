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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

class Util {


    public static byte[] bytesFromInt(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array();
    }

    public static byte[] bytesFromBigInteger(BigInteger n) {
        byte[] b = n.toByteArray();

        if (b.length == 32) {
            return b;
        }
        if (b.length > 32) {
            return Arrays.copyOfRange(b, b.length - 32, b.length);
        }

        byte[] buf = new byte[32];
        System.arraycopy(b, 0, buf, buf.length - b.length, b.length);
        return buf;
    }

    public static BigInteger bigIntFromBytes(byte[] b) {
        return new BigInteger(1, b);
    }

    public static BigInteger bigIntFromBytes(byte[] bytes, int offset, int length) {
        if (bytes.length == 0) return BigInteger.ZERO;

        // Calculate actual data length by skipping leading zeros
        int dataStart = offset;
        while (dataStart < offset + length && bytes[dataStart] == 0) {
            dataStart++;
        }

        // If all bytes are zero, return zero
        if (dataStart >= offset + length) return BigInteger.ZERO;

        // Create a byte array with leading 0x00 to ensure positive representation
        int dataLength = offset + length - dataStart;
        byte[] result = new byte[dataLength + 1];
        System.arraycopy(bytes, dataStart, result, 1, dataLength);

        return new BigInteger(result);
    }

    public static byte[] xor(byte[] b0, byte[] b1, byte ret[]) {
        if (b0.length != b1.length) {
            throw new IllegalArgumentException("The length of the input arrays must be equal");
        }
        if (ret == null) {
            ret = new byte[b0.length];
        }
        if (ret.length != b0.length) {
            throw new IllegalArgumentException(
                "The length of the return array must be equal to the length of the input arrays"
            );
        }

        for (int i = 0; i < b0.length; i++) {
            ret[i] = (byte) (b0[i] ^ b1[i]);
        }

        return ret;
    }

    public static byte[] hchacha20(byte[] key, byte[] nonce16) {
        if (key.length != 32 || nonce16.length != 16) {
            throw new IllegalArgumentException("Wrong lengths for HChaCha20");
        }
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646E;
        state[2] = 0x79622D32;
        state[3] = 0x6B206574;
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i * 4);
        }
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce16, i * 4);
        }
        for (int i = 0; i < 10; i++) {
            quarterRound(state, 0, 4, 8, 12);
            quarterRound(state, 1, 5, 9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7, 8, 13);
            quarterRound(state, 3, 4, 9, 14);
        }
        byte[] subkey = new byte[32];
        for (int i = 0; i < 4; i++) {
            intToLittleEndian(state[i], subkey, i * 4);
        }
        for (int i = 0; i < 4; i++) {
            intToLittleEndian(state[12 + i], subkey, (i + 4) * 4);
        }
        return subkey;
    }

    private static void quarterRound(int[] s, int a, int b, int c, int d) {
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 16);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 12);
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 8);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 7);
    }

    private static int littleEndianToInt(byte[] bs, int off) {
        return (bs[off] & 0xFF) | ((bs[off + 1] & 0xFF) << 8) | ((bs[off + 2] & 0xFF) << 16) | ((bs[off + 3] & 0xFF) << 24);
    }

    private static void intToLittleEndian(int v, byte[] bs, int off) {
        bs[off] = (byte) v;
        bs[off + 1] = (byte) (v >>> 8);
        bs[off + 2] = (byte) (v >>> 16);
        bs[off + 3] = (byte) (v >>> 24);
    }

    public static Path safePath(Path basePath, String path, boolean create) throws IOException {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Path required");

        Path userPath = Paths.get(path);
        if (userPath.isAbsolute()) throw new IOException("Absolute paths not allowed");

        if (create && !Files.exists(basePath)) {
            Files.createDirectories(basePath);
        }

        Path baseReal = basePath.toRealPath();
        Path candidate = baseReal.resolve(path).normalize();

        if (!candidate.startsWith(baseReal)) throw new IOException("Traversal detected");

        if (create) {
            Files.createDirectories(candidate.getParent());
        } else if (!Files.isRegularFile(candidate)) {
            throw new IOException("File not found: " + path);
        }

        return candidate;
    }

  
}
