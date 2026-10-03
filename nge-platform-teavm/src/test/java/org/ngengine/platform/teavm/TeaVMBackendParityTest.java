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
import java.util.LinkedHashMap;
import java.util.Map;
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

    private static TeaVMPlatform installedPlatform;
    private static org.ngengine.platform.MemoryLimits testLimits;

    private static TeaVMPlatform installedPlatform() {
        if (installedPlatform == null) {
            installedPlatform =
                new TeaVMPlatform() {
                    @Override
                    public org.ngengine.platform.MemoryLimits getMemoryLimits() {
                        return testLimits != null ? testLimits : super.getMemoryLimits();
                    }
                };
            org.ngengine.platform.NGEPlatform.set(installedPlatform);
        }
        return installedPlatform;
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void typedStringGetterPreservesFallbackValuesAndCustomLimits() {
        TeaVMPlatform platform = installedPlatform();
        org.ngengine.platform.JsonObject object = platform.parseJsonObject(
            "{\"text\":\"a\\u0000\\ud800\\udfff🦊\",\"empty\":\"\",\"null\":null,\"number\":42,\"flag\":true}"
        );
        assertEquals("a\u0000\ud800\udfff🦊", object.getString("text"));
        assertEquals("", object.getString("empty"));
        assertEquals("", object.getString("null"));
        assertEquals("", object.getString("missing"));
        assertEquals("42", object.getString("number"));
        assertEquals("true", object.getString("flag"));
        int[] checks = { 0 };
        testLimits =
            new org.ngengine.platform.MemoryLimits() {
                @Override
                protected boolean checkLimit(long size, long limit) {
                    checks[0]++;
                    return size != 14 && super.checkLimit(size, limit);
                }
            };
        try {
            assertEquals("", object.getString("empty"));
            assertEquals("42", object.getString("number"));
            assertEquals(2, checks[0]);
            platform.parseJsonObject("{\"text\":\"allowed\"}").getString("text");
            throw new AssertionError("Custom string policy was bypassed");
        } catch (IllegalArgumentException expected) {
            assertEquals(3, checks[0]);
        } finally {
            testLimits = null;
        }
        try {
            platform.parseJsonObject("{\"text\":\"" + "x".repeat(1024 * 1024 + 1) + "\"}").getString("text");
            throw new AssertionError("String size limit was bypassed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("string"));
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void stringHashAndJsonPreserveBinaryAndConverterSemantics() {
        TeaVMPlatform platform = installedPlatform();
        assertTrue(platform.supportsMinimalJSONEscaping());
        assertEquals(
            "[\"<>&" + (char) 0x2028 + (char) 0x2029 + "\\ud800\\u0001\"]",
            platform.toJSON(Arrays.asList("<>&" + (char) 0x2028 + (char) 0x2029 + (char) 0xD800 + (char) 1))
        );
        String[] inputs = { "", "abc", "Unicode 🦊 café 漢字" + (char) 0x2028 + (char) 0x2029, "large".repeat(14000) };
        for (String input : inputs) {
            assertEquals(hex(platform.sha256(utf8(input))), platform.sha256(input));
        }
        // JVM UTF-8 uses '?' for each unpaired surrogate. TeaVM's prior
        // charset path threw at a terminal high surrogate instead of hashing.
        assertEquals(
            "c5f52145eb20c4459c27628bca8310c10b5e266c54257572770742a2e4693d6f",
            platform.sha256("unpaired" + (char) 0xD800)
        );
        assertEquals(
            "c5f52145eb20c4459c27628bca8310c10b5e266c54257572770742a2e4693d6f",
            platform.sha256("unpaired" + (char) 0xDC00)
        );
        assertEquals(
            "cdb51ffa914a7391d6a327c86f01e26981fcc205b4b78c308cbd68fe3d315f78",
            platform.sha256("unpaired" + (char) 0xD800 + (char) 0xD800)
        );
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("null", null);
        tree.put("text", inputs[2]);
        tree.put("nested", Arrays.asList(Arrays.asList("quoted\"", "slash\\", "\n\r\t\b\f", null), true, 1.25));
        assertEquals(TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(tree)), platform.toJSON(tree));
        assertEquals(
            TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(Arrays.asList(tree, null))),
            platform.toJSON(Arrays.asList(tree, null))
        );
        for (String input : new String[] { inputs[2], inputs[3], "unpaired" + (char) 0xD800 }) {
            java.util.List<Object> payload = Arrays.asList(0, 1700000000L, input, tree);
            assertEquals(hex(platform.sha256(utf8(platform.toJSON(payload)))), platform.sha256JSON(payload));
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void jsonTreeConversionPreservesTypesAndIndependentCollections() {
        TeaVMPlatform platform = new TeaVMPlatform();
        String json =
            "{\"text\":\"Unicode 🦊 café 漢字\",\"nested\":[null,true,false,1,-2147483648,2147483648,1.25,{\"x\":[]}]}";
        Map<String, Object> tree = platform.fromJSON(json, Map.class);
        assertEquals("Unicode 🦊 café 漢字", tree.get("text"));
        java.util.List<Object> nested = (java.util.List<Object>) tree.get("nested");
        assertEquals(null, nested.get(0));
        assertEquals(Boolean.TRUE, nested.get(1));
        assertEquals(Boolean.FALSE, nested.get(2));
        assertTrue(nested.get(3) instanceof Integer);
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), nested.get(4));
        assertTrue(nested.get(5) instanceof Long);
        assertEquals(Long.valueOf(2147483648L), nested.get(5));
        assertEquals(Double.valueOf(1.25), nested.get(6));
        assertTrue(((java.util.List<?>) ((Map<?, ?>) nested.get(7)).get("x")).isEmpty());
        nested.set(1, "changed");
        org.teavm.jso.JSObject original = (org.teavm.jso.JSObject) TeaVMBinds.fromJSON(json);
        Map<String, Object> publicCopy = TeaVMJsConverter.toJavaMap(original);
        ((java.util.List<Object>) publicCopy.get("nested")).set(1, "changed");
        assertEquals(json, TeaVMBinds.toJSON(original));
        Map<String, Object> fresh = platform.fromJSON(json, Map.class);
        assertEquals(Boolean.TRUE, ((java.util.List<?>) fresh.get("nested")).get(1));
        assertTrue(platform.fromJSON("[]", java.util.List.class).isEmpty());
        assertTrue(platform.fromJSON("{}", Map.class).isEmpty());
        java.util.List<String> strings = platform.fromJSON("[\"alpha\",\"🦊\",\"\\ud800\"]", java.util.List.class);
        assertEquals(Arrays.asList("alpha", "🦊", String.valueOf((char) 0xD800)), strings);
        strings.set(0, "changed");
        assertEquals("alpha", ((java.util.List<?>) platform.fromJSON("[\"alpha\",\"🦊\"]", java.util.List.class)).get(0));
        assertEquals(Arrays.asList("a", null, "b"), platform.fromJSON("[\"a\",null,\"b\"]", java.util.List.class));
        assertEquals("[1,2,3]", TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(new int[] { 1, 2, 3 })));
        assertEquals("[\"a\",true,3]", TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(new Object[] { "a", true, 3 })));
        String[] source = { "a", null, "🦊", String.valueOf((char) 0xD800) };
        org.teavm.jso.JSObject copied = TeaVMJsConverter.toJSObject(source);
        source[0] = "changed";
        assertEquals("[\"a\",null,\"🦊\",\"\\ud800\"]", TeaVMBinds.toJSON(copied));
        assertEquals(
            "[[\"a\",null,\"🦊\",\"\\ud800\"],[]]",
            platform.toJSON(Arrays.asList(Arrays.asList("a", null, "🦊", String.valueOf((char) 0xD800)), Arrays.asList()))
        );
        org.ngengine.platform.MemoryLimits limits = platform.getMemoryLimits();
        assertTrue(limits.checkForString(1024 * 1024));
        assertFalse(limits.checkForString(1024 * 1024 + 1));
        assertFalse(limits.checkForString(-1));
        assertFalse(limits.checkForString(Integer.MAX_VALUE));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void stringRowsRemainOwnedAndValidatedAfterBulkConversion() {
        TeaVMPlatform platform = installedPlatform();
        java.util.List<java.util.List<String>> rows = platform.fromJSON(
            "[[\"t\",\"first\"],[\"p\",\"🦊\",\"\\ud800\"]]",
            java.util.List.class
        );
        String[] copy = org.ngengine.platform.NGEUtils.safeStringArray(rows.get(0));
        rows.get(0).set(1, "changed");
        assertArrayEquals(new String[] { "t", "first" }, copy);
        copy[0] = "changed independently";
        assertEquals("t", rows.get(0).get(0));
        assertArrayEquals(
            new String[] { "a", "", "42" },
            org.ngengine.platform.NGEUtils.safeStringArray(new java.util.ArrayList<Object>(Arrays.asList("a", null, 42)))
        );
        assertArrayEquals(
            new String[] { "a", "", "42" },
            org.ngengine.platform.NGEUtils.safeStringArray(new java.util.LinkedList<Object>(Arrays.asList("a", null, 42)))
        );
        try {
            org.ngengine.platform.NGEUtils.safeStringArray(Arrays.asList("x".repeat(1024 * 1024 + 1)));
            throw new AssertionError("String limit was bypassed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("string"));
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void typedJsonRowsPreserveLimitsOwnershipAndFallbackValues() {
        TeaVMPlatform platform = installedPlatform();
        org.ngengine.platform.JsonObject object = platform.parseJsonObject(
            "{\"text\":\"🦊\",\"kind\":1,\"created_at\":1700000000,\"tags\":[[],[\"t\",null,\"\\ud800\"]]}"
        );
        assertEquals("🦊", object.getString("text"));
        assertEquals(1, object.getInt("kind"));
        assertEquals(java.time.Instant.ofEpochSecond(1700000000L), object.getSecondsInstant("created_at"));
        java.util.List<java.util.List<String>> rows = object.getStringRows("tags");
        assertEquals(Arrays.asList(Arrays.asList("t", "", String.valueOf((char) 0xD800))), rows);
        try {
            rows.get(0).set(1, "changed");
            throw new AssertionError("Mutable JSON tag row escaped");
        } catch (UnsupportedOperationException expected) {
            assertEquals("", rows.get(0).get(1));
        }
        try {
            rows.add(Arrays.asList("new"));
            throw new AssertionError("Mutable JSON matrix escaped");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, rows.size());
        }
        assertEquals(
            Arrays.asList(Arrays.asList("t", "", "42", "true")),
            platform.parseJsonObject("{\"tags\":[[\"t\",null,42,true]]}").getStringRows("tags")
        );
        assertTrue(platform.parseJsonObject("{}").getStringRows("tags").isEmpty());
        int[] checks = { 0 };
        testLimits =
            new org.ngengine.platform.MemoryLimits() {
                @Override
                protected boolean checkLimit(long size, long limit) {
                    checks[0]++;
                    return size != 14 && super.checkLimit(size, limit);
                }
            };
        try {
            platform.parseJsonObject("{\"tags\":[[\"t\",\"allowed\"]]}").getStringRows("tags");
            throw new AssertionError("Custom string policy was bypassed");
        } catch (IllegalArgumentException expected) {
            assertEquals(2, checks[0]);
        } finally {
            testLimits = null;
        }

        try {
            platform.parseJsonObject("{\"tags\":[[\"" + "x".repeat(1024 * 1024 + 1) + "\"]]}").getStringRows("tags");
            throw new AssertionError("Native JSON string limit was bypassed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("string"));
        }
    }

    @JSBody(
        params = "enabled",
        script = "if (enabled) { Object.prototype.kind = 42; Object.prototype.content = 'inherited'; " +
        "Object.prototype.tags = [['t', 'inherited']]; } else { " +
        "delete Object.prototype.kind; delete Object.prototype.content; delete Object.prototype.tags; }"
    )
    private static native void inheritedFields(boolean enabled);

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void typedJsonReadsOnlyOwnedObjectProperties() {
        TeaVMPlatform platform = installedPlatform();
        inheritedFields(true);
        try {
            org.ngengine.platform.JsonObject object = platform.parseJsonObject("{}");
            assertEquals(0, object.getInt("kind"));
            assertEquals("", object.getString("content"));
            assertTrue(object.getStringRows("tags").isEmpty());
        } finally {
            inheritedFields(false);
        }
        for (String json : new String[] { "null", "[]", "[[\"x\",\"y\"]]", "42", "\"text\"", "true", "false", " \n [] \t" }) {
            boolean rejected = false;
            try {
                platform.parseJsonObject(json);
            } catch (IllegalArgumentException expected) {
                assertEquals("JSON root must be an object", expected.getMessage());
                rejected = true;
            }
            assertTrue("Non-object JSON root accepted: " + json, rejected);
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void nativeSchnorrHexPreservesVerificationAndKeyViews() {
        TeaVMPlatform platform = installedPlatform();
        byte[] secret = new byte[32];
        secret[31] = 3;
        ByteBuffer storage = direct(platform, new byte[36]);
        storage.position(2);
        storage.put(secret);
        storage.position(2);
        storage.limit(34);
        ByteBuffer key = storage.asReadOnlyBuffer();
        String digest = "01ab".repeat(16);
        String signature = platform.schnorrSign(digest, key);
        assertEquals(128, signature.length());
        ByteBuffer pub = platform.genPubKey(key);
        assertTrue(platform.schnorrVerify(digest, signature, pub));
        assertTrue(
            platform.schnorrVerify(digest.toUpperCase(java.util.Locale.ROOT), signature.toUpperCase(java.util.Locale.ROOT), pub)
        );
        assertTrue(platform.schnorrVerify(digest, signature, platform.genPubKey(secret)));
        assertTrue(platform.schnorrVerify(digest, platform.schnorrSign(digest, secret), pub));
        assertFalse(platform.schnorrVerify("00".repeat(32), signature, pub));
        assertFalse(platform.schnorrVerify(digest, "00".repeat(64), pub));
        assertArrayEquals(secret, bytes(key));
        assertEquals(2, key.position());
        assertEquals(34, key.limit());
        for (String malformed : new String[] { "0", "GF", "FG", "ＦＦ", "١٢" }) {
            try {
                org.ngengine.platform.NGEUtils.hexToByteArray(malformed);
                throw new AssertionError("common decoder accepted malformed hex");
            } catch (IllegalArgumentException expected) {}
            try {
                platform.schnorrSign(malformed, key);
                throw new AssertionError("native signer accepted malformed hex");
            } catch (IllegalArgumentException expected) {}
            try {
                platform.schnorrVerify(digest, malformed, pub);
                throw new AssertionError("native verifier accepted malformed hex");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void asyncSchnorrTasksSupportAwaitingCallbacksAndPropagateFailures() throws Exception {
        TeaVMPlatform platform = installedPlatform();
        byte[] secret = new byte[32];
        secret[31] = 3;
        ByteBuffer key = direct(platform, secret).asReadOnlyBuffer();
        ByteBuffer pub = platform.genPubKey(key);
        byte[] pubBytes = platform.genPubKey(secret);
        String digest = "01ab".repeat(16);
        Thread caller = Thread.currentThread();
        org.ngengine.platform.AsyncTask<String> signing = platform.schnorrSignAsync(digest, key);
        String signature = signing
            .then(value -> {
                assertFalse(caller == Thread.currentThread());
                assertTrue(platform.schnorrVerifyAsync(digest, value, pub).await());
                return value;
            })
            .await();
        assertTrue(signing.isSuccess());
        assertEquals(signature, signing.then(value -> value).await());
        assertTrue(platform.schnorrVerifyAsync(digest, signature, pubBytes).await());
        assertTrue(platform.schnorrVerify(digest, platform.schnorrSignAsync(digest, secret).await(), pub));
        assertFalse(platform.schnorrVerifyAsync("00".repeat(32), signature, pub).await());
        org.ngengine.platform.AsyncTask<String> failed = platform.schnorrSignAsync(digest, new byte[32]);
        try {
            failed.await();
            throw new AssertionError("invalid secret signed");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(failed.isFailed());
            assertTrue(expected.getCause() != null);
        }
    }

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
