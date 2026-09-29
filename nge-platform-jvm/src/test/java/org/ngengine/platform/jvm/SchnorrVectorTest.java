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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.Test;

public class SchnorrVectorTest {

    @Test
    public void verifiesOfficialBip340VectorsAndDeterministicSignatures() throws Exception {
        int checked = 0;
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(getClass().getResourceAsStream("/bip340-test-vectors.csv"), StandardCharsets.UTF_8)
            )
        ) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] vector = line.split(",", 9);
                // The platform's contract is a 32-byte event hash; BIP-340's
                // additional variable-message-length vectors do not apply here.
                if (vector[4].length() != 64) continue;
                byte[] message = hex(vector[4]);
                byte[] publicKey = hex(vector[2]);
                byte[] signature = hex(vector[5]);
                assertEquals(
                    "BIP-340 vector " + vector[0],
                    Boolean.parseBoolean(vector[6]),
                    Schnorr.verify(message, publicKey, signature)
                );
                if (!vector[1].isEmpty()) {
                    assertArrayEquals(
                        "BIP-340 signing vector " + vector[0],
                        signature,
                        Schnorr.sign(message, hex(vector[1]), hex(vector[3]))
                    );
                }
                checked++;
            }
        }
        assertEquals(15, checked);
    }

    @Test
    public void publicDoubleMultiplicationMatchesIndependentScalarOperations() {
        Random random = new Random(340);
        for (int i = 0; i < 48; i++) {
            Point publicPoint = Point.mul(Point.getG(), new BigInteger(256, random).mod(Point.getn()));
            BigInteger s = new BigInteger(256, random).mod(Point.getn());
            BigInteger e = new BigInteger(256, random).mod(Point.getn());
            Point expected = Point.add(
                Point.mul(Point.getG(), s),
                Point.mul(publicPoint, Point.getn().subtract(e).mod(Point.getn()))
            );
            assertEquals(expected, Point.schnorrVerify(s, publicPoint, e));
        }
        assertTrue(Point.schnorrVerify(BigInteger.ZERO, Point.getG(), BigInteger.ZERO).isInfinite());
        assertTrue(Point.schnorrVerify(BigInteger.ONE, Point.getG(), BigInteger.ONE).isInfinite());
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
