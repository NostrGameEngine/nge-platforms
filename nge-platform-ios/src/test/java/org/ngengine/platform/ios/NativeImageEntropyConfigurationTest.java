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
        "/META-INF/native-image/org.ngengine/nge-platform-ios/native-image.properties";

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
        assertTrue(runtimeTypes.contains("org.ngengine.platform.ios.IosPlatform"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.ios.IosPlatform$SecureRandomHolder"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.ios.IosNetworkSecurity"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.ios.IosNGEAllocator"));
        assertTrue(runtimeTypes.contains("org.ngengine.platform.ios.IosNGEAllocatorGuard"));
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
