package org.ngengine.platform.ios.interop;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.ngengine.libjglios.core.LibJGLIOSLifecycleBridge;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.ios.IosPlatform;

public final class IosInteropEntrypoints {
    private static final Gson GSON = new Gson();
    private static volatile boolean started;
    private static volatile int frameCount;
    private static volatile int exitCode;

    public IosInteropEntrypoints() {
    }

    public static void main(String[] args) {
        new IosInteropEntrypoints().start();
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        frameCount = 0;
        exitCode = 0;
        Thread harnessThread = new Thread(IosInteropEntrypoints::runHarness, "nge-ios-interop-harness");
        harnessThread.setDaemon(true);
        harnessThread.start();
    }

    public void frame() {
        frameCount++;
    }

    public void resize(int width, int height) {
    }

    public void stop(boolean successful) {
        started = false;
    }

    private static void runHarness() {
        String signalBase = System.getenv("IOS_INTEROP_SIGNAL_BASE");
        JsonObject result = new JsonObject();
        try {
            if (signalBase == null || signalBase.isBlank()) {
                throw new IllegalArgumentException("IOS_INTEROP_SIGNAL_BASE is required");
            }
            String kind = System.getenv("IOS_INTEROP_KIND");
            if (kind == null || kind.isBlank()) {
                kind = "smoke";
            }

            IosPlatform platform = new IosPlatform();
            setGlobalPlatform(platform);
            runSmoke(platform, signalBase, result);
            result.addProperty("kind", kind);
            result.addProperty("ok", true);
        } catch (Throwable t) {
            exitCode = 1;
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", stackTraceString(t));
        }

        if (signalBase != null && !signalBase.isBlank()) {
            try {
                postJson(signalBase + "/result/ios", result);
            } catch (Throwable t) {
                exitCode = 1;
                System.err.println("Failed to post iOS interop result: " + stackTraceString(t));
            }
        }
        if (!result.has("ok") || !result.get("ok").getAsBoolean()) {
            exitCode = 1;
        }
        LibJGLIOSLifecycleBridge.requestQuit();
    }

    private static void runSmoke(IosPlatform platform, String signalBase, JsonObject out) throws Exception {
        IosPlatform._NO_AUX_RANDOM = true;
        IosPlatform._EMPTY_NONCE = false;

        byte[] privateKey = hex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020101");
        byte[] publicKey = platform.genPubKey(privateKey);
        String data = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        String signature = platform.schnorrSign(data, privateKey);
        boolean verified = platform.schnorrVerify(data, signature, publicKey);
        String asyncValue;
        AsyncExecutor executor = platform.newAsyncExecutor("ios-interop");
        try {
            asyncValue = executor.runLater(() -> "ios-async-ok", 10, TimeUnit.MILLISECONDS).await();
        } finally {
            executor.close();
        }

        String httpUrl = signalBase.replaceFirst("/signal$", "") + "/ios-http";
        var httpResponse = platform
            .httpRequest(
                "POST",
                httpUrl,
                "ios-http-body".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(15),
                Map.of("X-IOS-Interop", "ios")
            )
            .await();

        byte[] random = platform.randomBytes(8);
        out.addProperty("platformName", platform.getPlatformName());
        out.addProperty("sha256", platform.sha256("ios-interoperability"));
        out.addProperty("publicKey", NGEUtils.bytesToHex(publicKey));
        out.addProperty("signatureLen", signature.length());
        out.addProperty("signatureVerified", verified);
        out.addProperty("asyncValue", asyncValue);
        out.addProperty("randomLen", random.length);
        out.addProperty("randomNonZero", !allZero(random));
        out.addProperty("httpStatusCode", httpResponse.statusCode());
        out.addProperty("httpBody", httpResponse.bodyAsString());
    }

    private static void setGlobalPlatform(IosPlatform platform) throws Exception {
        Field platformField = NGEPlatform.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        platformField.set(null, platform);
    }

    private static void postJson(String url, JsonObject payload) throws Exception {
        HttpRequest request = HttpRequest
            .newBuilder(URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST failed: " + response.statusCode() + " " + response.body());
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
        for (byte b : data) {
            if (b != 0) {
                return false;
            }
        }
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
}
