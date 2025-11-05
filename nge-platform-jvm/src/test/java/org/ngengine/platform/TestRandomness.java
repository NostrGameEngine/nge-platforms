package org.ngengine.platform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;

import org.junit.Test;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class TestRandomness {
    @Test
    public void testNewSecureRandomProducesDifferentSequences() {
        SecureRandom rng1 = JVMAsyncPlatform.newSecureRandom();
        SecureRandom rng2 = JVMAsyncPlatform.newSecureRandom();

        byte[] bytes1 = new byte[32];
        byte[] bytes2 = new byte[32];

        rng1.nextBytes(bytes1);
        rng2.nextBytes(bytes2);

        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
        for (int i = 0; i < bytes1.length; i++) {
            System.out.printf("%02x ", bytes1[i]);
        }
    }

}
