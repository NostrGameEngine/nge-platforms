package org.ngengine.platform.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

public class NativeImageEntropyConfigurationTest {

    private static final String CONFIG_RESOURCE =
        "/META-INF/native-image/org.ngengine/nge-platform-android/native-image.properties";

    @Test
    public void securityAndRuntimeStateSensitiveClassesAreRuntimeInitialized() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = NativeImageEntropyConfigurationTest.class.getResourceAsStream(CONFIG_RESOURCE)) {
            assertNotNull("Missing Native Image entropy configuration", input);
            properties.load(input);
        }

        String args = properties.getProperty("Args", "");
        assertTrue(args.contains("--initialize-at-run-time="));
        Set<String> runtimeTypes = runtimeInitializedTypes(args);
        assertTrue(runtimeTypes.contains("org.ngengine.platform.android.AndroidThreadedPlatform"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.android.AndroidThreadedPlatform$SecureRandomHolder"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.android.AndroidNGEAllocator"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.android.AndroidNGEAllocatorGuard"));
        assertTrue(runtimeTypes.contains("org.bouncycastle.crypto.CryptoServicesRegistrar"));
        assertTrue(runtimeTypes.contains("org.bouncycastle.jcajce.provider.drbg.DRBG"));
        assertTrue(runtimeTypes.contains("org.bouncycastle.jcajce.provider.drbg.DRBG$Default"));
        assertTrue(runtimeTypes.contains("org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV"));
        assertFalse(args.contains("java.security.SecureRandom"));
        assertTrue(args.contains("-H:+UnlockExperimentalVMOptions"));
        assertTrue(args.contains("-H:AdditionalSecurityProviders=org.bouncycastle.jce.provider.BouncyCastleProvider"));
        assertTrue(args.contains("-H:-UnlockExperimentalVMOptions"));
    }

    private static Set<String> runtimeInitializedTypes(String args) {
        String prefix = "--initialize-at-run-time=";
        int start = args.indexOf(prefix);
        int end = args.indexOf(' ', start);
        String value = args.substring(start + prefix.length(), end < 0 ? args.length() : end);
        return new HashSet<>(Arrays.asList(value.split(",")));
    }
}
