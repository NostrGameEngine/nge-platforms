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

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.VStore;
import org.ngengine.platform.VStore.VStoreBackend;

/** Source fixtures only until separately qualified; all file contents are synthetic. */
public class FileSystemVStoreCreateTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 10000)
    public void independentProvidersElectOneCompleteWinnerAndReuseItAcrossReopen() throws Exception {
        Path base = temporary.newFolder("race").toPath();
        byte[] firstValue = value(VStore.MAX_CREATE_IF_ABSENT_BYTES, 17);
        byte[] secondValue = value(VStore.MAX_CREATE_IF_ABSENT_BYTES, 93);
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch release = new CountDownLatch(1);
        FileSystemVStore.LinkPublisher barrier = (target, complete) -> {
            assertEquals(VStore.MAX_CREATE_IF_ABSENT_BYTES, Files.size(complete));
            byte[] written = Files.readAllBytes(complete);
            assertTrue(Arrays.equals(firstValue, written) || Arrays.equals(secondValue, written));
            ready.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture publication barrier expired"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            Files.createLink(target, complete);
        };
        // Direct calls intentionally bypass the JVM-global VStore queue: both provider instances
        // must reach kernel publication concurrently, just as separate client processes can.
        FileSystemVStore first = new FileSystemVStore(base, barrier, FileSystemVStoreCreateTest::forceDirectory);
        FileSystemVStore second = new FileSystemVStore(base, barrier, FileSystemVStoreCreateTest::forceDirectory);
        AsyncTask<Boolean> a = first.createIfAbsent("identity.bin", firstValue);
        AsyncTask<Boolean> b = second.createIfAbsent("identity.bin", secondValue);
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        boolean createdA = a.await(); boolean createdB = b.await(); assertTrue(createdA ^ createdB);
        Path target = base.resolve("identity.bin");
        assertArrayEquals(createdA ? firstValue : secondValue, Files.readAllBytes(target));
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(target);
        assertFalse(new FileSystemVStore(base).createIfAbsent("identity.bin", value(32, 51)).await());
        assertArrayEquals(createdA ? firstValue : secondValue, Files.readAllBytes(target));
        assertEquals(modified, Files.getLastModifiedTime(target)); assertNoTemporary(base);
        assertArrayEquals(value(firstValue.length, 17), firstValue);
        assertArrayEquals(value(secondValue.length, 93), secondValue);
    }

    @Test(timeout = 5000)
    public void existingValueDirectoryAndSymlinkAreNeverReplaced() throws Exception {
        Path base = temporary.newFolder("existing").toPath(); Path target = base.resolve("identity.bin");
        byte[] original = value(32, 7); Files.write(target, original);
        FileSystemVStore store = new FileSystemVStore(base);
        assertFalse(store.createIfAbsent("identity.bin", value(32, 8)).await());
        assertArrayEquals(original, Files.readAllBytes(target));
        Files.createDirectory(base.resolve("occupied"));
        assertFalse(store.createIfAbsent("occupied", value(32, 8)).await());
        assertTrue(Files.isDirectory(base.resolve("occupied")));
        if (base.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Path link = base.resolve("alias"); Files.createSymbolicLink(link, target.getFileName());
            assertFalse(store.createIfAbsent("alias", value(32, 8)).await());
            assertTrue(Files.isSymbolicLink(link)); assertArrayEquals(original, Files.readAllBytes(target));
        }
        assertNoTemporary(base);
    }

    @Test(timeout = 5000)
    public void publicQueueSnapshotsBeforeDispatchAndErasesOnlyAfterCompletion() throws Exception {
        ControlledBackend blocker = new ControlledBackend(); ControlledBackend backend = new ControlledBackend();
        AsyncTask<Boolean> blocked = new VStore(blocker).createIfAbsent("blocker.bin", value(1, 1));
        try {
            assertTrue(blocker.entered.await(2, TimeUnit.SECONDS));
            byte[] caller = value(32, 41); byte[] original = caller.clone();
            AsyncTask<Boolean> result = new VStore(backend).createIfAbsent("identity.bin", caller);
            assertEquals(1L, backend.entered.getCount());
            // Mutation occurs while the operation is still waiting behind a different backend.
            Arrays.fill(caller, (byte) 99); blocker.commit(); assertTrue(blocked.await());
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS));
            assertNotSame(caller, backend.received); assertArrayEquals(original, backend.received);
            backend.commit(); assertTrue(result.await());
            assertArrayEquals(original, backend.persisted); assertArrayEquals(new byte[32], backend.received);
            assertArrayEquals(value(32, 99), caller);
        } finally { blocker.finishIfPending(); backend.finishIfPending(); }
    }

    @Test(timeout = 5000)
    public void directJvmProviderSnapshotsCallerBeforeAsynchronousWrite() throws Exception {
        Path base = temporary.newFolder("direct-snapshot").toPath(); byte[] caller = value(32, 43);
        FileSystemVStore store = new FileSystemVStore(base, (target, complete) -> {
            assertArrayEquals(value(32, 43), Files.readAllBytes(complete)); Files.createLink(target, complete);
        }, FileSystemVStoreCreateTest::forceDirectory);
        AsyncTask<Boolean> publication = store.createIfAbsent("identity.bin", caller);
        Arrays.fill(caller, (byte) 44); assertTrue(publication.await());
        assertArrayEquals(value(32, 43), Files.readAllBytes(base.resolve("identity.bin")));
        assertArrayEquals(value(32, 44), caller); assertNoTemporary(base);
    }

    @Test(timeout = 5000)
    public void cancelledCallerViewDoesNotEraseOrAbortPrivatePublication() throws Exception {
        ControlledBackend backend = new ControlledBackend(); VStore store = new VStore(backend);
        byte[] caller = value(32, 53); AsyncTask<Boolean> view = store.createIfAbsent("identity.bin", caller);
        try {
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS)); view.cancel();
            assertArrayEquals(caller, backend.received); assertNull(backend.persisted);
            backend.commit();
            // A following normal queued publication observes the private operation's completion.
            VStore subsequent = new VStore(new FileSystemVStore(temporary.newFolder("after-cancel").toPath()));
            assertTrue(subsequent.createIfAbsent("complete.bin", value(32, 54)).await());
            assertArrayEquals(caller, backend.persisted); assertArrayEquals(new byte[32], backend.received);
            assertThrows(java.util.concurrent.CancellationException.class, view::await);
            assertArrayEquals(value(32, 53), caller);
        } finally { backend.finishIfPending(); }
    }

    @Test(timeout = 5000)
    public void backendFailureErasesPrivateSnapshotAndPreservesCallerAndError() throws Exception {
        ControlledBackend backend = new ControlledBackend(); byte[] caller = value(32, 63);
        AsyncTask<Boolean> result = new VStore(backend).createIfAbsent("identity.bin", caller);
        IOException failure = new IOException("Synthetic conditional IO failure");
        try {
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS)); backend.fail(failure);
            assertSame(failure, assertThrows(IOException.class, result::await));
            assertArrayEquals(new byte[32], backend.received); assertArrayEquals(value(32, 63), caller);
        } finally { backend.finishIfPending(); }
    }

    @Test(timeout = 5000)
    public void defaultBackendRefusesUnsupportedAndBoundsAreCheckedBeforeDispatch() throws Exception {
        VStore unsupported = new VStore(new UnsupportedBackend());
        assertThrows(UnsupportedOperationException.class, () -> unsupported.createIfAbsent("identity.bin", value(32, 1)).await());
        ControlledBackend backend = new ControlledBackend(); VStore bounded = new VStore(backend);
        assertThrows(IllegalArgumentException.class,
                () -> bounded.createIfAbsent("identity.bin", new byte[VStore.MAX_CREATE_IF_ABSENT_BYTES + 1]));
        assertEquals(1L, backend.entered.getCount());
        FileSystemVStore real = new FileSystemVStore(temporary.newFolder("bounds").toPath());
        assertThrows(IllegalArgumentException.class,
                () -> real.createIfAbsent("identity.bin", new byte[VStore.MAX_CREATE_IF_ABSENT_BYTES + 1]));
        assertTrue(real.createIfAbsent("empty.bin", new byte[0]).await());
        try (InputStream input = real.read("empty.bin").await()) { assertEquals(-1, input.read()); }
    }

    @Test(timeout = 5000)
    public void publicationIoErrorIsNotExistenceAndOnlyOwnTemporaryIsCleaned() throws Exception {
        Path base = temporary.newFolder("io-error").toPath(); Path unrelated = base.resolve("unrelated.tmp");
        byte[] untouched = value(12, 71); Files.write(unrelated, untouched);
        IOException failure = new IOException("Synthetic hard-link IO failure");
        FileSystemVStore store = new FileSystemVStore(base, (target, complete) -> {
            assertArrayEquals(value(32, 72), Files.readAllBytes(complete)); throw failure;
        }, FileSystemVStoreCreateTest::forceDirectory);
        assertSame(failure, assertThrows(IOException.class, () -> store.createIfAbsent("identity.bin", value(32, 72)).await()));
        assertFalse(Files.exists(base.resolve("identity.bin"))); assertArrayEquals(untouched, Files.readAllBytes(unrelated));
        assertNoTemporary(base);
        Path nonDirectory = base.resolve("not-a-directory"); Files.write(nonDirectory, untouched);
        assertThrows(IOException.class,
                () -> new FileSystemVStore(nonDirectory).createIfAbsent("identity.bin", value(32, 72)).await());
        assertArrayEquals(untouched, Files.readAllBytes(nonDirectory));
    }

    @Test(timeout = 5000)
    public void unsupportedHardLinkNeverFallsBackToReplacingMove() throws Exception {
        Path base = temporary.newFolder("unsupported-link").toPath();
        FileSystemVStore store = new FileSystemVStore(base, (target, complete) -> {
            throw new UnsupportedOperationException("Synthetic hard-link capability unavailable");
        }, FileSystemVStoreCreateTest::forceDirectory);
        assertThrows(UnsupportedOperationException.class, () -> store.createIfAbsent("identity.bin", value(32, 81)).await());
        assertFalse(Files.exists(base.resolve("identity.bin"))); assertNoTemporary(base);
    }

    @Test(timeout = 5000)
    public void forceFailureAfterPublicationPreservesCompleteWinnerAndCleansTemporary() throws Exception {
        Path base = temporary.newFolder("post-force").toPath();
        IOException failure = new IOException("Synthetic post-publication directory-sync failure");
        FileSystemVStore store = new FileSystemVStore(base, Files::createLink, parent -> {
            if (Files.exists(base.resolve("identity.bin"), LinkOption.NOFOLLOW_LINKS)) throw failure;
            forceDirectory(parent);
        });
        byte[] complete = value(32, 91);
        assertSame(failure, assertThrows(IOException.class, () -> store.createIfAbsent("identity.bin", complete).await()));
        assertArrayEquals(complete, Files.readAllBytes(base.resolve("identity.bin"))); assertNoTemporary(base);
        assertFalse(new FileSystemVStore(base).createIfAbsent("identity.bin", value(32, 92)).await());
        assertArrayEquals(complete, Files.readAllBytes(base.resolve("identity.bin")));
    }

    @Test(timeout = 5000)
    public void forceFailureBeforePublicationCreatesNoTargetOrTemporary() throws Exception {
        Path base = temporary.newFolder("pre-force").toPath(); IOException failure = new IOException("Synthetic pre-publication force failure");
        FileSystemVStore store = new FileSystemVStore(base, Files::createLink, parent -> { throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> store.createIfAbsent("identity.bin", value(32, 101)).await()));
        assertFalse(Files.exists(base.resolve("identity.bin"))); assertNoTemporary(base);
    }

    @Test(timeout = 10000)
    public void rejectedJvmSubmissionSettlesPublicTaskErasesSnapshotsAndAdvancesQueue() throws Exception {
        Path base = temporary.newFolder("rejected-executor").toPath();
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        AtomicReference<byte[]> privateSnapshot = new AtomicReference<>();
        ControlledBackend following = new ControlledBackend();
        byte[] caller = value(32, 111);
        try {
            platform.executor.shutdown();
            FileSystemVStore provider = new FileSystemVStore(base, Files::createLink,
                    FileSystemVStoreCreateTest::forceDirectory, platform, privateSnapshot::set);
            CapturingBackend backend = new CapturingBackend(provider);
            AsyncTask<Boolean> rejected = new VStore(backend).createIfAbsent("identity.bin", caller);
            assertThrows(RejectedExecutionException.class, rejected::await);
            assertTrue(rejected.isFailed());
            assertArrayEquals(new byte[32], backend.received);
            assertArrayEquals(new byte[32], privateSnapshot.get());
            assertArrayEquals(value(32, 111), caller);
            assertFalse(Files.exists(base.resolve("identity.bin"))); assertNoTemporary(base);
            AsyncTask<Boolean> next = new VStore(following).createIfAbsent("following.bin", value(1, 112));
            assertTrue(following.entered.await(2, TimeUnit.SECONDS)); following.commit(); assertTrue(next.await());
            // Direct observers also normalize wrapped errors and run after their executor rejects.
            IOException failure = new IOException("Synthetic normalized direct failure");
            AtomicReference<Throwable> observed = new AtomicReference<>();
            platform.wrapPromise((resolve, reject) -> reject.accept(new java.util.concurrent.CompletionException(failure)))
                    .observeCompletion(ignored -> fail("Failure must not reach success observer"), observed::set);
            assertSame(failure, observed.get());
        } finally {
            following.finishIfPending(); platform.executor.shutdownNow();
            assertTrue(platform.executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 10000)
    public void shutdownAfterPublicationStillForwardsCompletionAndErasesOnlySettledSnapshots() throws Exception {
        Path base = temporary.newFolder("late-executor-shutdown").toPath();
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<byte[]> privateSnapshot = new AtomicReference<>();
        ControlledBackend following = new ControlledBackend();
        byte[] caller = value(32, 121);
        try {
            FileSystemVStore provider = new FileSystemVStore(base, (target, complete) -> {
                Files.createLink(target, complete);
                assertArrayEquals(value(32, 121), Files.readAllBytes(target));
                published.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture completion release expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }, FileSystemVStoreCreateTest::forceDirectory, platform, privateSnapshot::set);
            CapturingBackend backend = new CapturingBackend(provider);
            AsyncTask<Boolean> result = new VStore(backend).createIfAbsent("identity.bin", caller);
            assertTrue(published.await(2, TimeUnit.SECONDS));
            AsyncTask<Boolean> next = new VStore(following).createIfAbsent("following.bin", value(1, 122));
            assertEquals(1L, following.entered.getCount()); assertFalse(result.isDone());
            assertArrayEquals(caller, backend.received); assertArrayEquals(caller, privateSnapshot.get());
            // The worker is live but no new observer dispatch can be submitted to its executor.
            platform.executor.shutdown(); release.countDown();
            assertTrue(result.await()); assertTrue(result.isSuccess());
            assertTrue(following.entered.await(2, TimeUnit.SECONDS)); following.commit(); assertTrue(next.await());
            assertArrayEquals(new byte[32], backend.received);
            assertArrayEquals(new byte[32], privateSnapshot.get());
            assertArrayEquals(value(32, 121), caller);
            assertArrayEquals(caller, Files.readAllBytes(base.resolve("identity.bin"))); assertNoTemporary(base);
        } finally {
            release.countDown(); following.finishIfPending(); platform.executor.shutdownNow();
            assertTrue(platform.executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static byte[] value(int size, int marker) { byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) marker); return bytes; }
    private static void assertNoTemporary(Path base) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base, ".vstore-create-*.tmp")) { assertFalse(entries.iterator().hasNext()); }
    }
    private static void forceDirectory(Path parent) throws IOException {
        if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)) {
            try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        }
    }

    private static class UnsupportedBackend implements VStoreBackend {
        @Override public AsyncTask<InputStream> read(String path) { return AsyncTask.failed(new UnsupportedOperationException()); }
        @Override public AsyncTask<OutputStream> write(String path) { return AsyncTask.failed(new UnsupportedOperationException()); }
        @Override public AsyncTask<Boolean> exists(String path) { return AsyncTask.failed(new UnsupportedOperationException()); }
        @Override public AsyncTask<Void> delete(String path) { return AsyncTask.failed(new UnsupportedOperationException()); }
        @Override public AsyncTask<List<String>> listAll() { return AsyncTask.failed(new UnsupportedOperationException()); }
    }
    private static final class CapturingBackend extends UnsupportedBackend {
        private final FileSystemVStore delegate;
        volatile byte[] received;
        CapturingBackend(FileSystemVStore delegate) { this.delegate = delegate; }
        @Override public AsyncTask<Boolean> createIfAbsent(String path, byte[] complete) {
            received = complete;
            return delegate.createIfAbsent(path, complete);
        }
    }
    private static final class ControlledBackend extends UnsupportedBackend {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile byte[] received;
        volatile byte[] persisted;
        private Consumer<Boolean> resolve;
        private Consumer<Throwable> reject;
        private boolean pending;
        @Override public AsyncTask<Boolean> createIfAbsent(String path, byte[] complete) {
            return AsyncTask.create((res, rej) -> {
                synchronized (this) { received = complete; resolve = res; reject = rej; pending = true; }
                entered.countDown();
            });
        }
        synchronized void commit() { persisted = received.clone(); pending = false; resolve.accept(true); }
        synchronized void fail(Throwable failure) { pending = false; reject.accept(failure); }
        synchronized void finishIfPending() { if (pending) { pending = false; resolve.accept(false); } }
    }
}
