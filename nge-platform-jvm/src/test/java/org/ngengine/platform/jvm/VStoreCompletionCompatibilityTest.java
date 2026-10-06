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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.VStore;

public class VStoreCompletionCompatibilityTest {

    @Test(timeout = 10000)
    public void unsupportedObservationRejectsBeforeQueueingAndPreservesPendingWork() throws Exception {
        verifyRejectedWhileQueued(new LegacyObserverPlatform(false, 0));
    }

    @Test(timeout = 10000)
    public void mixedObservationSupportAbandonsQueuedCreation() throws Exception {
        verifyRejectedWhileQueued(new LegacyObserverPlatform(true, 0));
    }

    @Test(timeout = 10000)
    public void detachedViewObservationFailureAlsoAbandonsQueuedCreation() throws Exception {
        verifyRejectedWhileQueued(new LegacyObserverPlatform(true, 2));
    }

    private void verifyRejectedWhileQueued(LegacyObserverPlatform platform) throws Exception {
        withPlatform(platform, () -> {
            RecordingBackend backend = new RecordingBackend();
            try {
                VStore store = new VStore(backend);
                AsyncTask<InputStream> blocker = store.read("hold-queue");
                assertNotNull("The ordinary read must hold the queue", backend.release);
                byte[] caller = new byte[] { 17, 34, 51, 68 };
                byte[] originalValue = caller.clone();
                assertThrows(UnsupportedOperationException.class, () -> store.createIfAbsent("value", caller).await());
                assertEquals("The rejected call must not reach the backend", 0, backend.createCalls);
                assertNull(backend.published);
                assertArrayEquals(originalValue, caller);

                backend.releaseRead();
                blocker.await().close();
                assertQueueDrains(platform);
                assertEquals(
                    "Rejected creation remained queued and published " + Arrays.toString(backend.published),
                    0,
                    backend.createCalls
                );
                assertNull(backend.published);
                assertArrayEquals(originalValue, caller);
            } finally {
                backend.finish();
            }
        });
    }

    @Test(timeout = 10000)
    public void startedPublicationKeepsSnapshotUntilBackendSettlesAfterObservationFailure() throws Exception {
        LegacyObserverPlatform platform = new LegacyObserverPlatform(true, 1);
        withPlatform(platform, () -> {
            RecordingBackend backend = new RecordingBackend();
            backend.deferCreation = true;
            try {
                VStore store = new VStore(backend);
                AsyncTask<InputStream> blocker = store.read("hold-queue");
                platform.beforeObservationFailure = () -> {
                    // Start wins ownership while the queued task's observer is being registered.
                    platform.observeAllTasks = true;
                    backend.releaseRead();
                };
                byte[] caller = new byte[] { 17, 34, 51, 68 };
                assertThrows(UnsupportedOperationException.class, () -> store.createIfAbsent("value", caller).await());
                blocker.await().close();
                assertEquals(1, backend.createCalls);
                assertNull("Publication is still pending", backend.published);
                assertArrayEquals("Started backend must retain intact bytes", caller, backend.received);

                backend.completeCreation();
                assertQueueDrains(platform);
                assertArrayEquals(caller, backend.published);
                assertArrayEquals("Erase only after backend settlement", new byte[caller.length], backend.received);
                assertArrayEquals(new byte[] { 17, 34, 51, 68 }, caller);
            } finally {
                platform.observeAllTasks = true;
                backend.finish();
            }
        });
    }

