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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.SafeFlag;
import org.teavm.classlib.PlatformDetector;
import org.teavm.jso.JSBody;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

/**
 * The TeaVM runner executes this class once as JavaScript and once as Wasm GC.
 * Both compiled backends must cross the same Java/JavaScript binary boundary and
 * produce the same authoritative vectors.
 */
@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMBackendParityTest {

    @Test
    public void safeFlagRoundTripsAcrossCompiledBackends() {
        SafeFlag flag = new SafeFlag(false);
        assertFalse(flag.get());
        flag.set(true);
        assertTrue(flag.get());
        flag.set(false);
        assertFalse(flag.get());
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void binaryAndCryptoVectorsMatchAcrossCompiledBackends() {
        TeaVMPlatform platform = new TeaVMPlatform();
        byte[] message = utf8("TeaVM/Wasm parity");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(platform.sha256(utf8("abc"))));
        assertEquals("VGVhVk0vV2FzbSBwYXJpdHk=", platform.base64encode(message));
        assertArrayEquals(message, platform.base64decode("VGVhVk0vV2FzbSBwYXJpdHk="));

        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            hex(platform.hmac(utf8("key"), utf8("The quick brown fox jumps over the lazy dog"), new byte[0]))
        );

        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = ascending(0x00, 13);
        byte[] info = ascending(0xf0, 10);
        byte[] prk = platform.hkdf_extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk));
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(platform.hkdf_expand(prk, info, 42))
        );

        byte[] key = new byte[32];
        byte[] nonce12 = new byte[12];
        byte[] chachaCiphertext = platform.chacha20(key, nonce12, new byte[64], true);
        assertEquals(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
            "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
            hex(chachaCiphertext)
        );
        assertArrayEquals(new byte[64], platform.chacha20(key, nonce12, chachaCiphertext, false));

        byte[] aesCiphertext = platform.aes256cbc(key, new byte[16], message, true);
        assertEquals("c6d3aae19876bb6f80a9e891fea652b17af3f5e303da58823bb18cfdb8b1c41b", hex(aesCiphertext));
        assertArrayEquals(message, platform.aes256cbc(key, new byte[16], aesCiphertext, false));

        byte[] associatedData = utf8("nge");
        byte[] xchachaCiphertext = platform.xchacha20poly1305(key, new byte[24], message, associatedData, true);
        assertEquals("2cfbf7dfa80fda1eaa8cd3b5d446763c961f65ed03545f00be7c118308500b4a02", hex(xchachaCiphertext));
        assertArrayEquals(message, platform.xchacha20poly1305(key, new byte[24], xchachaCiphertext, associatedData, false));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void directBuffersUseTheSameCryptoVectors() {
        TeaVMPlatform platform = new TeaVMPlatform();
        ByteBuffer message = direct(platform, utf8("TeaVM/Wasm parity"));
        ByteBuffer key = direct(platform, new byte[32]);
        ByteBuffer nonce12 = direct(platform, new byte[12]);
        ByteBuffer nonce24 = direct(platform, new byte[24]);
        ByteBuffer associatedData = direct(platform, utf8("nge"));

        assertTrue(message.isDirect());
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(platform.sha256(direct(platform, utf8("abc"))))
        );
        assertEquals("VGVhVk0vV2FzbSBwYXJpdHk=", platform.base64encode(message));
        assertEquals(
            "TeaVM/Wasm parity",
            new String(bytes(platform.base64decodeBuffer("VGVhVk0vV2FzbSBwYXJpdHk=")), StandardCharsets.UTF_8)
        );

        ByteBuffer chachaCiphertext = platform.chacha20(key, nonce12, direct(platform, new byte[64]), true);
        assertTrue(chachaCiphertext.isDirect());
        assertEquals(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
            "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
            hex(chachaCiphertext)
        );
        assertArrayEquals(new byte[64], bytes(platform.chacha20(key, nonce12, chachaCiphertext, false)));

        ByteBuffer aesCiphertext = platform.aes256cbc(key, direct(platform, new byte[16]), message, true);
        assertEquals("c6d3aae19876bb6f80a9e891fea652b17af3f5e303da58823bb18cfdb8b1c41b", hex(aesCiphertext));
        assertArrayEquals(bytes(message), bytes(platform.aes256cbc(key, direct(platform, new byte[16]), aesCiphertext, false)));

        ByteBuffer xchachaCiphertext = platform.xchacha20poly1305(key, nonce24, message, associatedData, true);
        assertEquals("2cfbf7dfa80fda1eaa8cd3b5d446763c961f65ed03545f00be7c118308500b4a02", hex(xchachaCiphertext));
        assertArrayEquals(
            bytes(message),
            bytes(platform.xchacha20poly1305(key, nonce24, xchachaCiphertext, associatedData, false))
        );

        assertEquals(0, message.position());
        assertEquals(0, key.position());
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void platformNameIdentifiesTheCompiledBackend() {
        String expectedBackend = PlatformDetector.isWebAssemblyGC() ? "TeaVM Wasm GC" : "TeaVM JavaScript";
        String platformName = new TeaVMPlatform().getPlatformName();
        assertTrue(platformName, platformName.startsWith(expectedBackend + " ("));
        assertTrue(platformName, platformName.contains("browser"));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void httpResponseBodyCrossesTheCompiledBoundaryWithoutBase64() {
        TeaVMPlatform platform = new TeaVMPlatform();
        assertArrayEquals(
            new byte[] { 0, 1, 2, 3, 127, (byte) 128, (byte) 254, (byte) 255, 0, 78, 71, 69 },
            TeaVMPlatform.readHttpResponseBody(binaryHttpResponse(), platform)
        );
        assertArrayEquals(new byte[0], TeaVMPlatform.readHttpResponseBody(emptyHttpResponse(), platform));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void cleanerRunsExplicitCleanupExactlyOnce() throws Exception {
        TeaVMPlatform platform = new TeaVMPlatform();
        int[] cleanupCount = { 0 };
        Runnable cleanup = platform.registerFinalizer(new Object(), () -> cleanupCount[0]++);

        cleanup.run();
        cleanup.run();

        assertEquals(1, cleanupCount[0]);
    }

    private static byte[] ascending(int start, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (start + i);
        }
        return out;
    }

    @JSBody(script = "return { body: new Uint8Array([0, 1, 2, 3, 127, 128, 254, 255, 0, 78, 71, 69]) };")
    private static native TeaVMHttpResponse binaryHttpResponse();

    @JSBody(script = "return { body: new Uint8Array(0) };")
    private static native TeaVMHttpResponse emptyHttpResponse();

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(unsigned));
        }
        return out.toString();
    }

    private static String hex(ByteBuffer buffer) {
        return hex(bytes(buffer));
    }

    private static ByteBuffer direct(TeaVMPlatform platform, byte[] bytes) {
        ByteBuffer buffer = platform.getNativeAllocator().malloc(Math.max(1, bytes.length));
        buffer.limit(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer.slice();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }
}
