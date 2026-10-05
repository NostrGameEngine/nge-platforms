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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.VStore.VStoreBackend;

public class FileSystemVStore implements VStoreBackend {

    private final AsyncExecutor executor;
    private final Path basePath;
    private final LinkPublisher linkPublisher;
    private final DirectoryForce directoryForce;
    private final NGEPlatform publicationPlatform;
    private final Consumer<byte[]> snapshotObserver;

    @FunctionalInterface interface LinkPublisher { void publish(Path target, Path completeTemporary) throws IOException; }
    @FunctionalInterface interface DirectoryForce { void force(Path parent) throws IOException; }

    public FileSystemVStore(Path basePath) {
        this(basePath, Files::createLink, FileSystemVStore::forceDirectoryWhereSupported);
    }

    FileSystemVStore(Path basePath, LinkPublisher linkPublisher, DirectoryForce directoryForce) {
        this(basePath, linkPublisher, directoryForce, NGEPlatform.get(), ignored -> {});
    }

    // Disposable actual-JVM executor and borrowed-buffer observation for lifecycle fixtures.
    FileSystemVStore(Path basePath, LinkPublisher linkPublisher, DirectoryForce directoryForce,
                    NGEPlatform publicationPlatform, Consumer<byte[]> snapshotObserver) {
        this.basePath = Objects.requireNonNull(basePath);
        this.linkPublisher = Objects.requireNonNull(linkPublisher);
        this.directoryForce = Objects.requireNonNull(directoryForce);
        this.publicationPlatform = Objects.requireNonNull(publicationPlatform);
        this.snapshotObserver = Objects.requireNonNull(snapshotObserver);
        this.executor = publicationPlatform.newAsyncExecutor(VStoreBackend.class);
    }

    @Override
    public AsyncTask<InputStream> read(String path) {
        return NGEPlatform
            .get()
            .promisify(
                (res, rej) -> {
                    try {
                        Path fullPath = Util.safePath(basePath, path, false);
                        FileInputStream is = new FileInputStream(fullPath.toFile());
                        res.accept(is);
                    } catch (IOException e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    @Override
    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform
            .get()
            .promisify(
                (res, rej) -> {
                    try {
                        Path fullPath = Util.safePath(basePath, path, true);
                        SafeFileOutputStream os = new SafeFileOutputStream(fullPath);
                        res.accept(os);
                    } catch (IOException e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    /**
     * Publishes a fully synced temporary file with an atomic hard-link create, never a replacing
     * move. Independent processes therefore elect one complete winner. POSIX-capable file stores
     * use 0600 temporary files and force parent-directory entries; other providers retain their
     * normal desktop creation ACL and filesystem-dependent directory crash durability. Unsupported
     * hard links or IO/cleanup/force failures are errors. A visible target is never removed on error.
     * Cancellation of the returned view does not withdraw the private commit.
     */
    @Override
    public AsyncTask<Boolean> createIfAbsent(String path, byte[] completeValue) {
        Objects.requireNonNull(path, "Store path required");
        Objects.requireNonNull(completeValue, "Complete store value required");
        if (completeValue.length > VStore.MAX_CREATE_IF_ABSENT_BYTES) {
            throw new IllegalArgumentException("Conditional store value exceeds limit");
        }
        byte[] snapshot = Arrays.copyOf(completeValue, completeValue.length);
        try {
            snapshotObserver.accept(snapshot);
            AsyncTask<Boolean> privateTask = publicationPlatform.promisify((resolve, reject) -> {
                try { resolve.accept(publishIfAbsent(path, snapshot)); }
                catch (Throwable failure) { reject.accept(failure); }
            }, executor);
            // Observe the private task directly, including submission rejection or late shutdown.
            // The caller gets a detached no-affinity promise, never an executor-affine then view.
            return publicationPlatform.wrapPromise((resolve, reject) -> privateTask.observeCompletion(created -> {
                Arrays.fill(snapshot, (byte) 0);
                resolve.accept(created);
            }, failure -> {
                Arrays.fill(snapshot, (byte) 0);
                reject.accept(failure);
            }));
        } catch (RuntimeException | Error failure) {
            Arrays.fill(snapshot, (byte) 0);
            throw failure;
        }
    }

    private boolean publishIfAbsent(String path, byte[] snapshot) throws IOException {
        Path target = Util.safePath(basePath, path, true);
        try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException absent) { /* only actual absence is eligible for creation */ }
        Path parent = target.getParent();
        directoryForce.force(parent);
        Path temporary = Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)
                ? Files.createTempFile(parent, ".vstore-create-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(parent, ".vstore-create-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(snapshot);
                output.getFD().sync();
            }
            try {
                linkPublisher.publish(target, temporary);
                return true;
            } catch (FileAlreadyExistsException competing) {
                return false;
            }
        } finally {
            // Delete only this operation's unique temporary name; never repair or delete the target.
            Files.delete(temporary);
            directoryForce.force(parent);
        }
    }

    private static void forceDirectoryWhereSupported(Path parent) throws IOException {
        if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)) {
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                directory.force(true);
            }
        }
    }

    @Override
    public AsyncTask<Boolean> exists(String path) {
        return NGEPlatform
            .get()
            .promisify(
                (res, rej) -> {
                    try {
                        Path fullPath = Util.safePath(basePath, path, false);
                        res.accept(fullPath != null && Files.exists(fullPath));
                    } catch (IOException e) {
                        res.accept(false);
                    }
                },
                executor
            );
    }

    @Override
    public AsyncTask<Void> delete(String path) {
        return NGEPlatform
            .get()
            .promisify(
                (res, rej) -> {
                    try {
                        Path fullPath = Util.safePath(basePath, path, false);
                        if (fullPath != null) {
                            Files.deleteIfExists(fullPath);
                        }
                        res.accept(null);
                    } catch (IOException e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    @Override
    public AsyncTask<List<String>> listAll() {
        return NGEPlatform
            .get()
            .promisify(
                (res, rej) -> {
                    try {
                        List<String> files = Files
                            .walk(basePath)
                            .filter(Files::isRegularFile)
                            .map(basePath::relativize)
                            .map(Path::toString)
                            .toList();
                        res.accept(files);
                    } catch (IOException e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    /**
     * An OutputStream that writes to a temporary file and atomically moves it
     * to the target location on close, preventing corruption if the process is
     * interrupted or crashes during write.
     */
    private static class SafeFileOutputStream extends OutputStream {

        private final Path targetPath;
        private final Path tempPath;
        private final FileOutputStream tempStream;
        private boolean closed = false;

        public SafeFileOutputStream(Path targetPath) throws IOException {
            this.targetPath = targetPath;
            Files.createDirectories(targetPath.getParent());
            this.tempPath = Files.createTempFile(targetPath.getParent(), "vstore", ".tmp");
            this.tempStream = new FileOutputStream(tempPath.toFile());
        }

        @Override
        public void write(int b) throws IOException {
            tempStream.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            tempStream.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            tempStream.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            tempStream.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            // Ensure all data is written to disk
            tempStream.flush();
            tempStream.getFD().sync(); // Force sync to disk
            tempStream.close();

            // Atomic move
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            closed = true;
        }
    }
}
