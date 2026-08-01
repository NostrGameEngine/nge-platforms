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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.security.Provider;
import java.security.Security;
import java.util.HashSet;
import java.util.Set;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

public class PrivateKeyEntropyTest {

    private static final int SAMPLE_COUNT = 4096;

    @Test
    public void bytesFromBigIntegerIsStrictUnsignedFixedWidthEncoding() {
        assertRoundTrip(BigInteger.ZERO);
        assertRoundTrip(BigInteger.ONE);
        assertRoundTrip(BigInteger.ONE.shiftLeft(255));
        assertRoundTrip(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));

        assertThrows(NullPointerException.class, () -> Util.bytesFromBigInteger(null));
        assertThrows(IllegalArgumentException.class, () -> Util.bytesFromBigInteger(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> Util.bytesFromBigInteger(BigInteger.ONE.shiftLeft(256)));
    }

    @Test
    public void privateKeyGenerationUsesBundledBouncyCastleProvider() {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        assertEquals(BouncyCastleProvider.class, provider.getClass());

        byte[] key = platform.generatePrivateKey();
        assertEquals(32, key.length);
        assertTrue(platform.secp256k1PrivateKeyVerify(key));

        BigInteger domainSize = Point.getn().subtract(BigInteger.ONE);
        assertEquals(256, domainSize.bitLength());
    }

    @Test
    public void generatedPrivateKeysPassDistributionSanityChecks() {
        assertHealthyDistribution(new JVMAsyncPlatform());
    }

    private static void assertRoundTrip(BigInteger value) {
        byte[] encoded = Util.bytesFromBigInteger(value);
        assertEquals(32, encoded.length);
        assertEquals(value, new BigInteger(1, encoded));
    }

    private static void assertHealthyDistribution(JVMAsyncPlatform platform) {
        int[] byteCounts = new int[256];
        int[] bitCounts = new int[256];
        Set<BigInteger> keys = new HashSet<>();

        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            byte[] key = platform.generatePrivateKey();
            assertEquals(32, key.length);
            assertTrue(platform.secp256k1PrivateKeyVerify(key));
            assertTrue("private-key collision", keys.add(new BigInteger(1, key)));

            for (int byteIndex = 0; byteIndex < key.length; byteIndex++) {
                int value = key[byteIndex] & 0xff;
                byteCounts[value]++;
                for (int bit = 0; bit < 8; bit++) {
                    bitCounts[byteIndex * 8 + bit] += (value >>> bit) & 1;
                }
            }
        }

        double expectedByteCount = SAMPLE_COUNT * 32.0 / 256.0;
        double chiSquare = 0.0;
        for (int count : byteCounts) {
            double delta = count - expectedByteCount;
            chiSquare += delta * delta / expectedByteCount;
        }
        assertTrue("byte-frequency chi-square too high: " + chiSquare, chiSquare < 400.0);

        double expectedBitCount = SAMPLE_COUNT / 2.0;
        double maxBitDeviation = 7.0 * Math.sqrt(SAMPLE_COUNT * 0.25);
        for (int bit = 0; bit < bitCounts.length; bit++) {
            assertTrue(
                "bit " + bit + " is biased: " + bitCounts[bit],
                Math.abs(bitCounts[bit] - expectedBitCount) <= maxBitDeviation
            );
        }
    }
}
