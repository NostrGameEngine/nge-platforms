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

import java.net.InetAddress;
import java.security.SecureRandom;
import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.ngengine.platform.NGEPlatform;

public final class NativeImageRuntimeInitializationSmoke {

    private NativeImageRuntimeInitializationSmoke() {}

    public static void main(String[] args) throws Exception {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        NGEPlatform.set(platform);

        byte[] platformRandom = platform.randomBytes(32);
        byte[] registrarRandom = new byte[32];
        CryptoServicesRegistrar.getSecureRandom().nextBytes(registrarRandom);

        byte[] defaultDrbgRandom = new byte[32];
        SecureRandom.getInstance("DEFAULT", "BC").nextBytes(defaultDrbgRandom);
        byte[] nonceAndIvRandom = new byte[32];
        SecureRandom.getInstance("NONCEANDIV", "BC").nextBytes(nonceAndIvRandom);

        JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 }));
        Class.forName(JVMNGEAllocatorGuard.class.getName(), true, JVMNGEAllocatorGuard.class.getClassLoader());

        // Keep the native RTC initializer reachable without loading libdatachannel during this smoke run.
        if (Boolean.getBoolean("nge.nativeImageSmoke.initializeRtc")) {
            new JVMRTCTransport();
        }

        int generatedBytes =
            platformRandom.length + registrarRandom.length + defaultDrbgRandom.length + nonceAndIvRandom.length;
        if (generatedBytes != 128) {
            throw new IllegalStateException("Unexpected random output length: " + generatedBytes);
        }
        System.out.println("Native Image runtime-initialization smoke passed");
    }
}
