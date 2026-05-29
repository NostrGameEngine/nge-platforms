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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import org.junit.Test;

public class JVMNetworkSecurityTest {

    @Test
    public void rejectsLoopbackWebSocketUrls() {
        assertThrows(
            IllegalArgumentException.class,
            () -> JVMNetworkSecurity.safeWebSocketUri("ws://127.0.0.1:19082/internal")
        );
    }

    @Test
    public void rejectsRedirectsToLoopbackUrls() {
        URI publicUri = URI.create("https://example.com/redirect");

        assertThrows(
            IllegalArgumentException.class,
            () -> JVMNetworkSecurity.safeRedirectUri(publicUri, "http://127.0.0.1:19081/secret")
        );
    }

    @Test
    public void detectsPrivateAndLocalAddresses() throws Exception {
        assertTrue(JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByName("172.16.0.1")));
        assertTrue(JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByName("192.168.0.1")));
        assertTrue(JVMNetworkSecurity.isPrivateOrLocalAddress(InetAddress.getByName("fc00::1")));
    }
}
