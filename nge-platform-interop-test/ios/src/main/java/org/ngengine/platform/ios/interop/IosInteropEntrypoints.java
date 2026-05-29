package org.ngengine.platform.ios.interop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ngengine.libjglios.core.LibJGLIOSLifecycleBridge;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.ios.IosPlatform;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public final class IosInteropEntrypoints {
    private static final Gson GSON = new Gson();
    private static final int WS_STRESS_MESSAGES = 24;
    private static final int RTC_STRESS_MESSAGES = 24;

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

    private static volatile boolean started;
    private static volatile int frameCount;
    private static volatile int exitCode;

    public IosInteropEntrypoints() {
        start();
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

    public void resize(int width, int height) {}

    public void stop(boolean successful) {
        started = false;
    }

    private static void runHarness() {
        String signalBase = System.getenv("IOS_INTEROP_SIGNAL_BASE");
        IosPlatform platform = null;
        JsonObject result = new JsonObject();
        try {
            if (signalBase == null || signalBase.isBlank()) {
                throw new IllegalArgumentException("IOS_INTEROP_SIGNAL_BASE is required");
            }
            String kind = System.getenv("IOS_INTEROP_KIND");
            if (kind == null || kind.isBlank()) {
                kind = "parity";
            }

            System.setProperty("nge-platforms.allowLoopbackInURIs", "true");
            platform = new IosPlatform();
            setGlobalPlatform(platform);
            runParity(platform, signalBase, result);
            runAsync(platform, result);
            runAsset(platform, result);
            runWebSocket(platform, signalBase, result);
            if (!"false".equalsIgnoreCase(System.getenv().getOrDefault("IOS_INTEROP_ENABLE_RTC", "true"))) {
                runRtc(platform, signalBase, result);
            }
            result.addProperty("kind", kind);
            result.addProperty("ok", true);
        } catch (Throwable t) {
            exitCode = 1;
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", stackTraceString(t));
        }

        emitResultLine(result);
        if (signalBase != null && !signalBase.isBlank()) {
            try {
                if (result.has("ok") && !result.get("ok").getAsBoolean() && result.has("error")) {
                    System.err.println("iOS interop result before post: " + result.get("error").getAsString());
                }
                postResult(platform, signalBase + "/result/ios", result);
            } catch (Throwable t) {
                System.err.println("Failed to post iOS interop result: " + stackTraceString(t));
            }
        }
        if (!result.has("ok") || !result.get("ok").getAsBoolean()) {
            exitCode = 1;
        }
        LibJGLIOSLifecycleBridge.requestQuit();
    }

    private static void runParity(IosPlatform platform, String signalBase, JsonObject out) throws Exception {
        IosPlatform._NO_AUX_RANDOM = true;
        IosPlatform._EMPTY_NONCE = false;

        byte[] pubA = platform.genPubKey(PRIV_A);
        byte[] pubB = platform.genPubKey(PRIV_B);
        byte[] hmac = platform.hmac(HMAC_KEY, DATA_1, DATA_2);
        byte[] prk = platform.hkdf_extract(HKDF_SALT, HKDF_IKM);
        byte[] okm = platform.hkdf_expand(prk, HKDF_INFO, 42);
        String b64 = platform.base64encode(B64_DATA);
        byte[] b64rt = platform.base64decode(b64);
        byte[] chachaEnc = platform.chacha20(CHACHA_KEY, CHACHA_NONCE, CHACHA_DATA, true);
        byte[] chachaDec = platform.chacha20(CHACHA_KEY, CHACHA_NONCE, chachaEnc, false);
        String shaStr = platform.sha256(SHA_STRING_INPUT);
        byte[] shaBytes = platform.sha256(SHA_BYTES_INPUT);

        Map<String, Object> jsonMap = new LinkedHashMap<>();
        jsonMap.put("a", 1);
        jsonMap.put("b", true);
        jsonMap.put("c", null);
        jsonMap.put("d", List.of("x", "y"));
        String mapJson = platform.toJSON(jsonMap);
        String listJson = platform.toJSON(Arrays.asList(1, "two", null, true));
        Map parsed = platform.fromJSON("{\"x\":5,\"y\":[1,2],\"z\":{\"k\":\"v\"}}", Map.class);

        byte[] rnd = platform.randomBytes(16);
        byte[] genPriv = platform.generatePrivateKey();
        String sig = platform.schnorrSign(SIGN_DATA_HEX, PRIV_A);
        boolean verifyOwn = platform.schnorrVerify(SIGN_DATA_HEX, sig, pubA);
        boolean verifyWrong = platform.schnorrVerify("ff" + SIGN_DATA_HEX.substring(2), sig, pubA);
        boolean verifyAsync = platform.schnorrVerifyAsync(SIGN_DATA_HEX, sig, pubA).await();
        String sigAsync = platform.schnorrSignAsync(SIGN_DATA_HEX, PRIV_A).await();

        String httpBase = signalBase.replaceFirst("/signal$", "");
        NGEHttpResponse postResponse = platform
            .httpRequest(
                "POST",
                httpBase + "/parity-http",
                "parity-http-body".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(15),
                Map.of("X-Parity-Req", "parity")
            )
            .await();
        NGEHttpResponse getResponse = platform
            .httpRequest("GET", httpBase + "/ios-http-get?from=ios", null, Duration.ofSeconds(15), Map.of("X-IOS-Get", "ios"))
            .await();

        out.addProperty("platformName", platform.getPlatformName());
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
        out.addProperty("signatureVerified", verifyOwn);
        out.addProperty("verifyOwn", verifyOwn);
        out.addProperty("verifyWrong", verifyWrong);
        out.addProperty("verifyAsync", verifyAsync);
        out.addProperty("randomLen", rnd.length);
        out.addProperty("randomNonZero", !allZero(rnd));
        out.addProperty("generatedPrivateKeyLen", genPriv.length);
        out.addProperty("generatedPrivateKeyNonZero", !allZero(genPriv));
        out.addProperty("httpRequest_status", postResponse.status());
        out.addProperty("httpRequest_statusCode", postResponse.statusCode());
        out.addProperty("httpRequest_body", postResponse.bodyAsString());
        out.addProperty("httpRequest_replyHeader", headerValue(postResponse.headers(), "X-Parity-Reply"));
        out.addProperty("httpGet_statusCode", getResponse.statusCode());
        out.addProperty("httpGet_body", getResponse.bodyAsString());
    }

    private static void runAsync(IosPlatform platform, JsonObject out) throws Exception {
        AsyncExecutor executor = platform.newAsyncExecutor("ios-interop");
        try {
            out.addProperty("asyncValue", executor.runLater(() -> "ios-async-ok", 10, TimeUnit.MILLISECONDS).await());
        } finally {
            executor.close();
        }
    }

    private static void runAsset(IosPlatform platform, JsonObject out) throws Exception {
        try (InputStream in = platform.openResource("ios-interop-resource.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            out.addProperty("assetResource", text);
            out.addProperty("assetResourceSha256", platform.sha256(text));
        }
    }

    private static void runWebSocket(IosPlatform platform, String signalBase, JsonObject out) throws Exception {
        String wsUrl = signalBase.replaceFirst("^http", "ws").replaceFirst("/signal$", "/ios-ws");
        WebsocketTransport ws = platform.newTransport();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        BlockingQueue<String> textInbox = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> binaryInbox = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        ws.addListener(new WebsocketTransportListener() {
            @Override
            public void onConnectionOpen() {
                openLatch.countDown();
            }

            @Override
            public void onConnectionMessage(String msg) {
                textInbox.offer(msg);
            }

            @Override
            public void onConnectionBinaryMessage(ByteBuffer msg) {
                byte[] bytes = new byte[msg.remaining()];
                msg.duplicate().get(bytes);
                binaryInbox.offer(bytes);
            }

            @Override
            public void onConnectionClosedByServer(String reason) {
                serverCloseLatch.countDown();
            }

            @Override
            public void onConnectionClosedByClient(String reason) {}

            @Override
            public void onConnectionError(Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        try {
            ws.connect(wsUrl).await();
            if (!openLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for WebSocket open");
            }
            expectText(textInbox, "welcome", 10, "WebSocket welcome");
            ws.send("echo:ios-text").await();
            expectText(textInbox, "echo:ios-text", 10, "WebSocket text echo");
            ws.sendBinary(ByteBuffer.wrap(new byte[] { 9, 8, 7, 6 })).await();
            byte[] binary = binaryInbox.poll(10, TimeUnit.SECONDS);
            if (!Arrays.equals(binary, new byte[] { 9, 8, 7, 6 })) {
                throw new IllegalStateException("WebSocket binary echo mismatch");
            }
            for (int i = 0; i < WS_STRESS_MESSAGES; i++) {
                ws.send("stress-client:" + i).await();
                expectText(textInbox, "stress-client:" + i, 10, "WebSocket client stress " + i);
            }
            ws.send("burst-server:" + WS_STRESS_MESSAGES).await();
            for (int i = 0; i < WS_STRESS_MESSAGES; i++) {
                expectText(textInbox, "stress-server:" + i, 10, "WebSocket server stress " + i);
            }
            ws.send("close-by-server").await();
            if (!serverCloseLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for WebSocket server close");
            }
            awaitNoListenerError(error, "WebSocket");
            out.addProperty("websocketOk", true);
            out.addProperty("websocketClientStressCount", WS_STRESS_MESSAGES);
            out.addProperty("websocketServerStressCount", WS_STRESS_MESSAGES);
        } finally {
            try {
                ws.close("done").await();
            } catch (Throwable ignored) {}
        }
    }

    private static void runRtc(IosPlatform platform, String signalBase, JsonObject out) throws Exception {
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean stopPolling = new AtomicBoolean(false);
        AtomicReference<String> answerSdp = new AtomicReference<>();
        CountDownLatch answerLatch = new CountDownLatch(1);
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch nodeReadyLatch = new CountDownLatch(1);
        CountDownLatch nodeResultLatch = new CountDownLatch(1);
        BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> listenerError = new AtomicReference<>();
        Map<String, String> meta = new ConcurrentHashMap<>();
        RTCTransport transport = platform.newRTCTransport(Duration.ofSeconds(60), "ios-node-interop", List.of());

        Thread poller = null;
        try {
            transport.addListener(new RTCTransportListener() {
                @Override
                public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("to", "node");
                    msg.addProperty("type", "ice");
                    msg.addProperty("candidate", candidate.getCandidate());
                    msg.addProperty("sdpMid", candidate.getSdpMid());
                    try {
                        postJson(platform, signalBase + "/send", msg);
                    } catch (Exception e) {
                        listenerError.compareAndSet(null, e);
                    }
                }

                @Override
                public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                    byte[] bytes = new byte[msg.remaining()];
                    msg.duplicate().get(bytes);
                    inbox.offer(bytes);
                }

                @Override
                public void onRTCChannelError(RTCDataChannel channel, Throwable e) {
                    listenerError.compareAndSet(null, e);
                }

                @Override
                public void onRTCChannelReady(RTCDataChannel channel) {}

                @Override
                public void onRTCBufferedAmountLow(RTCDataChannel channel) {}

                @Override
                public void onRTCChannelClosed(RTCDataChannel channel) {}

                @Override
                public void onRTCDisconnected(String reason) {
                    listenerError.compareAndSet(null, new IllegalStateException("RTC disconnected: " + reason));
                }

                @Override
                public void onRTCConnected() {
                    connectedLatch.countDown();
                }
            });

            poller = new Thread(() -> {
                while (!stopPolling.get()) {
                    try {
                        JsonObject poll = getJson(platform, signalBase + "/poll?to=ios&after=" + cursor.get());
                        if (poll.has("cursor")) {
                            cursor.set(poll.get("cursor").getAsInt());
                        }
                        JsonArray messages = poll.has("messages") ? poll.getAsJsonArray("messages") : new JsonArray();
                        for (JsonElement el : messages) {
                            JsonObject msg = el.getAsJsonObject();
                            String type = msg.get("type").getAsString();
                            if ("answer".equals(type)) {
                                if (answerSdp.compareAndSet(null, msg.get("sdp").getAsString())) {
                                    answerLatch.countDown();
                                }
                            } else if ("ice".equals(type)) {
                                String candidate = msg.get("candidate").getAsString();
                                String sdpMid = msg.has("sdpMid") && !msg.get("sdpMid").isJsonNull()
                                    ? msg.get("sdpMid").getAsString()
                                    : null;
                                try {
                                    transport.addRemoteIceCandidates(List.of(new RTCTransportIceCandidate(candidate, sdpMid)));
                                } catch (Throwable ignored) {}
                            } else if ("node-ready".equals(type)) {
                                nodeReadyLatch.countDown();
                            } else if ("node-result".equals(type)) {
                                if (msg.has("iosToNodeStressCount")) {
                                    meta.put("iosToNodeStressCount", msg.get("iosToNodeStressCount").getAsString());
                                }
                                nodeResultLatch.countDown();
                            } else if ("node-failed".equals(type)) {
                                listenerError.compareAndSet(
                                    null,
                                    new IllegalStateException("Node RTC failed: " + msg.get("error").getAsString())
                                );
                            }
                        }
                        Thread.sleep(50);
                    } catch (Throwable t) {
                        listenerError.compareAndSet(null, t);
                        return;
                    }
                }
            }, "ios-rtc-interop-poller");
            poller.setDaemon(true);
            poller.start();

            String offer = transport.listen().await();
            JsonObject offerMsg = new JsonObject();
            offerMsg.addProperty("to", "node");
            offerMsg.addProperty("type", "offer");
            offerMsg.addProperty("sdp", offer);
            postJson(platform, signalBase + "/send", offerMsg);

            awaitNoListenerError(listenerError, "waiting for node answer");
            if (!answerLatch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Node SDP answer");
            }
            transport.connect(answerSdp.get()).await();
            awaitNoListenerError(listenerError, "waiting for RTC connected");
            if (!connectedLatch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for RTC connected event");
            }

            RTCDataChannel channel = awaitDefaultChannel(transport, 20, TimeUnit.SECONDS);
            channel.ready().await();
            if (!nodeReadyLatch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for node-ready signal");
            }

            sendRtcStressBurst(channel, "ios-seq", RTC_STRESS_MESSAGES);
            assertReceiveRtcStressBurst(inbox, listenerError, "node-seq", RTC_STRESS_MESSAGES, 30, TimeUnit.SECONDS);
            if (!nodeResultLatch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for node RTC result");
            }
            if (!String.valueOf(RTC_STRESS_MESSAGES).equals(meta.get("iosToNodeStressCount"))) {
                throw new IllegalStateException("Node RTC stress count mismatch: " + meta.get("iosToNodeStressCount"));
            }

            out.addProperty("rtcOk", true);
            out.addProperty("iosToNodeRtcStressCount", RTC_STRESS_MESSAGES);
            out.addProperty("nodeToIosRtcStressCount", RTC_STRESS_MESSAGES);
            out.addProperty("rtcMaxMessageSize", channel.getMaxMessageSize().await().intValue());
            out.addProperty("rtcBufferedAmount", channel.getBufferedAmount().await().intValue());
            channel.close().await();
        } finally {
            stopPolling.set(true);
            if (poller != null) {
                poller.join(500);
            }
            try {
                transport.close();
            } catch (Throwable ignored) {}
        }
    }

    private static RTCDataChannel awaitDefaultChannel(RTCTransport transport, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            RTCDataChannel channel = transport.getDataChannel(RTCTransport.DEFAULT_CHANNEL);
            if (channel != null) {
                return channel;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Timed out waiting for default RTC data channel");
    }

    private static void sendRtcStressBurst(RTCDataChannel channel, String prefix, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            channel.write(ByteBuffer.wrap((prefix + ":" + i).getBytes(StandardCharsets.UTF_8))).await();
        }
    }

    private static void assertReceiveRtcStressBurst(
        BlockingQueue<byte[]> inbox,
        AtomicReference<Throwable> listenerError,
        String prefix,
        int count,
        long timeout,
        TimeUnit unit
    ) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (int i = 0; i < count; i++) {
            awaitNoListenerError(listenerError, "waiting for RTC stress " + i);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Timed out waiting for RTC stress burst " + i + "/" + count);
            }
            byte[] bytes = inbox.poll(remaining, TimeUnit.NANOSECONDS);
            if (bytes == null) {
                throw new IllegalStateException("Timed out waiting for RTC stress message " + i);
            }
            String actual = new String(bytes, StandardCharsets.UTF_8);
            String expected = prefix + ":" + i;
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Out-of-order RTC message: expected=" + expected + " actual=" + actual);
            }
        }
    }

    private static void awaitNoListenerError(AtomicReference<Throwable> error, String phase) throws Exception {
        Throwable t = error.get();
        if (t != null) {
            throw new IllegalStateException("Listener error while " + phase, t);
        }
    }

    private static void expectText(BlockingQueue<String> inbox, String expected, long timeoutSeconds, String phase)
        throws Exception {
        String actual = inbox.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(phase + " mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    private static JsonObject getJson(IosPlatform platform, String url) throws Exception {
        NGEHttpResponse res = platform.httpRequest("GET", url, null, Duration.ofSeconds(15), Map.of()).await();
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("GET failed: " + res.statusCode() + " " + res.bodyAsString());
        }
        return GSON.fromJson(res.bodyAsString(), JsonObject.class);
    }

    private static void postJson(IosPlatform platform, String url, JsonObject payload) throws Exception {
        NGEHttpResponse response = platform
            .httpRequest(
                "POST",
                url,
                GSON.toJson(payload).getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(15),
                Map.of("Content-Type", "application/json; charset=utf-8")
            )
            .await();
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST failed: " + response.statusCode() + " " + response.bodyAsString());
        }
    }

    private static void postResult(IosPlatform platform, String url, JsonObject payload) throws Exception {
        if (platform == null) {
            postJsonWithUrlConnection(url, payload);
            return;
        }
        NGEHttpResponse response = platform
            .httpRequest(
                "POST",
                url,
                GSON.toJson(payload).getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(15),
                Map.of("Content-Type", "application/json; charset=utf-8")
            )
            .await();
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST failed: " + response.statusCode() + " " + response.bodyAsString());
        }
    }

    private static void postJsonWithUrlConnection(String url, JsonObject payload) throws Exception {
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.getOutputStream().write(body);
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("POST failed: " + status);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void emitResultLine(JsonObject result) {
        String json = GSON.toJson(result);
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        System.err.println("IOS_INTEROP_RESULT_JSON_BASE64=" + encoded);
    }

    private static String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() == null || e.getValue().isEmpty() ? "" : String.valueOf(e.getValue().get(0));
            }
        }
        return "";
    }

    private static void setGlobalPlatform(IosPlatform platform) throws Exception {
        Field platformField = NGEPlatform.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        platformField.set(null, platform);
    }

    private static String stackTraceString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
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

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
