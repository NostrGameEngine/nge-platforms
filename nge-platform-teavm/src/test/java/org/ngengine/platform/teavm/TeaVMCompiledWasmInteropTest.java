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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.ngengine.platform.transport.NGEHttpResponseStream;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;
import org.teavm.classlib.PlatformDetector;
import org.teavm.jso.JSBody;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

/**
 * These methods are compiled by the TeaVM JUnit runner. The normal test run
 * verifies that both JavaScript and Wasm GC can compile and enter every method.
 * The external harness re-opens the generated Wasm GC pages with endpoint query
 * parameters, causing the actual Java implementation below to perform live
 * interoperability against the JVM backend.
 */
@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMCompiledWasmInteropTest {

    private static final int STRESS_MESSAGES = 256;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void compiledWasmPlatformServicesParity() throws Exception {
        String signalBase = queryParameter("signalBase");
        if (signalBase == null) {
            return;
        }
        requireWasm();
        TeaVMPlatform platform = installPlatform();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            installHostBindings();
            assertTrue(platform.getPlatformName(), platform.getPlatformName().startsWith("TeaVM Wasm GC (browser"));

            result.put("platformName", platform.getPlatformName());
            result.put("sha256Bytes", hex(platform.sha256(hex("11223344556677889900aabbccddeeff"))));
            result.put("base64", platform.base64encode(hex("00010203f0f1f2f37f80ff")));
            result.put(
                "hmac",
                hex(
                    platform.hmac(
                        hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                        hex("cafebabe00112233deadbeef"),
                        hex("0102030405060708090a")
                    )
                )
            );

            String httpUrl = queryParameter("httpUrl");
            assertNotNull("Missing httpUrl query parameter", httpUrl);
            ByteBuffer requestBody = direct(platform, utf8("parity-http-body"));
            NGEHttpResponse response = platform
                .httpRequest("POST", httpUrl, requestBody, TIMEOUT, Map.of("X-Parity-Req", "parity"))
                .await();
            assertEquals(0, requestBody.position());
            assertTrue(response.status());
            assertEquals(201, response.statusCode());
            assertEquals("echo:parity-http-body|req:parity", response.bodyAsString());
            result.put("httpRequest_status", response.status());
            result.put("httpRequest_statusCode", response.statusCode());
            result.put("httpRequest_body", response.bodyAsString());

            NGEHttpResponse binaryResponse = platform
                .httpRequest("POST", httpUrl + "?binary=1", new byte[0], TIMEOUT, Map.of())
                .await();
            assertTrue(binaryResponse.status());
            assertEquals(201, binaryResponse.statusCode());
            assertEquals("000102037f80feff004e4745", hex(binaryResponse.body()));
            result.put("httpRequest_binary", hex(binaryResponse.body()));

            NGEHttpResponse emptyResponse = platform
                .httpRequest("POST", httpUrl + "?empty=1", new byte[0], TIMEOUT, Map.of())
                .await();
            assertTrue(emptyResponse.status());
            assertEquals(201, emptyResponse.statusCode());
            assertEquals(0, emptyResponse.body().length);
            result.put("httpRequest_emptyLength", emptyResponse.body().length);

            NGEHttpResponseStream streamResponse = platform
                .httpRequestStream("POST", httpUrl, utf8("parity-stream-body"), TIMEOUT, Map.of("X-Parity-Req", "stream"))
                .await();
            assertTrue(streamResponse.status());
            assertEquals(201, streamResponse.statusCode());
            String streamBody;
            try (InputStream body = streamResponse.body()) {
                streamBody = new String(readFully(body), StandardCharsets.UTF_8);
            }
            assertEquals("echo:parity-stream-body|req:stream", streamBody);
            result.put("httpRequestStream_statusCode", streamResponse.statusCode());
            result.put("httpRequestStream_body", streamBody);

            byte[] scrypt = platform.scrypt(utf8("pw"), utf8("salt"), 1024, 8, 1, 32);
            ByteBuffer scryptPassword = direct(platform, utf8("pw"));
            ByteBuffer scryptSalt = direct(platform, utf8("salt"));
            ByteBuffer scryptBuffer = platform.scrypt(scryptPassword, scryptSalt, 1024, 8, 1, 32);
            assertTrue(scryptBuffer.isDirect());
            assertEquals(hex(scrypt), hex(bytes(scryptBuffer)));
            assertEquals(0, scryptPassword.position());
            assertEquals(0, scryptSalt.position());
            result.put("scrypt", hex(scrypt));

            AsyncExecutor executor = platform.newAsyncExecutor();
            try {
                assertEquals(9, executor.runLater(() -> 9, 10, TimeUnit.MILLISECONDS).await().intValue());
                result.put("executorRunLater", true);
            } finally {
                executor.close();
            }

            assertTrue(awaitCanCall(platform, "interop.sum"));
            assertFalse(awaitCanCall(platform, "interop.missing"));
            assertEquals(5, ((Number) awaitCall(platform, "interop.sum", new Object[] { 2, 3 })).intValue());
            result.put("callFunction", true);

            platform.openInWebBrowser("https://example.invalid/wasm");
            assertEquals("https://example.invalid/wasm", openedUrl());
            result.put("openURL", true);

            platform.setClipboardContent("wasm-clipboard");
            sleep(platform, 20);
            assertEquals("wasm-clipboard", platform.getClipboardContent().await());
            result.put("clipboard", true);

            verifyPersistentStore(platform);
            result.put("persistentStore", true);

            int[] finalizerCalls = { 0 };
            Runnable cleanup = platform.registerFinalizer(new Object(), () -> finalizerCalls[0]++);
            cleanup.run();
            cleanup.run();
            assertEquals(1, finalizerCalls[0]);
            result.put("finalizerExactlyOnce", true);

            int[] automaticFinalizerCalls = { 0 };
            int registrationIndex = trackedFinalizerRegistrationCount();
            registerForAutomaticCleanup(platform, automaticFinalizerCalls);
            assertEquals(
                "Cleaner must create exactly one FinalizationRegistry entry",
                registrationIndex + 1,
                trackedFinalizerRegistrationCount()
            );
            assertTrue(
                "The compiled Wasm harness must expose the FinalizationRegistry callback hook",
                triggerFinalizerRegistration(registrationIndex)
            );
            assertEquals(
                "Cleaner action was not invoked by the Wasm FinalizationRegistry callback",
                1,
                automaticFinalizerCalls[0]
            );
            result.put("finalizerAutomatic", true);

            result.put("ok", true);
            postJson(platform, signalBase + "/result/wasm", result).await();
        } catch (Throwable failure) {
            postFailure(platform, signalBase, failure);
            throw failure;
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void compiledWasmWebsocketParity() throws Exception {
        String signalBase = queryParameter("signalBase");
        if (signalBase == null) {
            return;
        }
        requireWasm();
        TeaVMPlatform platform = installPlatform();
        String wsUrl = queryParameter("wsUrl");
        assertNotNull("Missing wsUrl query parameter", wsUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        WebsocketTransport websocket = platform.newTransport();
        List<String> textInbox = new ArrayList<>();
        List<byte[]> binaryInbox = new ArrayList<>();
        AtomicReference<Throwable> listenerError = new AtomicReference<>();
        int[] openCount = { 0 };
        int[] serverCloseCount = { 0 };
        try {
            websocket.addListener(
                new WebsocketTransportListener() {
                    @Override
                    public void onConnectionClosedByServer(String reason) {
                        serverCloseCount[0]++;
                    }

                    @Override
                    public void onConnectionOpen() {
                        openCount[0]++;
                    }

                    @Override
                    public void onConnectionMessage(String msg) {
                        textInbox.add(msg);
                    }

                    @Override
                    public void onConnectionBinaryMessage(ByteBuffer msg) {
                        binaryInbox.add(bytes(msg));
                    }

                    @Override
                    public void onConnectionClosedByClient(String reason) {}

                    @Override
                    public void onConnectionError(Throwable error) {
                        listenerError.compareAndSet(null, error);
                    }
                }
            );

            websocket.connect(wsUrl).await();
            waitUntil(platform, () -> openCount[0] == 1, listenerError, "WebSocket open");
            assertTrue(websocket.isConnected());
            assertEquals("welcome", awaitText(platform, textInbox, listenerError, "welcome"));

            websocket.send("echo:wasm").await();
            assertEquals("echo:wasm", awaitText(platform, textInbox, listenerError, "text echo"));

            ByteBuffer binary = direct(platform, utf8("wasm-binary"));
            websocket.sendBinary(binary).await();
            assertEquals(0, binary.position());
            waitUntil(platform, () -> !binaryInbox.isEmpty(), listenerError, "binary echo");
            assertEquals("wasm-binary", new String(binaryInbox.remove(0), StandardCharsets.UTF_8));

            for (int i = 0; i < STRESS_MESSAGES; i++) {
                websocket.send("stress-client:" + i).await();
            }
            for (int i = 0; i < STRESS_MESSAGES; i++) {
                assertEquals("stress-client:" + i, awaitText(platform, textInbox, listenerError, "client stress echo " + i));
            }

            websocket.send("burst-server:" + STRESS_MESSAGES).await();
            for (int i = 0; i < STRESS_MESSAGES; i++) {
                assertEquals("stress-server:" + i, awaitText(platform, textInbox, listenerError, "server stress message " + i));
            }

            websocket.send("close-by-server").await();
            waitUntil(platform, () -> serverCloseCount[0] == 1, listenerError, "server close");
            assertFalse(websocket.isConnected());

            result.put("ok", true);
            result.put("compiledBackend", platform.getPlatformName());
            result.put("openCount", openCount[0]);
            result.put("textEcho", true);
            result.put("binaryDirectEcho", true);
            result.put("clientToServerStressCount", STRESS_MESSAGES);
            result.put("serverToClientStressCount", STRESS_MESSAGES);
            result.put("ordered", true);
            result.put("serverCloseCount", serverCloseCount[0]);
            postJson(platform, signalBase + "/result/wasm", result).await();
        } catch (Throwable failure) {
            postFailure(platform, signalBase, failure);
            throw failure;
        } finally {
            if (websocket.isConnected()) {
                try {
                    websocket.close("test-finished").await();
                } catch (Exception ignored) {}
            }
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void compiledWasmRtcInteroperatesWithJvm() throws Exception {
        String signalBase = queryParameter("signalBase");
        if (signalBase == null) {
            return;
        }
        requireWasm();
        TeaVMPlatform platform = installPlatform();
        RTCTransport transport = platform.newRTCTransport(TIMEOUT, "jvm-teavm-interop", List.of());
        List<byte[]> inbox = new ArrayList<>();
        AtomicReference<Throwable> listenerError = new AtomicReference<>();
        boolean[] connected = { false };
        int[] cursor = { 0 };
        try {
            transport.addListener(
                new RTCTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        Map<String, Object> message = new LinkedHashMap<>();
                        message.put("to", "jvm");
                        message.put("type", "ice");
                        message.put("candidate", candidate.getCandidate());
                        message.put("sdpMid", candidate.getSdpMid());
                        postJson(platform, signalBase + "/send", message)
                            .catchException(error -> listenerError.compareAndSet(null, error));
                    }

                    @Override
                    public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer msg) {
                        if (!msg.isDirect()) {
                            listenerError.compareAndSet(
                                null,
                                new AssertionError("TeaVM RTC delivered a non-direct ByteBuffer")
                            );
                            return;
                        }
                        inbox.add(bytes(msg));
                    }

                    @Override
                    public void onRTCChannelError(RTCDataChannel channel, Throwable error) {
                        listenerError.compareAndSet(null, error);
                    }

                    @Override
                    public void onRTCChannelReady(RTCDataChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(RTCDataChannel channel) {}

                    @Override
                    public void onRTCChannelClosed(RTCDataChannel channel) {}

                    @Override
                    public void onRTCDisconnected(String reason) {
                        if (!"closed".equals(reason)) {
                            listenerError.compareAndSet(null, new IllegalStateException("RTC disconnected: " + reason));
                        }
                    }

                    @Override
                    public void onRTCConnected() {
                        connected[0] = true;
                    }
                }
            );

            String offer = null;
            long offerDeadline = System.currentTimeMillis() + 20_000;
            while (offer == null && System.currentTimeMillis() < offerDeadline) {
                for (Map<String, Object> message : poll(platform, signalBase, cursor)) {
                    String type = String.valueOf(message.get("type"));
                    if ("offer".equals(type)) {
                        offer = String.valueOf(message.get("sdp"));
                    } else if ("ice".equals(type)) {
                        addIce(transport, message);
                    }
                }
                if (offer == null) {
                    sleep(platform, 25);
                }
            }
            assertNotNull("Timed out waiting for JVM RTC offer", offer);

            String answer = transport.connect(offer).await();
            Map<String, Object> answerMessage = new LinkedHashMap<>();
            answerMessage.put("to", "jvm");
            answerMessage.put("type", "answer");
            answerMessage.put("sdp", answer);
            postJson(platform, signalBase + "/send", answerMessage).await();

            long connectedDeadline = System.currentTimeMillis() + 20_000;
            while (!connected[0] && System.currentTimeMillis() < connectedDeadline) {
                for (Map<String, Object> message : poll(platform, signalBase, cursor)) {
                    if ("ice".equals(String.valueOf(message.get("type")))) {
                        addIce(transport, message);
                    }
                }
                assertNoError(listenerError, "RTC connection");
                sleep(platform, 25);
            }
            assertTrue("Timed out waiting for RTC connected state", connected[0]);

            RTCDataChannel channel = null;
            long channelDeadline = System.currentTimeMillis() + 15_000;
            while (channel == null && System.currentTimeMillis() < channelDeadline) {
                channel = transport.getDefaultChannel();
                if (channel == null) {
                    sleep(platform, 20);
                }
            }
            assertNotNull("Timed out waiting for incoming default channel", channel);
            channel.ready().await();

            sendSignal(platform, signalBase, "meta", Map.of("name", "remoteLabel", "value", channel.getName()));
            sendSignal(platform, signalBase, "meta", Map.of("name", "protocol", "value", channel.getProtocol()));
            sendSignal(platform, signalBase, "browser-ready", Map.of());

            for (int i = 0; i < STRESS_MESSAGES; i++) {
                ByteBuffer payload = direct(platform, utf8("browser-seq:" + i));
                channel.write(payload).await();
                assertEquals(0, payload.position());
            }

            for (int i = 0; i < STRESS_MESSAGES; i++) {
                byte[] message = awaitBinary(platform, inbox, listenerError, "JVM RTC message " + i);
                assertEquals("jvm-seq:" + i, new String(message, StandardCharsets.UTF_8));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("compiledBackend", platform.getPlatformName());
            result.put("directInboundBuffers", true);
            result.put("browserToJvmStressCount", STRESS_MESSAGES);
            result.put("jvmToBrowserStressCount", STRESS_MESSAGES);
            result.put("label", channel.getName());
            result.put("protocol", channel.getProtocol());
            postJson(platform, signalBase + "/result/wasm", result).await();
            channel.close().await();
        } catch (Throwable failure) {
            sendFailureSignal(platform, signalBase, failure);
            postFailure(platform, signalBase, failure);
            throw failure;
        } finally {
            transport.close();
        }
    }

    private static TeaVMPlatform installPlatform() {
        TeaVMPlatform platform = new TeaVMPlatform();
        NGEPlatform.set(platform);
        return platform;
    }

    private static void requireWasm() {
        assertTrue("External interoperability harness must load the Wasm GC artifact", PlatformDetector.isWebAssemblyGC());
    }

    private static void verifyPersistentStore(TeaVMPlatform platform) throws Exception {
        VStore store = platform.getDataStore("nge-wasm-interop", "parity");
        String path = "roundtrip.bin";
        byte[] value = utf8("persistent-wasm-value");
        OutputStream output = store.write(path).await();
        output.write(value);
        output.close();
        assertTrue(store.exists(path).await());
        assertEquals("persistent-wasm-value", new String(store.readFully(path).await(), StandardCharsets.UTF_8));
        assertTrue(store.listAll().await().contains(path));
        store.delete(path).await();
        assertFalse(store.exists(path).await());
    }

    private static boolean awaitCanCall(TeaVMPlatform platform, String function) throws Exception {
        return platform.<Boolean>wrapPromise((resolve, reject) -> platform.canCallFunction(function, resolve)).await();
    }

    private static Object awaitCall(TeaVMPlatform platform, String function, Object[] args) throws Exception {
        return platform
            .<Object>wrapPromise((resolve, reject) -> platform.callFunction(function, args, resolve, reject))
            .await();
    }

    private static AsyncTask<NGEHttpResponse> postJson(TeaVMPlatform platform, String url, Map<String, ?> value) {
        return platform.httpRequest(
            "POST",
            url,
            utf8(platform.toJSON(value)),
            TIMEOUT,
            Map.of("Content-Type", "application/json")
        );
    }

    private static void postFailure(TeaVMPlatform platform, String signalBase, Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", stackTrace(failure));
        try {
            postJson(platform, signalBase + "/result/wasm", result).await();
        } catch (Exception ignored) {}
    }

    private static void sendFailureSignal(TeaVMPlatform platform, String signalBase, Throwable failure) {
        try {
            sendSignal(platform, signalBase, "browser-failed", Map.of("error", stackTrace(failure)));
        } catch (Exception ignored) {}
    }

    private static void sendSignal(TeaVMPlatform platform, String signalBase, String type, Map<String, Object> values)
        throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("to", "jvm");
        message.put("type", type);
        message.putAll(values);
        postJson(platform, signalBase + "/send", message).await();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> poll(TeaVMPlatform platform, String signalBase, int[] cursor) throws Exception {
        NGEHttpResponse response = platform
            .httpRequest("GET", signalBase + "/poll?to=browser&after=" + cursor[0], (byte[]) null, TIMEOUT, Map.of())
            .await();
        if (!response.status()) {
            throw new IllegalStateException("RTC signal poll failed with HTTP " + response.statusCode());
        }
        Map<String, Object> parsed = platform.fromJSON(response.bodyAsString(), Map.class);
        Object cursorValue = parsed.get("cursor");
        if (cursorValue instanceof Number) {
            cursor[0] = ((Number) cursorValue).intValue();
        }
        Object messages = parsed.get("messages");
        return messages instanceof List ? (List<Map<String, Object>>) messages : List.of();
    }

    private static void addIce(RTCTransport transport, Map<String, Object> message) {
        Object mid = message.get("sdpMid");
        transport.addRemoteIceCandidates(
            List.of(
                new RTCTransportIceCandidate(String.valueOf(message.get("candidate")), mid == null ? null : String.valueOf(mid))
            )
        );
    }

    private static String awaitText(TeaVMPlatform platform, List<String> inbox, AtomicReference<Throwable> error, String phase)
        throws Exception {
        waitUntil(platform, () -> !inbox.isEmpty(), error, phase);
        return inbox.remove(0);
    }

    private static byte[] awaitBinary(
        TeaVMPlatform platform,
        List<byte[]> inbox,
        AtomicReference<Throwable> error,
        String phase
    ) throws Exception {
        waitUntil(platform, () -> !inbox.isEmpty(), error, phase);
        return inbox.remove(0);
    }

    private static void waitUntil(TeaVMPlatform platform, Condition condition, AtomicReference<Throwable> error, String phase)
        throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.test() && System.currentTimeMillis() < deadline) {
            assertNoError(error, phase);
            sleep(platform, 10);
        }
        assertNoError(error, phase);
        assertTrue("Timed out waiting for " + phase, condition.test());
    }

    private static void assertNoError(AtomicReference<Throwable> error, String phase) {
        Throwable failure = error.get();
        if (failure != null) {
            throw new IllegalStateException("Listener failed during " + phase, failure);
        }
    }

    private static void sleep(TeaVMPlatform platform, int millis) throws Exception {
        TeaVMBinds.delayPromise(millis).await();
    }

    private static ByteBuffer direct(TeaVMPlatform platform, byte[] value) {
        ByteBuffer buffer = platform.getNativeAllocator().malloc(Math.max(1, value.length));
        assertTrue(buffer.isDirect());
        buffer.limit(value.length);
        buffer.put(value);
        buffer.flip();
        return buffer;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.slice();
        byte[] value = new byte[source.remaining()];
        source.get(value);
        return value;
    }

    private static byte[] readFully(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        for (int read; (read = input.read(buffer)) >= 0;) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte current : value) {
            int unsigned = current & 0xff;
            if (unsigned < 16) {
                out.append('0');
            }
            out.append(Integer.toHexString(unsigned));
        }
        return out.toString();
    }

    private static String stackTrace(Throwable failure) {
        StringBuilder out = new StringBuilder(String.valueOf(failure));
        for (StackTraceElement element : failure.getStackTrace()) {
            out.append("\n  at ").append(element);
        }
        return out.toString();
    }

    @JSBody(
        script = "globalThis.__ngeOpenedUrl = '';" +
        "globalThis.ngeOpenURL = function(url) { globalThis.__ngeOpenedUrl = String(url); };" +
        "globalThis.ngeClipboard = {" +
        " value: ''," +
        " readText: function() { return Promise.resolve(this.value); }," +
        " writeText: function(value) { this.value = String(value); return Promise.resolve(); }" +
        "};" +
        "globalThis.ngeFunctionExecutor = {" +
        " canExecute: function(name) { return name === 'interop.sum'; }," +
        " execute: function(name, args) {" +
        "  if (name !== 'interop.sum') throw new Error('Unknown host function: ' + name);" +
        "  return Number(args[0]) + Number(args[1]);" +
        " }" +
        "};"
    )
    private static native void installHostBindings();

    @JSBody(script = "return globalThis.__ngeOpenedUrl || '';")
    private static native String openedUrl();

    private static void registerForAutomaticCleanup(TeaVMPlatform platform, int[] cleanupCount) {
        Object target = new Object();
        platform.registerFinalizer(target, () -> cleanupCount[0]++);
    }

    @JSBody(
        script = "return typeof globalThis.__ngeFinalizationEntryCount === 'function' " +
        "? globalThis.__ngeFinalizationEntryCount() : -1;"
    )
    private static native int trackedFinalizerRegistrationCount();

    @JSBody(
        params = { "index" },
        script = "return typeof globalThis.__ngeTriggerFinalizer === 'function' && " +
        "globalThis.__ngeTriggerFinalizer(index);"
    )
    private static native boolean triggerFinalizerRegistration(int index);

    @JSBody(params = { "name" }, script = "return new URL(globalThis.location.href).searchParams.get(name);")
    private static native String queryParameter(String name);

    private interface Condition {
        boolean test();
    }
}
