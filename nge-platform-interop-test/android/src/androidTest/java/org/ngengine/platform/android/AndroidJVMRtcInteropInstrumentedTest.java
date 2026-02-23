package org.ngengine.platform.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

@RunWith(AndroidJUnit4.class)
public class AndroidJVMRtcInteropInstrumentedTest {
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final List<String> STUN_SERVERS = List.of("stun.l.google.com:19302");

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
    public void androidAndJvmCanExchangeMessagesOverRtc() throws Exception {
        String signalBase = InstrumentationRegistry.getArguments().getString("signalBase");
        assertNotNull("Missing instrumentation arg 'signalBase'", signalBase);

        AndroidThreadedPlatform platform;
        try {
            platform = (AndroidThreadedPlatform) NGEPlatform.get();
            new AndroidRTCTransport();
        } catch (Throwable t) {
            Assume.assumeNoException("Skipping Android/JVM RTC interop test: runtime unavailable", t);
            return;
        }

        OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean stopPolling = new AtomicBoolean(false);
        AtomicReference<String> answerSdp = new AtomicReference<>();
        CountDownLatch answerLatch = new CountDownLatch(1);
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch channelReadyLatch = new CountDownLatch(1);
        CountDownLatch jvmReadyLatch = new CountDownLatch(1);
        AtomicReference<AsyncTask<RTCDataChannel>> responderChannelTaskRef = new AtomicReference<>();
        BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> listenerError = new AtomicReference<>();
        Map<String, String> meta = new ConcurrentHashMap<>();

        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            Duration.ofSeconds(120)
        );
        RTCTransport transport = platform.newRTCTransport(settings, "android-jvm-interop", STUN_SERVERS);

        Thread poller = null;
        try {
            hookAnswerCapture((AndroidRTCTransport) transport, answerSdp, answerLatch);
            transport.addListener(new RTCTransportListener() {
                @Override
                public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("to", "jvm");
                    msg.addProperty("type", "ice");
                    msg.addProperty("candidate", candidate.getCandidate());
                    msg.addProperty("sdpMid", candidate.getSdpMid());
                    try {
                        postJson(http, signalBase + "/send", msg);
                    } catch (Throwable e) {
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
                public void onRTCChannelReady(RTCDataChannel channel) {
                    channelReadyLatch.countDown();
                }

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
                        JsonObject poll = getJson(http, signalBase + "/poll?to=android&after=" + cursor.get());
                        if (poll.has("cursor")) {
                            cursor.set(poll.get("cursor").getAsInt());
                        }
                        JsonArray messages = poll.has("messages") ? poll.getAsJsonArray("messages") : new JsonArray();
                        for (JsonElement el : messages) {
                            JsonObject msg = el.getAsJsonObject();
                            String type = msg.get("type").getAsString();
                            if ("offer".equals(type)) {
                                responderChannelTaskRef.compareAndSet(null, transport.connect(msg.get("sdp").getAsString()));
                            } else if ("ice".equals(type)) {
                                String candidate = msg.get("candidate").getAsString();
                                String sdpMid = msg.has("sdpMid") && !msg.get("sdpMid").isJsonNull()
                                    ? msg.get("sdpMid").getAsString()
                                    : null;
                                try {
                                    transport.addRemoteIceCandidates(List.of(new RTCTransportIceCandidate(candidate, sdpMid)));
                                } catch (Throwable ignored) {
                                    // libdatachannel may reject some browser/libdatachannel candidate variants; keep polling.
                                }
                            } else if ("jvm-ready".equals(type)) {
                                jvmReadyLatch.countDown();
                            } else if ("meta".equals(type)) {
                                if (msg.has("name")) {
                                    meta.put(msg.get("name").getAsString(), msg.get("value").getAsString());
                                }
                            } else if ("jvm-failed".equals(type)) {
                                listenerError.compareAndSet(
                                    null,
                                    new IllegalStateException("JVM harness failed: " + msg.get("error").getAsString())
                                );
                            }
                        }
                        Thread.sleep(50);
                    } catch (Throwable t) {
                        listenerError.compareAndSet(null, t);
                        return;
                    }
                }
            }, "android-jvm-interop-poller");
            poller.setDaemon(true);
            poller.start();

