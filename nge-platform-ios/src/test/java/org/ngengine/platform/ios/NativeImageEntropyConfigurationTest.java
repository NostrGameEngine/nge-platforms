package org.ngengine.platform.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.Test;

public class NativeImageEntropyConfigurationTest {

    private static final String CONFIG_RESOURCE =
        "/META-INF/native-image/org.ngengine/nge-platform-ios/native-image.properties";

    @Test
    public void entropySensitiveClassesAreRuntimeInitialized() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = NativeImageEntropyConfigurationTest.class.getResourceAsStream(CONFIG_RESOURCE)) {
            assertNotNull("Missing Native Image entropy configuration", input);
            properties.load(input);
        }

        String args = properties.getProperty("Args", "");
        assertTrue(args.contains("--initialize-at-run-time="));
        assertTrue(args.contains("org.ngengine.platform.ios.IosPlatform$SecureRandomHolder"));
        assertTrue(args.contains("org.bouncycastle.crypto.CryptoServicesRegistrar"));
        assertTrue(args.contains("org.bouncycastle.jcajce.provider.drbg.DRBG"));
        assertTrue(args.contains("org.bouncycastle.jcajce.provider.drbg.DRBG$Default"));
        assertTrue(args.contains("org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV"));
        assertFalse(args.contains("java.security.SecureRandom"));
        assertTrue(args.contains("-H:+UnlockExperimentalVMOptions"));
        assertTrue(args.contains("-H:AdditionalSecurityProviders=org.bouncycastle.jce.provider.BouncyCastleProvider"));
        assertTrue(args.contains("-H:-UnlockExperimentalVMOptions"));
    }
}
