package org.ngengine.platform.jvm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class JVMAsyncParityMain {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: JVMAsyncParityMain <signalBaseUrl>");
            System.exit(2);
            return;
        }
        String signalBase = args[0];
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JsonObject result = new JsonObject();
        try {
            fillSnapshot(result, new JVMAsyncPlatform());
            result.addProperty("ok", true);
        } catch (Throwable t) {
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", t.toString());
        }
        postJson(http, signalBase + "/result/jvm", result);
        if (!result.get("ok").getAsBoolean()) System.exit(1);
    }

    private static void fillSnapshot(JsonObject out, NGEPlatform p) throws Exception {
        AsyncExecutor exec = p.newAsyncExecutor("parity");
        try {
            out.addProperty("executorRun", exec.run(() -> 7).await());
            out.addProperty("executorRunLater", exec.runLater(() -> 9, 10, TimeUnit.MILLISECONDS).await());
            out.addProperty("wrapResolved", p.<Integer>wrapPromise((res, rej) -> res.accept(5)).await());

            AtomicBoolean caught = new AtomicBoolean(false);
            AsyncTask<Integer> failed = p.wrapPromise((res, rej) -> rej.accept(new IllegalStateException("boom")));
            failed.catchException(e -> caught.set(true));
            boolean wrapRejectedAwaitFailed;
            try {
                failed.await();
                wrapRejectedAwaitFailed = false;
            } catch (Throwable t) {
                wrapRejectedAwaitFailed = true;
            }
            out.addProperty("wrapRejectedCaught", caught.get());
            out.addProperty("wrapRejectedAwaitFailed", wrapRejectedAwaitFailed);

            out.addProperty("promisifyResolved", p.<String>promisify((res, rej) -> res.accept("exec-ok"), exec).await());
            out.addProperty("thenChain", AsyncTask.completed(3).then(v -> v + 4).await());
            out.addProperty("composeChain", AsyncTask.completed(3).compose(v -> AsyncTask.completed(v * 2)).await());

            List<AsyncTask<String>> allTasks = List.of(
                exec.runLater(() -> "a", 30, TimeUnit.MILLISECONDS),
                exec.runLater(() -> "b", 0, TimeUnit.MILLISECONDS),
                exec.runLater(() -> "c", 10, TimeUnit.MILLISECONDS)
            );
            out.addProperty("awaitAllOrder", String.join(",", p.awaitAll(allTasks).await()));

            out.addProperty(
                "awaitAnyFirstSuccess",
                p.awaitAny(List.of(AsyncTask.failed(new RuntimeException("x")), exec.runLater(() -> 42, 10, TimeUnit.MILLISECONDS))).await()
            );
            out.addProperty(
                "awaitAnyFilter",
                p.awaitAny(
                    List.of(exec.runLater(() -> 5, 0, TimeUnit.MILLISECONDS), exec.runLater(() -> 12, 10, TimeUnit.MILLISECONDS)),
                    v -> v > 10
                ).await()
            );

            out.addProperty("awaitAllFail", fails(() -> p.awaitAll(List.of(AsyncTask.completed(1), AsyncTask.failed(new RuntimeException("x")))).await()));
            out.addProperty(
                "awaitAnyAllFail",
                fails(() -> p.awaitAny(List.of(AsyncTask.failed(new RuntimeException("a")), AsyncTask.failed(new RuntimeException("b")))).await())
            );
            out.addProperty(
                "awaitAnyNoMatch",
                fails(() -> p.awaitAny(List.of(AsyncTask.completed(1), AsyncTask.completed(2)), v -> v > 10).await())
            );

            List<AsyncTask<Integer>> settled = p.awaitAllSettled(
                List.of(AsyncTask.completed(1), AsyncTask.failed(new RuntimeException("z")))
            ).await();
            out.addProperty("awaitAllSettledCount", settled.size());
            out.addProperty(
                "awaitAllSettledPattern",
                (settled.get(0).isSuccess() ? "S" : "F") + (settled.get(1).isSuccess() ? "S" : "F")
            );

            Queue<String> q = p.newConcurrentQueue(String.class);
            q.add("q1");
            q.add("q2");
            out.addProperty("queueOrder", q.poll() + "," + q.poll());
        } finally {
            try { exec.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean fails(ThrowingRunnable r) {
        try { r.run(); return false; } catch (Throwable t) { return true; }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private static void postJson(HttpClient http, String url, JsonObject payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("POST failed: " + res.statusCode() + " " + res.body());
    }
}
