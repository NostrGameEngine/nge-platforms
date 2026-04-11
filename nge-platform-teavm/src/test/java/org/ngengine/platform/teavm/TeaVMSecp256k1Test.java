package org.ngengine.platform.teavm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.secp256k1.Secp256k1RecoverableSignature;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMSecp256k1Test {

    private static final String PRIVATE_VALID = "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String PRIVATE_INVALID_ZERO = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String PRIVATE_INVALID_N = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141";
    private static final String PUBLIC_COMPRESSED = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String PUBLIC_UNCOMPRESSED = "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8";
    private static final String PUBLIC_INVALID_33 = "050000000000000000000000000000000000000000000000000000000000000000";
    private static final String HASH32 = "4f3c2a1b0e9d8c7b6a594837261504f3c2a1b0e9d8c7b6a594837261504f3c2a";
    private static final String SIGNATURE64 = "8195d0641be8128e0e6ca5dd4ee589a15418a37c0977a07fffdeeb6c88bcca105e827dd8c591082e86d1ae7306c1ed0fac7760151f07119eba76edf6f081da72";
    private static final int RECOVERY_ID = 1;

    @Test
    @ServeJS(
        from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js",
        as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js"
    )
    public void validatesSecp256k1PublicVectors() {
        TeaVMPlatform platform = new TeaVMTestPlatform();

        byte[] privateKey = hex(PRIVATE_VALID);
        byte[] invalidPrivateZero = hex(PRIVATE_INVALID_ZERO);
        byte[] invalidPrivateN = hex(PRIVATE_INVALID_N);
        byte[] publicCompressed = hex(PUBLIC_COMPRESSED);
        byte[] publicUncompressed = hex(PUBLIC_UNCOMPRESSED);
        byte[] invalidPublic = hex(PUBLIC_INVALID_33);
        byte[] hash32 = hex(HASH32);
        byte[] signature64 = hex(SIGNATURE64);

        assertTrue(platform.secp256k1PrivateKeyVerify(privateKey));
        assertFalse(platform.secp256k1PrivateKeyVerify(invalidPrivateZero));
        assertFalse(platform.secp256k1PrivateKeyVerify(invalidPrivateN));

        assertTrue(platform.secp256k1PublicKeyVerify(publicCompressed));
        assertTrue(platform.secp256k1PublicKeyVerify(publicUncompressed));
        assertFalse(platform.secp256k1PublicKeyVerify(invalidPublic));

        assertArrayEquals(publicCompressed, platform.secp256k1PublicKeyCreate(privateKey, true));
        assertArrayEquals(publicUncompressed, platform.secp256k1PublicKeyCreate(privateKey, false));

        Secp256k1RecoverableSignature signed = platform.secp256k1SignRecoverable(hash32, privateKey);
        assertArrayEquals(signature64, signed.getSignature64());
        assertEquals(RECOVERY_ID, signed.getRecoveryId());

        assertArrayEquals(publicCompressed, platform.secp256k1RecoverPublicKey(hash32, signature64, RECOVERY_ID, true));
        assertArrayEquals(publicUncompressed, platform.secp256k1RecoverPublicKey(hash32, signature64, RECOVERY_ID, false));
    }

    private static byte[] hex(String value) {
        int len = value.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(value.charAt(i), 16) << 4) + Character.digit(value.charAt(i + 1), 16));
        }
        return out;
    }

    private static final class TeaVMTestPlatform extends TeaVMPlatform {

        @Override
        public Runnable registerFinalizer(Object obj, Runnable finalizer) {
            return () -> {
                // TeaVM JUnit runtime does not provide the full finalizer bridge used in app runtime.
            };
        }
    }
}
