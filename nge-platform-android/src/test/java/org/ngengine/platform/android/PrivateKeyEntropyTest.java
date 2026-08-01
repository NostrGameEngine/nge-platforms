package org.ngengine.platform.android;

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
    public void privateKeyGenerationReplacesAndroidBcWithBundledProvider() {
        AndroidThreadedPlatform platform = new AndroidThreadedPlatform(null);
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
        assertHealthyDistribution(new AndroidThreadedPlatform(null));
    }

    private static void assertRoundTrip(BigInteger value) {
        byte[] encoded = Util.bytesFromBigInteger(value);
        assertEquals(32, encoded.length);
        assertEquals(value, new BigInteger(1, encoded));
    }

    private static void assertHealthyDistribution(AndroidThreadedPlatform platform) {
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
