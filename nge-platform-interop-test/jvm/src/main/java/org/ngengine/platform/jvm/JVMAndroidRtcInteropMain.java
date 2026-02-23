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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

public class JVMAndroidRtcInteropMain {

    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger(JVMAndroidRtcInteropMain.class.getName());
    private static final List<String> STUN_SERVERS = List.of("stun.l.google.com:19302");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: JVMAndroidRtcInteropMain <signalBaseUrl>");
            System.exit(2);
            return;
        }
        String signalBase = args[0];

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean stopPolling = new AtomicBoolean(false);
        AtomicReference<String> answerSdp = new AtomicReference<>();
        CountDownLatch answerLatch = new CountDownLatch(1);
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch androidReadyLatch = new CountDownLatch(1);
        BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> listenerError = new AtomicReference<>();
        Map<String, String> meta = new ConcurrentHashMap<>();

        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(120)
        );
        RTCTransport transport = platform.newRTCTransport(settings, "jvm-android-interop", STUN_SERVERS);

        Thread poller = null;
        try {
            transport.addListener(
                new RTCTransportListener() {
                    @Override
                    public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("to", "android");
                        msg.addProperty("type", "ice");
                        msg.addProperty("candidate", candidate.getCandidate());
                        msg.addProperty("sdpMid", candidate.getSdpMid());
                        try {
                            postJson(http, signalBase + "/send", msg);
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
                    public void onRTCChannelClosed(RTCDataChannel channel) {}

                    @Override
                    public void onRTCDisconnected(String reason) {
                        listenerError.compareAndSet(null, new IllegalStateException("RTC disconnected: " + reason));
                    }

                    @Override
                    public void onRTCConnected() {
                        connectedLatch.countDown();
                    }
                }
            );

            poller =
                new Thread(
                    () -> {
                        while (!stopPolling.get()) {
                            try {
                                JsonObject poll = getJson(http, signalBase + "/poll?to=jvm&after=" + cursor.get());
                                if (poll.has("cursor")) cursor.set(poll.get("cursor").getAsInt());
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
                                            transport.addRemoteIceCandidates(
                                                List.of(new RTCTransportIceCandidate(candidate, sdpMid))
                                            );
                                        } catch (Throwable addErr) {
                                            LOG.log(Level.FINE, "Ignoring remote ICE candidate add failure", addErr);
                                        }
                                    } else if ("android-ready".equals(type)) {
                                        androidReadyLatch.countDown();
                                    } else if ("meta".equals(type)) {
                                        if (msg.has("name")) meta.put(
                                            msg.get("name").getAsString(),
                                            msg.get("value").getAsString()
                                        );
                                    } else if ("android-failed".equals(type)) {
                                        listenerError.compareAndSet(
                                            null,
                                            new IllegalStateException(
                                                "Android harness failed: " + msg.get("error").getAsString()
                                            )
                                        );
                                    }
                                }
                                Thread.sleep(50);
                            } catch (Throwable t) {
                                listenerError.compareAndSet(null, t);
                                return;
                            }
                        }
                    },
                    "jvm-android-interop-poller"
                );
            poller.setDaemon(true);
            poller.start();

            String offer = transport
                .createChannel(RTCTransport.DEFAULT_CHANNEL, "jvm-android-proto", true, true, -1, null)
                .await();
            JsonObject offerMsg = new JsonObject();
            offerMsg.addProperty("to", "android");
            offerMsg.addProperty("type", "offer");
            offerMsg.addProperty("sdp", offer);
            postJson(http, signalBase + "/send", offerMsg);

            awaitNoListenerError(listenerError, "waiting for android answer");
            if (!answerLatch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Android SDP answer");
            }
            transport.connect(answerSdp.get()).await();

            awaitNoListenerError(listenerError, "waiting for RTC connected");
            if (!connectedLatch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for RTC connected event");
            }

            RTCDataChannel channel = awaitDefaultChannel(transport, 60, TimeUnit.SECONDS);
            channel.ready().await();

            JsonObject metaMsg = new JsonObject();
            metaMsg.addProperty("to", "android");
            metaMsg.addProperty("type", "meta");
            metaMsg.addProperty("name", "jvmLabel");
            metaMsg.addProperty("value", channel.getName());
            postJson(http, signalBase + "/send", metaMsg);

            JsonObject readyMsg = new JsonObject();
            readyMsg.addProperty("to", "android");
            readyMsg.addProperty("type", "jvm-ready");
            postJson(http, signalBase + "/send", readyMsg);

            if (!androidReadyLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for android-ready signal");
            }

            byte[] ping = "ping-from-jvm".getBytes(StandardCharsets.UTF_8);
            channel.write(ByteBuffer.wrap(ping)).await();
            byte[] response = waitForMessage(inbox, listenerError, 60, TimeUnit.SECONDS);
            String responseText = new String(response, StandardCharsets.UTF_8);
            if (!"pong-from-android".equals(responseText)) {
                throw new IllegalStateException("Unexpected android reply: " + responseText);
            }

            channel.close().await();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("reply", responseText);
            result.addProperty("androidLabel", meta.getOrDefault("androidLabel", ""));
            postJson(http, signalBase + "/result/jvm", result);
        } catch (Throwable t) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", stackTraceString(t));
            try {
                postJson(http, signalBase + "/result/jvm", result);
            } catch (Exception ignored) {}
            throw t;
        } finally {
            stopPolling.set(true);
            if (poller != null) poller.join(500);
            try {
                transport.close();
            } catch (Exception ignored) {}
        }
    }

    private static RTCDataChannel awaitDefaultChannel(RTCTransport transport, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            RTCDataChannel channel = transport.getDataChannel(RTCTransport.DEFAULT_CHANNEL);
            if (channel != null) return channel;
            Thread.sleep(20);
        }
        throw new IllegalStateException("Timed out waiting for default RTC data channel");
    }

    private static byte[] waitForMessage(
        BlockingQueue<byte[]> inbox,
        AtomicReference<Throwable> listenerError,
        long timeout,
        TimeUnit unit
    ) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Throwable err = listenerError.get();
            if (err != null) throw new IllegalStateException("Listener error", err);
            byte[] msg = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null) return msg;
        }
        throw new IllegalStateException("Timed out waiting for Android message");
    }

    private static void awaitNoListenerError(AtomicReference<Throwable> listenerError, String phase) {
        Throwable err = listenerError.get();
        if (err != null) {
            throw new IllegalStateException("Listener error while " + phase, err);
        }
    }

    private static JsonObject getJson(HttpClient http, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("GET " + url + " failed: " + res.statusCode() + " " + res.body());
        }
        return GSON.fromJson(res.body(), JsonObject.class);
    }

    private static void postJson(HttpClient http, String url, JsonObject payload) throws Exception {
        HttpRequest req = HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST " + url + " failed: " + res.statusCode() + " " + res.body());
        }
    }

    private static String stackTraceString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t).append('\n');
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("  at ").append(el).append('\n');
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("Caused by: ").append(stackTraceString(cause));
        }
        return sb.toString();
    }
}
