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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.platform.NGEPlatform;

public class JVMPlatformParityMain {

    private static final Gson GSON = new Gson();

    private static final byte[] PRIV_A = hex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020101");
    private static final byte[] PRIV_B = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
    private static final byte[] HMAC_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] DATA_1 = hex("cafebabe00112233deadbeef");
    private static final byte[] DATA_2 = hex("0102030405060708090a");
    private static final byte[] HKDF_SALT = hex("f0e0d0c0b0a090807060504030201000112233445566778899aabbccddeeff00");
    private static final byte[] HKDF_IKM = hex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    private static final byte[] HKDF_INFO = "nge-platform-parity-info".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] B64_DATA = hex("00010203f0f1f2f37f80ff");
    private static final byte[] CHACHA_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] CHACHA_NONCE = hex("000000000000004a00000000");
    private static final byte[] CHACHA_DATA = "parity-chacha-message".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String SHA_STRING_INPUT = "nge-platform parity string";
    private static final byte[] SHA_BYTES_INPUT = hex("11223344556677889900aabbccddeeff");
    private static final String SIGN_DATA_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JVMPlatformParityMain <signalBaseUrl> <httpParityUrl>");
            System.exit(2);
            return;
        }
        String signalBase = args[0];
        String httpParityUrl = args[1];

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JsonObject result = new JsonObject();
        try {
            JVMAsyncPlatform._NO_AUX_RANDOM = true;
            JVMAsyncPlatform._EMPTY_NONCE = false;
            NGEPlatform p = new JVMAsyncPlatform();
            fillSnapshot(result, p, httpParityUrl);
            result.addProperty("ok", true);
        } catch (Throwable t) {
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", t.toString());
        }
        postJson(http, signalBase + "/result/jvm", result);
        if (!result.get("ok").getAsBoolean()) System.exit(1);
    }

    private static void fillSnapshot(JsonObject out, NGEPlatform p, String httpParityUrl) throws Exception {
        byte[] pubA = p.genPubKey(PRIV_A);
        byte[] pubB = p.genPubKey(PRIV_B);
        byte[] hmac = p.hmac(HMAC_KEY, DATA_1, DATA_2);
        byte[] prk = p.hkdf_extract(HKDF_SALT, HKDF_IKM);
        byte[] okm = p.hkdf_expand(prk, HKDF_INFO, 42);
        String b64 = p.base64encode(B64_DATA);
        byte[] b64rt = p.base64decode(b64);
        byte[] chachaEnc = p.chacha20(CHACHA_KEY, CHACHA_NONCE, CHACHA_DATA, true);
        byte[] chachaDec = p.chacha20(CHACHA_KEY, CHACHA_NONCE, chachaEnc, false);
        String shaStr = p.sha256(SHA_STRING_INPUT);
        byte[] shaBytes = p.sha256(SHA_BYTES_INPUT);

        Map<String, Object> jsonMap = new LinkedHashMap<>();
        jsonMap.put("a", 1);
        jsonMap.put("b", true);
        jsonMap.put("c", null);
        jsonMap.put("d", List.of("x", "y"));
        String mapJson = p.toJSON(jsonMap);
        String listJson = p.toJSON(Arrays.asList(1, "two", null, true));
        Map parsed = p.fromJSON("{\"x\":5,\"y\":[1,2],\"z\":{\"k\":\"v\"}}", Map.class);

        byte[] rnd = p.randomBytes(16);
        byte[] genPriv = p.generatePrivateKey();
        String sig = p.sign(SIGN_DATA_HEX, PRIV_A);
        boolean verifyOwn = p.verify(SIGN_DATA_HEX, sig, pubA);
        boolean verifyWrong = p.verify("ff" + SIGN_DATA_HEX.substring(2), sig, pubA);
        boolean verifyAsync = p.verifyAsync(SIGN_DATA_HEX, sig, pubA).await();
        String sigAsync = p.signAsync(SIGN_DATA_HEX, PRIV_A).await();
        var httpRes = p
            .httpRequest(
                "POST",
                httpParityUrl,
                "parity-http-body".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(10),
                Map.of("X-Parity-Req", "parity")
            )
            .await();
        String httpHdr = "";
        if (httpRes.headers() != null) {
            for (Map.Entry<String, List<String>> e : httpRes.headers().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("X-Parity-Reply")) {
                    httpHdr = e.getValue() == null || e.getValue().isEmpty() ? "" : String.valueOf(e.getValue().get(0));
                    break;
                }
            }
        }

        out.addProperty("pubA", hex(pubA));
        out.addProperty("pubB", hex(pubB));
        out.addProperty("hmac", hex(hmac));
        out.addProperty("hkdfExtract", hex(prk));
        out.addProperty("hkdfExpand", hex(okm));
        out.addProperty("base64", b64);
        out.addProperty("base64Roundtrip", hex(b64rt));
        out.addProperty("chachaEnc", hex(chachaEnc));
        out.addProperty("chachaDec", hex(chachaDec));
        out.addProperty("sha256String", shaStr);
        out.addProperty("sha256Bytes", hex(shaBytes));
        out.addProperty("jsonMap", mapJson);
        out.addProperty("jsonList", listJson);
        out.addProperty("fromJson_x", String.valueOf(((Number) parsed.get("x")).intValue()));
        out.addProperty("fromJson_y_len", String.valueOf(((List) parsed.get("y")).size()));
        out.addProperty("fromJson_z_k", String.valueOf(((Map) parsed.get("z")).get("k")));
        out.addProperty("signatureLen", sig.length());
        out.addProperty("signatureAsyncLen", sigAsync.length());
        out.addProperty("verifyOwn", verifyOwn);
        out.addProperty("verifyWrong", verifyWrong);
        out.addProperty("verifyAsync", verifyAsync);
        out.addProperty("randomLen", rnd.length);
        out.addProperty("randomNonZero", !allZero(rnd));
        out.addProperty("generatedPrivateKeyLen", genPriv.length);
        out.addProperty("generatedPrivateKeyNonZero", !allZero(genPriv));
        out.addProperty("httpRequest_status", httpRes.status());
        out.addProperty("httpRequest_statusCode", httpRes.statusCode());
        out.addProperty("httpRequest_body", httpRes.bodyAsString());
        out.addProperty("httpRequest_replyHeader", httpHdr);
    }

    private static void postJson(HttpClient http, String url, JsonObject payload) throws Exception {
        HttpRequest req = HttpRequest
            .newBuilder(URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST failed: " + res.statusCode() + " " + res.body());
        }
    }

    private static boolean allZero(byte[] data) {
        for (byte b : data) if (b != 0) return false;
        return true;
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
