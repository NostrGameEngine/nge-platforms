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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

@RunWith(AndroidJUnit4.class)
public class AndroidWebsocketParityInstrumentedTest {
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

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
    public void websocketParitySnapshot() throws Exception {
        String signalBase = InstrumentationRegistry.getArguments().getString("signalBase");
        String wsUrl = InstrumentationRegistry.getArguments().getString("wsUrl");
        assertNotNull("Missing instrumentation arg 'signalBase'", signalBase);
        assertNotNull("Missing instrumentation arg 'wsUrl'", wsUrl);

        OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

        JsonObject out = new JsonObject();
        try {
            AndroidThreadedPlatform platform = (AndroidThreadedPlatform) NGEPlatform.get();
            fillSnapshot(out, platform, wsUrl);
            out.addProperty("ok", true);
        } catch (Throwable t) {
            out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("error", stackTraceString(t));
        }
        postJson(http, signalBase + "/result/android", out);
        if (!out.get("ok").getAsBoolean()) throw new AssertionError(out.get("error").getAsString());
    }

    private static void fillSnapshot(JsonObject out, AndroidThreadedPlatform platform, String wsUrl) throws Exception {
        out.add("serverClosePhase", runServerClosePhase(platform.newTransport(), wsUrl));
        out.add("clientClosePhase", runClientClosePhase(platform.newTransport(), wsUrl));
    }

    private static JsonObject runServerClosePhase(WebsocketTransport ws, String wsUrl) throws Exception {
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        AtomicReference<String> serverCloseReason = new AtomicReference<>("");
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger openCount = new AtomicInteger(0);
        AtomicInteger clientCloseCount = new AtomicInteger(0);

        ws.addListener(new WebsocketTransportListener() {
            @Override public void onConnectionClosedByServer(String reason) { serverCloseReason.set(reason == null ? "" : reason); serverCloseLatch.countDown(); }
            @Override public void onConnectionOpen() { openCount.incrementAndGet(); openLatch.countDown(); }
            @Override public void onConnectionMessage(String msg) { inbox.offer(msg); }
            @Override public void onConnectionClosedByClient(String reason) { clientCloseCount.incrementAndGet(); }
            @Override public void onConnectionError(Throwable e) { error.compareAndSet(null, e); }
        });

        ws.connect(wsUrl).await();
        await(openLatch, 10, "websocket open");
        boolean connectedAfterOpen = ws.isConnected();

        String welcome = pollString(inbox, error, 10, "welcome");
        ws.send("echo:server-phase").await();
        String echo = pollString(inbox, error, 10, "echo");
        ws.send("close-by-server").await();
        await(serverCloseLatch, 10, "server close");
        Thread.sleep(50);

        JsonObject out = new JsonObject();
        out.addProperty("openCount", openCount.get());
        out.addProperty("connectedAfterOpen", connectedAfterOpen);
        out.addProperty("welcome", welcome);
        out.addProperty("echo", echo);
        out.addProperty("serverCloseReason", serverCloseReason.get());
        out.addProperty("clientCloseCount", clientCloseCount.get());
        out.addProperty("connectedAfterServerClose", ws.isConnected());
        out.addProperty("error", error.get() == null ? "" : String.valueOf(error.get()));
        return out;
    }

    private static JsonObject runClientClosePhase(WebsocketTransport ws, String wsUrl) throws Exception {
        CountDownLatch openLatch = new CountDownLatch(1);
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger openCount = new AtomicInteger(0);
        AtomicInteger clientCloseCount = new AtomicInteger(0);
        AtomicInteger serverCloseCount = new AtomicInteger(0);
        AtomicReference<String> clientCloseReason = new AtomicReference<>("");

        ws.addListener(new WebsocketTransportListener() {
            @Override public void onConnectionClosedByServer(String reason) { serverCloseCount.incrementAndGet(); }
            @Override public void onConnectionOpen() { openCount.incrementAndGet(); openLatch.countDown(); }
            @Override public void onConnectionMessage(String msg) { inbox.offer(msg); }
            @Override public void onConnectionClosedByClient(String reason) { clientCloseCount.incrementAndGet(); clientCloseReason.set(reason == null ? "" : reason); }
            @Override public void onConnectionError(Throwable e) { error.compareAndSet(null, e); }
        });

        ws.connect(wsUrl).await();
        await(openLatch, 10, "client phase open");
        boolean connectedAfterOpen = ws.isConnected();

        String welcome = pollString(inbox, error, 10, "client phase welcome");
        ws.send("echo:client-phase").await();
        String echo = pollString(inbox, error, 10, "client phase echo");
        ws.close("client-close").await();
        Thread.sleep(100);

        JsonObject out = new JsonObject();
        out.addProperty("openCount", openCount.get());
        out.addProperty("connectedAfterOpen", connectedAfterOpen);
        out.addProperty("welcome", welcome);
        out.addProperty("echo", echo);
        out.addProperty("clientCloseCount", clientCloseCount.get());
        out.addProperty("clientCloseReason", clientCloseReason.get());
        out.addProperty("serverCloseCount", serverCloseCount.get());
        out.addProperty("connectedAfterClientClose", ws.isConnected());
        out.addProperty("error", error.get() == null ? "" : String.valueOf(error.get()));
        return out;
    }

    private static String pollString(LinkedBlockingQueue<String> inbox, AtomicReference<Throwable> error, long timeoutSec, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        while (System.nanoTime() < deadline) {
            Throwable t = error.get();
            if (t != null) throw new IllegalStateException("WebSocket listener error while " + phase, t);
            String msg = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null) return msg;
        }
        throw new IllegalStateException("Timed out waiting for " + phase);
    }

    private static void await(CountDownLatch latch, long timeoutSec, String phase) throws Exception {
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for " + phase);
    }

    private static void postJson(OkHttpClient client, String url, JsonObject body) throws IOException {
        Request request = new Request.Builder().url(url).post(RequestBody.create(GSON.toJson(body), JSON)).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("POST " + url + " failed: " + response.code());
        }
    }

    private static String stackTraceString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