            awaitNoListenerError(listenerError, "waiting for offer");
            if (!answerLatch.await(120, TimeUnit.SECONDS)) {
                awaitNoListenerError(listenerError, "waiting for SDP answer");
                fail("Responder did not produce SDP answer");
            }
            String answer = answerSdp.get();
            assertNotNull("Responder answer SDP missing", answer);
            JsonObject answerMsg = new JsonObject();
            answerMsg.addProperty("to", "jvm");
            answerMsg.addProperty("type", "answer");
            answerMsg.addProperty("sdp", answer);
            postJson(http, signalBase + "/send", answerMsg);

            awaitNoListenerError(listenerError, "waiting for RTC connected");
            if (!connectedLatch.await(60, TimeUnit.SECONDS)) {
                awaitNoListenerError(listenerError, "waiting for RTC connected");
                fail("RTC connected event missing");
            }

            AsyncTask<RTCDataChannel> responderChannelTask = responderChannelTaskRef.get();
            assertNotNull("Responder channel task not created", responderChannelTask);
            RTCDataChannel channel = responderChannelTask.await();
            channel.ready().await();
            if (!channelReadyLatch.await(10, TimeUnit.SECONDS)) {
                awaitNoListenerError(listenerError, "waiting for channel ready");
                fail("Channel ready callback missing");
            }

            JsonObject metaMsg = new JsonObject();
            metaMsg.addProperty("to", "jvm");
            metaMsg.addProperty("type", "meta");
            metaMsg.addProperty("name", "androidLabel");
            metaMsg.addProperty("value", channel.getName());
            postJson(http, signalBase + "/send", metaMsg);

            JsonObject readyMsg = new JsonObject();
            readyMsg.addProperty("to", "jvm");
            readyMsg.addProperty("type", "android-ready");
            postJson(http, signalBase + "/send", readyMsg);

            awaitNoListenerError(listenerError, "waiting for jvm-ready");
            if (!jvmReadyLatch.await(10, TimeUnit.SECONDS)) {
                awaitNoListenerError(listenerError, "waiting for jvm-ready");
                fail("Did not receive jvm-ready signal");
            }

            byte[] ping = pollMessage(inbox, listenerError, 60, TimeUnit.SECONDS);
            String pingText = new String(ping, StandardCharsets.UTF_8);
            assertTrue("Unexpected payload from JVM: " + pingText, "ping-from-jvm".equals(pingText));
            channel.write(ByteBuffer.wrap("pong-from-android".getBytes(StandardCharsets.UTF_8))).await();

            channel.close().await();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("reply", "pong-from-android");
            result.addProperty("jvmLabel", meta.getOrDefault("jvmLabel", ""));
            postJson(http, signalBase + "/result/android", result);
        } catch (Throwable t) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", stackTraceString(t));
            try {
                postJson(http, signalBase + "/result/android", result);
            } catch (Throwable ignored) {}
            throw t;
        } finally {
            stopPolling.set(true);
            if (poller != null) {
                poller.join(500);
            }
            try {
                transport.close();
            } catch (Exception ignored) {}
        }
    }

    private static RTCDataChannel awaitDefaultChannel(RTCTransport transport, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            RTCDataChannel ch = transport.getDataChannel(RTCTransport.DEFAULT_CHANNEL);
            if (ch != null) return ch;
            Thread.sleep(20);
        }
        fail("Timed out waiting for default RTC data channel");
        return null;
    }

    private static byte[] pollMessage(
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
        throw new IllegalStateException("Timed out waiting for JVM message");
    }

    private static void awaitNoListenerError(AtomicReference<Throwable> listenerError, String phase) {
        Throwable err = listenerError.get();
        if (err != null) throw new IllegalStateException("Listener error while " + phase, err);
    }

    private static void hookAnswerCapture(AndroidRTCTransport transport, AtomicReference<String> answerRef, CountDownLatch latch)
        throws Exception {
        Field connField = AndroidRTCTransport.class.getDeclaredField("conn");
        connField.setAccessible(true);
        PeerConnection conn = (PeerConnection) connField.get(transport);
        conn.onLocalDescription.register((peer, sdp, type) -> {
            if (type == SessionDescriptionType.ANSWER && answerRef.compareAndSet(null, sdp)) {
                latch.countDown();
            }
        });
    }

    private static JsonObject getJson(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GET " + url + " failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return GSON.fromJson(body, JsonObject.class);
        }
    }

    private static void postJson(OkHttpClient client, String url, JsonObject body) throws IOException {
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(GSON.toJson(body), JSON))
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST " + url + " failed: " + response.code());
            }
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
