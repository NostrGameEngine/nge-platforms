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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.SchnorrSigner;

public class PreparedSchnorrSignerTest {

    private final JVMAsyncPlatform platform = new JVMAsyncPlatform();

    @Test
    public void concurrentSignaturesVerifyAndKeepAuxiliaryRandomness() throws Exception {
        for (int secret : new int[] { 3, 6 }) {
            byte[] key = Util.bytesFromBigInteger(BigInteger.valueOf(secret));
            byte[] pub = Schnorr.genPubKey(key);
            ByteBuffer buffer = ByteBuffer.wrap(key).asReadOnlyBuffer();
            SchnorrSigner signer = platform.createSchnorrSigner(() -> buffer);
            List<AsyncTask<String>> pending = new ArrayList<>();
            String message = "00".repeat(32);
            for (int i = 0; i < 32; i++) pending.add(signer.sign(message));
            List<String> signatures = new ArrayList<>();
            for (AsyncTask<String> task : pending) {
                String signature = task.await();
                assertTrue(platform.schnorrVerify(message, signature, pub));
                assertFalse("auxiliary randomness must remain enabled", signatures.contains(signature));
                signatures.add(signature);
            }
            assertEquals(0, buffer.position());
            assertArrayEquals(Util.bytesFromBigInteger(BigInteger.valueOf(secret)), key);
        }
    }

    @Test
    public void changedKeyCannotReleaseSignatureForPreparedIdentity() throws Exception {
        AtomicReference<ByteBuffer> key = new AtomicReference<>(
            ByteBuffer.wrap(Util.bytesFromBigInteger(BigInteger.valueOf(3)))
        );
        SchnorrSigner signer = platform.createSchnorrSigner(key::get);
        key.set(ByteBuffer.wrap(Util.bytesFromBigInteger(BigInteger.valueOf(4))));
        assertThrows(Exception.class, () -> signer.sign("00".repeat(32)).await());
        assertThrows(
            org.ngengine.platform.FailedToSignException.class,
            () ->
                Schnorr.sign(
                    new byte[32],
                    Util.bytesFromBigInteger(BigInteger.valueOf(4)),
                    new byte[32],
                    Schnorr.preparePublicPoint(Util.bytesFromBigInteger(BigInteger.valueOf(3)))
                )
        );
        key.set(ByteBuffer.wrap(new byte[32]));
        assertThrows(Exception.class, () -> signer.sign("00".repeat(32)).await());
        key.set(ByteBuffer.wrap(new byte[] { 3 }));
        assertThrows(Exception.class, () -> signer.sign("00".repeat(32)).await());
    }

    @Test
    public void destroyedKeySupplierIsConsultedOnEverySignature() throws Exception {
        AtomicBoolean destroyed = new AtomicBoolean();
        ByteBuffer key = ByteBuffer.wrap(Util.bytesFromBigInteger(BigInteger.valueOf(3)));
        SchnorrSigner signer = platform.createSchnorrSigner(() -> {
            if (destroyed.get()) throw new IllegalStateException("destroyed");
            return key;
        });
        signer.sign("00".repeat(32)).await();
        destroyed.set(true);
        assertThrows(IllegalStateException.class, () -> signer.sign("00".repeat(32)));
    }

    @Test
    public void rejectsInvalidSetupKeysAndMessages() {
        for (byte[] key : new byte[][] { new byte[32], Util.bytesFromBigInteger(Point.getn()), new byte[31] }) {
            assertThrows(IllegalArgumentException.class, () -> platform.createSchnorrSigner(() -> ByteBuffer.wrap(key)));
        }
        SchnorrSigner signer = platform.createSchnorrSigner(() -> ByteBuffer.wrap(Util.bytesFromBigInteger(BigInteger.ONE)));
        assertThrows(Exception.class, () -> signer.sign("00").await());
        assertThrows(Exception.class, () -> signer.sign("not hex").await());
    }

    @Test
    public void randomPreparedSignaturesMatchStatelessForBothPublicPointParities() throws Exception {
        java.util.Random random = new java.util.Random(344);
        boolean even = false, odd = false;
        for (int i = 0; i < 48; i++) {
            byte[] key = Util.bytesFromBigInteger(new BigInteger(255, random).add(BigInteger.ONE));
            byte[] message = new byte[32], aux = new byte[32];
            random.nextBytes(message);
            random.nextBytes(aux);
            Point point = Schnorr.preparePublicPoint(key);
            even |= point.hasEvenY();
            odd |= !point.hasEvenY();
            byte[] prepared = Schnorr.sign(message, key, aux, point);
            assertArrayEquals(Schnorr.sign(message, key, aux), prepared);
            assertTrue(Schnorr.verify(message, point.toBytes(), prepared));
            prepared[i % 64] ^= 1;
            assertFalse(Schnorr.verify(message, point.toBytes(), prepared));
        }
        assertTrue(even && odd);
    }

    @Test
    public void unsignedIntegerSlicesPreserveBoundaryValues() {
        byte[] bytes = new byte[80];
        Arrays.fill(bytes, (byte) 0xff);
        bytes[16] = 0;
        for (int length : new int[] { 0, 1, 31, 32, 33 }) {
            assertEquals(
                new BigInteger(1, Arrays.copyOfRange(bytes, 16, 16 + length)),
                Util.bigIntFromBytes(bytes, 16, length)
            );
        }
        assertThrows(IndexOutOfBoundsException.class, () -> Util.bigIntFromBytes(bytes, 70, 32));
    }
}
