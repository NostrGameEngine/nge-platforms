package org.ngengine.platform.android;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.NGEPlatform;

@RunWith(AndroidJUnit4.class)
public class AndroidPlatformParityInstrumentedTest {
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int PRIVATE_KEY_SANITY_SAMPLES = 32;

    private static final byte[] PRIV_A = hex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020101");
    private static final byte[] PRIV_B = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
    private static final byte[] HMAC_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] DATA_1 = hex("cafebabe00112233deadbeef");
    private static final byte[] DATA_2 = hex("0102030405060708090a");
    private static final byte[] HKDF_SALT = hex("f0e0d0c0b0a090807060504030201000112233445566778899aabbccddeeff00");
    private static final byte[] HKDF_IKM = hex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    private static final byte[] HKDF_INFO = "nge-platform-parity-info".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B64_DATA = hex("00010203f0f1f2f37f80ff");
    private static final byte[] CHACHA_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] CHACHA_NONCE = hex("000000000000004a00000000");
    private static final byte[] CHACHA_DATA = "parity-chacha-message".getBytes(StandardCharsets.UTF_8);
    private static final String SHA_STRING_INPUT = "nge-platform parity string";
    private static final byte[] SHA_BYTES_INPUT = hex("11223344556677889900aabbccddeeff");
    private static final String SIGN_DATA_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @BeforeClass
    public static void ensurePlatform() throws Exception {
        Field platformField = NGEPlatform.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        if (platformField.get(null) == null) {
            Context context = ApplicationProvider.getApplicationContext();
            platformField.set(null, new AndroidThreadedPlatform(context));
        }
    }

    @Test
    public void androidPlatformProducesParitySnapshot() throws Exception {
        String signalBase = InstrumentationRegistry.getArguments().getString("signalBase");
        String httpParityUrl = InstrumentationRegistry.getArguments().getString("httpParityUrl");
        assertNotNull("Missing instrumentation arg 'signalBase'", signalBase);
        assertNotNull("Missing instrumentation arg 'httpParityUrl'", httpParityUrl);

        OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

        JsonObject result = new JsonObject();
        try {
            Field noAuxRandom = AndroidThreadedPlatform.class.getDeclaredField("_NO_AUX_RANDOM");
            noAuxRandom.setAccessible(true);
            noAuxRandom.setBoolean(null, true);
            Field emptyNonce = AndroidThreadedPlatform.class.getDeclaredField("_EMPTY_NONCE");
            emptyNonce.setAccessible(true);
            emptyNonce.setBoolean(null, false);
            fillSnapshot(result, (AndroidThreadedPlatform) NGEPlatform.get(), httpParityUrl);
            result.addProperty("ok", true);
        } catch (Throwable t) {
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", stackTraceString(t));
        }

        postJson(http, signalBase + "/result/android", result);
        if (!result.get("ok").getAsBoolean()) {
            throw new AssertionError(result.get("error").getAsString());
        }
    }

    private static void fillSnapshot(JsonObject out, AndroidThreadedPlatform p, String httpParityUrl) throws Exception {
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
        ByteBuffer hmacKeyBuffer = nativeBuffer(p, HMAC_KEY);
        ByteBuffer data1Buffer = nativeBuffer(p, DATA_1);
        ByteBuffer data2Buffer = nativeBuffer(p, DATA_2);
        ByteBuffer hmacBuffer = p.hmac(hmacKeyBuffer, data1Buffer, data2Buffer);
        ByteBuffer shaInputBuffer = nativeBuffer(p, SHA_BYTES_INPUT);
        ByteBuffer shaBuffer = p.sha256(shaInputBuffer);
        if (
            hmacKeyBuffer.position() != 0 ||
            data1Buffer.position() != 0 ||
            data2Buffer.position() != 0 ||
            shaInputBuffer.position() != 0
        ) {
            throw new AssertionError("ByteBuffer crypto inputs were consumed");
        }
        if (!Arrays.equals(hmac, remainingBytes(hmacBuffer)) || !Arrays.equals(shaBytes, remainingBytes(shaBuffer))) {
            throw new AssertionError("ByteBuffer crypto result differs from byte[] result");
        }

        Map<String, Object> jsonMap = new LinkedHashMap<>();
        jsonMap.put("a", 1);
        jsonMap.put("b", true);
        jsonMap.put("c", null);
        jsonMap.put("d", Arrays.asList("x", "y"));
        String mapJson = p.toJSON(jsonMap);
        String listJson = p.toJSON(Arrays.asList(1, "two", null, true));
        Map parsed = p.fromJSON("{\"x\":5,\"y\":[1,2],\"z\":{\"k\":\"v\"}}", Map.class);

        byte[] rnd = p.randomBytes(16);
        byte[] genPriv = p.generatePrivateKey();
        JsonObject privateKeySanity = checkPrivateKeyGeneration(p);
        String sig = p.schnorrSign(SIGN_DATA_HEX, PRIV_A);
        boolean verifyOwn = p.schnorrVerify(SIGN_DATA_HEX, sig, pubA);
        boolean verifyWrong = p.schnorrVerify("ff" + SIGN_DATA_HEX.substring(2), sig, pubA);
        boolean verifyAsync = p.schnorrVerifyAsync(SIGN_DATA_HEX, sig, pubA).await();
        String sigAsync = p.schnorrSignAsync(SIGN_DATA_HEX, PRIV_A).await();
        var httpRes = p.httpRequest(
            "POST",
            httpParityUrl,
            "parity-http-body".getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(10),
            Map.of("X-Parity-Req", "parity")
        ).await();
        ByteBuffer httpBodyBuffer = nativeBuffer(p, "parity-http-body".getBytes(StandardCharsets.UTF_8));
        var httpBufferRes = p
            .httpRequest(
                "POST",
                httpParityUrl,
                httpBodyBuffer,
                Duration.ofSeconds(10),
                Map.of("X-Parity-Req", "parity")
            )
            .await();
        if (httpBodyBuffer.position() != 0) {
            throw new AssertionError("ByteBuffer HTTP body was consumed");
        }
        if (
            !httpBufferRes.status() ||
            httpBufferRes.statusCode() != 201 ||
            !"echo:parity-http-body|req:parity".equals(httpBufferRes.bodyAsString())
        ) {
            throw new AssertionError("ByteBuffer HTTP request differs from byte[] request");
        }
        String httpHdr = "";
        if (httpRes.headers() != null) {
            for (Map.Entry<String, List<String>> e : httpRes.headers().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("X-Parity-Reply")) {
                    httpHdr = (e.getValue() == null || e.getValue().isEmpty()) ? "" : String.valueOf(e.getValue().get(0));
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
        out.add("privateKeySanity", privateKeySanity);
        out.addProperty("httpRequest_status", httpRes.status());
        out.addProperty("httpRequest_statusCode", httpRes.statusCode());
        out.addProperty("httpRequest_body", httpRes.bodyAsString());
        out.addProperty("httpRequest_replyHeader", httpHdr);
        out.addProperty("bufferOverridesVerified", true);
    }

    private static JsonObject checkPrivateKeyGeneration(NGEPlatform platform) {
        Set<String> uniqueKeys = new HashSet<>();
        for (int sample = 0; sample < PRIVATE_KEY_SANITY_SAMPLES; sample++) {
            byte[] key = platform.generatePrivateKey();
            if (key.length != 32) {
                throw new AssertionError("Generated private key has length " + key.length);
            }
            if (!platform.secp256k1PrivateKeyVerify(key)) {
                throw new AssertionError("Generated private key is not a valid secp256k1 scalar");
            }
            if (isTrivialPrivateKey(key)) {
                throw new AssertionError("Generated private key is trivial");
            }
            if (!uniqueKeys.add(hex(key))) {
                throw new AssertionError("Duplicate generated private key in sanity sample");
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("sampleCount", PRIVATE_KEY_SANITY_SAMPLES);
        result.addProperty("uniqueCount", uniqueKeys.size());
        result.addProperty("allValid", true);
        result.addProperty("noTrivialValues", true);
        return result;
    }

    private static boolean isTrivialPrivateKey(byte[] key) {
        boolean allSame = true;
        boolean scalarOne = (key[key.length - 1] & 0xff) == 1;
        for (int i = 0; i < key.length; i++) {
            allSame &= key[i] == key[0];
            if (i < key.length - 1 && key[i] != 0) scalarOne = false;
        }
        return allSame || scalarOne;
    }

    private static void postJson(OkHttpClient client, String url, JsonObject body) throws IOException {
        Request request = new Request.Builder().url(url).post(RequestBody.create(GSON.toJson(body), JSON)).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST " + url + " failed: " + response.code());
            }
        }
    }

    private static String stackTraceString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static boolean allZero(byte[] data) {
        for (byte b : data) if (b != 0) return false;
        return true;
    }

    private static ByteBuffer nativeBuffer(NGEPlatform platform, byte[] data) {
        ByteBuffer buffer = platform.getNativeAllocator().malloc(data.length);
        if (!buffer.isDirect()) {
            throw new AssertionError("Native allocator returned a non-direct buffer");
        }
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    private static byte[] remainingBytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.duplicate();
        byte[] data = new byte[source.remaining()];
        source.get(data);
        return data;
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