    @Test(timeout = 30000)
    public void racingDispatchAndObservationFailureNeverPublishesErasedBytes() throws Exception {
        LegacyObserverPlatform platform = new LegacyObserverPlatform(true, 0);
        withPlatform(platform, () -> {
            for (int attempt = 0; attempt < 100; attempt++) {
                RecordingBackend backend = new RecordingBackend();
                VStore store = new VStore(backend);
                AsyncTask<InputStream> blocker = store.read("hold-queue");
                byte[] caller = new byte[] { 17, 34, 51, 68 };
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                Thread create = Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        store.createIfAbsent("value", caller).await();
                    } catch (UnsupportedOperationException expected) {
                        // Either dispatch or abandonment may win the race.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                Thread release = Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        backend.releaseRead();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                try {
                    start.countDown();
                    create.join(2000);
                    release.join(2000);
                    assertFalse("Creation thread stalled", create.isAlive());
                    assertFalse("Queue release thread stalled", release.isAlive());
                    assertNull("Unexpected race failure", unexpected.get());
                    blocker.await().close();
                    assertQueueDrains(platform);
                    assertTrue("At most one backend invocation", backend.createCalls <= 1);
                    if (backend.createCalls != 0) {
                        assertArrayEquals("A dispatch winner must receive intact bytes", caller, backend.published);
                        assertArrayEquals(new byte[caller.length], backend.received);
                    }
                    assertArrayEquals(new byte[] { 17, 34, 51, 68 }, caller);
                } finally {
                    start.countDown();
                    create.interrupt();
                    release.interrupt();
                    create.join(2000);
                    release.join(2000);
                    backend.finish();
                }
            }
        });
    }

    private static void assertQueueDrains(LegacyObserverPlatform platform) throws Exception {
        AsyncTask<String> drained = platform.getVStoreQueue().enqueue((resolve, reject) -> resolve.accept("drained"));
        assertEquals("The queue must remain usable", "drained", drained.await());
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void withPlatform(LegacyObserverPlatform platform, CheckedAction action) throws Exception {
        synchronized (NGEPlatform.class) {
            Field platformField = NGEPlatform.class.getDeclaredField("platform");
            platformField.setAccessible(true);
            NGEPlatform originalPlatform = (NGEPlatform) platformField.get(null);
            try {
                platformField.set(null, platform);
                action.run();
            } finally {
                platformField.set(null, originalPlatform);
                platform.shutdownForTest();
            }
            assertSame("The fixture must restore the installed platform", originalPlatform, platformField.get(null));
        }
    }

    /**
     * Synthetic custom/non-JVM compatibility fixture. The production JVM tasks support direct
     * observation; this wrapper deliberately retains AsyncTask's unsupported default instead.
     */
    private static class LegacyTask<T> implements AsyncTask<T> {

        final AsyncTask<T> delegate;

        LegacyTask(AsyncTask<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public boolean isFailed() {
            return delegate.isFailed();
        }

        @Override
        public boolean isSuccess() {
            return delegate.isSuccess();
        }

        @Override
        public T await() throws Exception {
            return delegate.await();
        }

        @Override
        public <R> AsyncTask<R> then(ThrowableFunction<T, R> function) {
            return delegate.then(function);
        }

        @Override
        public <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> function) {
            return delegate.compose(function);
        }

        @Override
        public AsyncTask<T> catchException(Consumer<Throwable> failure) {
            delegate.catchException(failure);
            return this;
        }
    }

    private static final class RegistrationFailureTask<T> extends LegacyTask<T> {

        private final int failingRegistration;
        private final Runnable beforeFailure;
        private final AtomicInteger registrations = new AtomicInteger();

        RegistrationFailureTask(AsyncTask<T> delegate, int failingRegistration, Runnable beforeFailure) {
            super(delegate);
            this.failingRegistration = failingRegistration;
            this.beforeFailure = beforeFailure;
        }

        @Override
        public void observeCompletion(Consumer<T> success, Consumer<Throwable> failure) {
            if (registrations.incrementAndGet() == failingRegistration) {
                beforeFailure.run();
                throw new UnsupportedOperationException("Synthetic observer registration failure");
            }
            delegate.observeCompletion(success, failure);
        }
    }

    private static final class LegacyObserverPlatform extends JVMAsyncPlatform {

        private final boolean observeCompletedTasks;
        private final int failingRegistration;
        private volatile boolean observeAllTasks;
        private Runnable beforeObservationFailure = () -> {};

        LegacyObserverPlatform(boolean observeCompletedTasks, int failingRegistration) {
            this.observeCompletedTasks = observeCompletedTasks;
            this.failingRegistration = failingRegistration;
        }

        @Override
        public <T> AsyncTask<T> promisify(
            BiConsumer<Consumer<T>, Consumer<Throwable>> function,
            AsyncExecutor executor
        ) {
            AsyncTask<T> task = super.promisify(function, executor);
            if (observeAllTasks || (observeCompletedTasks && task.isDone())) {
                return task;
            }
            if (failingRegistration != 0) {
                return new RegistrationFailureTask<>(task, failingRegistration, () -> beforeObservationFailure.run());
            }
            return new LegacyTask<>(task);
        }

        void shutdownForTest() throws InterruptedException {
            executor.shutdownNow();
            assertTrue("Fixture executor did not stop", executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class RecordingBackend implements VStore.VStoreBackend {

        private Consumer<InputStream> release;
        private int createCalls;
        private byte[] published;
        private byte[] received;
        private boolean deferCreation;
        private Consumer<Boolean> complete;

        @Override
        public AsyncTask<InputStream> read(String path) {
            return AsyncTask.create((resolve, reject) -> release = resolve);
        }

        void releaseRead() {
            if (release != null) {
                Consumer<InputStream> resolve = release;
                release = null;
                resolve.accept(new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public AsyncTask<Boolean> createIfAbsent(String path, byte[] completeValue) {
            createCalls++;
            received = completeValue;
            if (deferCreation) {
                return AsyncTask.create((resolve, reject) -> complete = resolve);
            }
            published = completeValue.clone();
            return AsyncTask.completed(true);
        }

        void completeCreation() {
            if (complete != null) {
                Consumer<Boolean> resolve = complete;
                complete = null;
                published = received.clone();
                resolve.accept(true);
            }
        }

        void finish() {
            releaseRead();
            completeCreation();
        }

        @Override
        public AsyncTask<OutputStream> write(String path) {
            return AsyncTask.failed(new UnsupportedOperationException());
        }

        @Override
        public AsyncTask<Boolean> exists(String path) {
            return AsyncTask.completed(published != null);
        }

        @Override
        public AsyncTask<Void> delete(String path) {
            return AsyncTask.failed(new UnsupportedOperationException());
        }

        @Override
        public AsyncTask<List<String>> listAll() {
            return AsyncTask.failed(new UnsupportedOperationException());
        }
    }
}
