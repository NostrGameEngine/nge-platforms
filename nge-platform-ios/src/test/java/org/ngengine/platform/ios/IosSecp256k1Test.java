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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.ngengine.platform.NGEPlatform;

public class IosSecp256k1Test {

    private static final String PRIVATE_VALID = "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String PRIVATE_INVALID_ZERO = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String PRIVATE_INVALID_N = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141";
    private static final String PRIVATE_INVALID_N_PLUS_ONE = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364142";
    private static final String PUBLIC_COMPRESSED = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String PUBLIC_UNCOMPRESSED =
        "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8";

    @Test
    public void validatesSecp256k1SharedSecretInputs() {
        IosPlatform platform = new IosPlatform();
        byte[] privateKey = hex(PRIVATE_VALID);
        byte[] publicCompressed = hex(PUBLIC_COMPRESSED);
        byte[] publicUncompressed = hex(PUBLIC_UNCOMPRESSED);

        assertInvalidPrivateKey(platform, hex(PRIVATE_INVALID_ZERO), publicCompressed);
        assertInvalidPrivateKey(platform, hex(PRIVATE_INVALID_N), publicCompressed);
        assertInvalidPrivateKey(platform, hex(PRIVATE_INVALID_N_PLUS_ONE), publicCompressed);

        assertInvalidPublicKey(platform, privateKey, new byte[] { 0 });
        assertInvalidPublicKey(platform, privateKey, new byte[0]);
        assertInvalidPublicKey(platform, privateKey, malformedPublicKey(32, 0x02));
        assertInvalidPublicKey(platform, privateKey, malformedPublicKey(64, 0x04));
        assertInvalidPublicKey(platform, privateKey, malformedPublicKey(65, 0x04));

        assertArrayEquals(publicUncompressed, platform.secp256k1SharedSecret(privateKey, publicCompressed));
        assertArrayEquals(publicUncompressed, platform.secp256k1SharedSecret(privateKey, publicUncompressed));
    }

    private static void assertInvalidPrivateKey(NGEPlatform platform, byte[] privateKey, byte[] publicKey) {
        try {
            platform.secp256k1SharedSecret(privateKey, publicKey);
            throw new AssertionError("Expected invalid private key rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid secp256k1 private key", expected.getMessage());
        }
    }

    private static void assertInvalidPublicKey(NGEPlatform platform, byte[] privateKey, byte[] publicKey) {
        try {
            platform.secp256k1SharedSecret(privateKey, publicKey);
            throw new AssertionError("Expected invalid public key rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid secp256k1 public key", expected.getMessage());
        }
    }

    private static byte[] malformedPublicKey(int length, int prefix) {
        byte[] key = new byte[length];
        key[0] = (byte) prefix;
        return key;
    }

    private static byte[] hex(String value) {
        int len = value.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(value.charAt(i), 16) << 4) + Character.digit(value.charAt(i + 1), 16));
        }
        return out;
    }
}
