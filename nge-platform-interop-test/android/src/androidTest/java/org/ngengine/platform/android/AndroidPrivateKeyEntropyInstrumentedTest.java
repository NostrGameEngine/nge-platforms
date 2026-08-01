package org.ngengine.platform.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.math.BigInteger;
import java.security.Provider;
import java.security.Security;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AndroidPrivateKeyEntropyInstrumentedTest {

    private static final int SAMPLE_COUNT = 4096;

    @Test
    public void deviceSecureRandomProducesHealthyPrivateKeys() {
        Context context = ApplicationProvider.getApplicationContext();
        AndroidThreadedPlatform platform = new AndroidThreadedPlatform(context);
        Provider provider = Security.getProvider("BC");
        assertEquals("org.bouncycastle.jce.provider.BouncyCastleProvider", provider.getClass().getName());
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
