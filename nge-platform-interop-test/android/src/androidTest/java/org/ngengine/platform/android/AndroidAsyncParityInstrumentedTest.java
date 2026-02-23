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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

@RunWith(AndroidJUnit4.class)
public class AndroidAsyncParityInstrumentedTest {
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
    public void androidAsyncSemanticsParitySnapshot() throws Exception {
        String signalBase = InstrumentationRegistry.getArguments().getString("signalBase");
        assertNotNull("Missing instrumentation arg 'signalBase'", signalBase);
        OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

        JsonObject result = new JsonObject();
        try {
            fillSnapshot(result, (AndroidThreadedPlatform) NGEPlatform.get());
            result.addProperty("ok", true);
        } catch (Throwable t) {
            result = new JsonObject();
            result.addProperty("ok", false);
            if (t instanceof StepFailureException) {
                result.addProperty("lastStep", ((StepFailureException) t).step);
            }
            result.addProperty("error", stackTraceString(t));
        }
        postJson(http, signalBase + "/result/android", result);
        if (!result.get("ok").getAsBoolean()) throw new AssertionError(result.get("error").getAsString());
    }

    private static void fillSnapshot(JsonObject out, NGEPlatform p) throws Exception {
        AsyncExecutor exec = p.newAsyncExecutor("parity");
        try {
            out.addProperty("executorRun", step("executorRun", () -> exec.run(() -> 7).await()));
            out.addProperty("executorRunLater", step("executorRunLater", () -> exec.runLater(() -> 9, 10, TimeUnit.MILLISECONDS).await()));
            out.addProperty("wrapResolved", step("wrapResolved", () -> p.<Integer>wrapPromise((res, rej) -> res.accept(5)).await()));

            AtomicBoolean caught = new AtomicBoolean(false);
            AsyncTask<Integer> failed = step("wrapRejectedCreate", () -> p.wrapPromise((res, rej) -> rej.accept(new IllegalStateException("boom"))));
            step("wrapRejectedCatchAttach", () -> {
                failed.catchException(e -> caught.set(true));
                return null;
            });
            boolean wrapRejectedAwaitFailed;
            try {
                step("wrapRejectedAwait", failed::await);
                wrapRejectedAwaitFailed = false;
            } catch (Throwable t) {
                wrapRejectedAwaitFailed = true;
            }
            out.addProperty("wrapRejectedCaught", caught.get());
            out.addProperty("wrapRejectedAwaitFailed", wrapRejectedAwaitFailed);

            out.addProperty("promisifyResolved", step("promisifyResolved", () -> p.<String>promisify((res, rej) -> res.accept("exec-ok"), exec).await()));
            out.addProperty("thenChain", step("thenChain", () -> AsyncTask.completed(3).then(v -> v + 4).await()));
            out.addProperty("composeChain", step("composeChain", () -> AsyncTask.completed(3).compose(v -> AsyncTask.completed(v * 2)).await()));

            List<AsyncTask<String>> allTasks = step("awaitAllCreateTasks", () -> List.of(
                exec.runLater(() -> "a", 30, TimeUnit.MILLISECONDS),
                exec.runLater(() -> "b", 0, TimeUnit.MILLISECONDS),
                exec.runLater(() -> "c", 10, TimeUnit.MILLISECONDS)
            ));
            out.addProperty("awaitAllOrder", step("awaitAllOrder", () -> String.join(",", p.awaitAll(allTasks).await())));
            out.addProperty("awaitAnyFirstSuccess", step("awaitAnyFirstSuccess", () -> p.awaitAny(List.of(AsyncTask.failed(new RuntimeException("x")), exec.runLater(() -> 42, 10, TimeUnit.MILLISECONDS))).await()));
            out.addProperty("awaitAnyFilter", step("awaitAnyFilter", () -> p.awaitAny(List.of(exec.runLater(() -> 5, 0, TimeUnit.MILLISECONDS), exec.runLater(() -> 12, 10, TimeUnit.MILLISECONDS)), v -> v > 10).await()));
            out.addProperty("awaitAllFail", step("awaitAllFail", () -> fails(() -> p.awaitAll(List.of(AsyncTask.completed(1), AsyncTask.failed(new RuntimeException("x")))).await())));
            out.addProperty("awaitAnyAllFail", step("awaitAnyAllFail", () -> fails(() -> p.awaitAny(List.of(AsyncTask.failed(new RuntimeException("a")), AsyncTask.failed(new RuntimeException("b")))).await())));
            out.addProperty("awaitAnyNoMatch", step("awaitAnyNoMatch", () -> fails(() -> p.awaitAny(List.of(AsyncTask.completed(1), AsyncTask.completed(2)), v -> v > 10).await())));

            List<AsyncTask<Integer>> settled = step("awaitAllSettled", () -> p.awaitAllSettled(List.of(AsyncTask.completed(1), AsyncTask.failed(new RuntimeException("z")))).await());
            out.addProperty("awaitAllSettledCount", settled.size());
            out.addProperty("awaitAllSettledPattern", (settled.get(0).isSuccess() ? "S" : "F") + (settled.get(1).isSuccess() ? "S" : "F"));

            Queue<String> q = step("queueCreate", () -> p.newConcurrentQueue(String.class));
            q.add("q1"); q.add("q2");
            out.addProperty("queueOrder", q.poll() + "," + q.poll());
        } finally {
            try { exec.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean fails(ThrowingRunnable r) { try { r.run(); return false; } catch (Throwable t) { return true; } }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    private static <T> T step(String name, ThrowingSupplier<T> fn) throws Exception {
        System.out.println("ASYNC_PARITY step=" + name);
        try {
            return fn.get();
        } catch (Throwable t) {
            throw new StepFailureException(name, t);
        }
    }

    private static final class StepFailureException extends Exception {
        final String step;
        StepFailureException(String step, Throwable cause) {
            super("Async parity step failed: " + step, cause);
            this.step = step;
        }
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
