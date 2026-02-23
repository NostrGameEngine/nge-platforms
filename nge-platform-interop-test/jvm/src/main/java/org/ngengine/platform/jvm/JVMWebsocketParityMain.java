package org.ngengine.platform.jvm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class JVMWebsocketParityMain {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JVMWebsocketParityMain <signalBaseUrl> <wsUrl>");
            System.exit(2);
            return;
        }
        String signalBase = null;
        String wsUrl = null;
        for (String arg : args) {
            if ((arg.startsWith("http://") || arg.startsWith("https://")) && arg.contains("/signal")) {
                signalBase = arg;
            } else if (arg.startsWith("ws://") || arg.startsWith("wss://")) {
                wsUrl = arg;
            }
        }
        if (signalBase == null || wsUrl == null) {
            System.err.println("Raw args: " + java.util.Arrays.toString(args));
            throw new IllegalArgumentException("Could not parse signalBase/wsUrl args");
        }

        JsonObject out = new JsonObject();
        try {
            JVMAsyncPlatform platform = new JVMAsyncPlatform();
            fillSnapshot(out, platform, wsUrl);
            out.addProperty("ok", true);
        } catch (Throwable t) {
            out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("error", stackTraceString(t));
        }
        postJson(signalBase + "/result/jvm", out);
        if (!out.get("ok").getAsBoolean()) System.exit(1);
    }

    private static void fillSnapshot(JsonObject out, JVMAsyncPlatform platform, String wsUrl) throws Exception {
        JsonObject serverPhase = runServerClosePhase(platform.newTransport(), wsUrl);
        JsonObject clientPhase = runClientClosePhase(platform.newTransport(), wsUrl);
        out.add("serverClosePhase", serverPhase);
        out.add("clientClosePhase", clientPhase);
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
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for " + phase);
        }
    }

    private static void postJson(String url, JsonObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = GSON.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            String errBody = "";
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) errBody = new String(es.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("POST failed: " + code + " " + errBody);
        }
        conn.disconnect();
    }

    private static String stackTraceString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
