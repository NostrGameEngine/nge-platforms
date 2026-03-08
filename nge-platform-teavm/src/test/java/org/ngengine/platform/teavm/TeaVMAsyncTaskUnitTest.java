package org.ngengine.platform.teavm;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;

public class TeaVMAsyncTaskUnitTest {

    @Test
    public void composeAttachedAfterAlreadyResolvedTaskExecutes() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncTask<Integer> resolved = platform.wrapPromise((resolve, reject) -> resolve.accept(4));
        assertEquals(Integer.valueOf(4), resolved.await());

        AsyncTask<Integer> composed = resolved.compose(v -> AsyncTask.completed(v + 6));
        assertEquals(Integer.valueOf(10), composed.await());
    }

    @Test
    public void wrapPromiseSynchronousThrowReturnsFailedTask() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncTask<String> task = platform.wrapPromise((resolve, reject) -> {
            throw new IllegalStateException("sync failure");
        });

        assertTrue(task.isDone());
        assertTrue(task.isFailed());
        try {
            task.await();
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("sync failure"));
        }
    }

    @Test
    public void promisifyWithExecutorRunFailureReturnsFailedTask() throws Exception {
        TeaVMPlatform platform = newPlatformOrSkip();
        AsyncExecutor failingExecutor = new AsyncExecutor() {
            @Override
            public <T> AsyncTask<T> run(java.util.concurrent.Callable<T> r) {
                throw new RuntimeException("executor down");
            }

            @Override
            public <T> AsyncTask<T> runLater(java.util.concurrent.Callable<T> r, long delay, TimeUnit unit) {
                throw new RuntimeException("executor down");
            }

            @Override
            public void close() {}
        };

        AsyncTask<String> task = platform.promisify((resolve, reject) -> resolve.accept("ok"), failingExecutor);
        assertTrue(task.isDone());
        assertTrue(task.isFailed());
        try {
            task.await();
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("executor down"));
        }
    }

    private static TeaVMPlatform newPlatformOrSkip() {
        try {
            TeaVMPlatform platform = new TeaVMPlatform();
            platform.wrapPromise((resolve, reject) -> resolve.accept("probe")).await();
            return platform;
        } catch (Throwable t) {
            Assume.assumeNoException("TeaVM runtime not available for async task tests", t);
            throw new AssertionError(t);
        }
    }
}
