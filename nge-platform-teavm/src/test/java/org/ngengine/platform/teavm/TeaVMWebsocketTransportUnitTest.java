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
package org.ngengine.platform.teavm;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class TeaVMWebsocketTransportUnitTest {

    private static final int STRESS_MESSAGES = 256;

    @Test
    public void sendPreservesOrderUnderStressInBothDirections() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        TeaVMWebsocketTransport transportA = new TeaVMWebsocketTransport(platform);
        TeaVMWebsocketTransport transportB = new TeaVMWebsocketTransport(platform);
        List<String> sentA = new ArrayList<>();
        List<String> sentB = new ArrayList<>();
        setField(transportA, "ws", newBrowserSocketProxy(sentA));
        setField(transportB, "ws", newBrowserSocketProxy(sentB));

        for (int i = 0; i < STRESS_MESSAGES; i++) {
            transportA.send("a2b:" + i).await();
            transportB.send("b2a:" + i).await();
        }

        assertEquals(STRESS_MESSAGES, sentA.size());
        assertEquals(STRESS_MESSAGES, sentB.size());
        for (int i = 0; i < STRESS_MESSAGES; i++) {
            assertEquals("a2b:" + i, sentA.get(i));
            assertEquals("b2a:" + i, sentB.get(i));
        }
    }

    private static Object newBrowserSocketProxy(List<String> sentTexts) throws Exception {
        Class<?> iface = Class.forName("org.ngengine.platform.teavm.TeaVMWebsocketTransport$BrowserWebSocket");
        return Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[] { iface },
            (proxy, method, args) -> {
                String name = method.getName();
                if ("send".equals(name) && args != null && args.length == 1 && args[0] instanceof String) {
                    sentTexts.add((String) args[0]);
                    return null;
                }
                if ("getReadyState".equals(name)) {
                    return Integer.valueOf(1);
                }
                return null;
            }
        );
    }

    private static TeaVMPlatform newPlatformOrSkip() {
        try {
            TeaVMPlatform platform = new TeaVMPlatform();
            platform.wrapPromise((resolve, reject) -> resolve.accept("probe")).await();
            return platform;
        } catch (Throwable t) {
            Assume.assumeNoException("TeaVM runtime not available for websocket unit tests", t);
            throw new AssertionError(t);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
