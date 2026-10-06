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
package org.ngengine.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VStore {

    private static final Logger logger = Logger.getLogger(VStore.class.getName());

    /** Maximum complete value accepted by conditional creation; existing write APIs are unchanged. */
    public static final int MAX_CREATE_IF_ABSENT_BYTES = 64 * 1024;

    private enum CreateDispatch { QUEUED, STARTED, ABANDONED }

    public interface VStoreBackend {
        AsyncTask<InputStream> read(String path);

        AsyncTask<OutputStream> write(String path);

        /**
         * Atomically publishes a complete value only if no target entry exists. The input remains
         * owned by the caller until the returned private task settles; implementations must not
         * retain it afterwards. Create-capable backends must return a task that supports
         * {@link AsyncTask#observeCompletion} so completion can be forwarded without executor
         * submission. Unsupported backends must fail, never emulate exists-then-write.
         */
        default AsyncTask<Boolean> createIfAbsent(String path, byte[] completeValue) {
            return AsyncTask.failed(new UnsupportedOperationException("Conditional store creation unsupported"));
        }

        AsyncTask<Boolean> exists(String path);

        AsyncTask<Void> delete(String path);
        AsyncTask<List<String>> listAll();
    }

    private final VStoreBackend backend;

    public VStore(VStoreBackend backend) {
        this.backend = backend;
    }

    public AsyncTask<InputStream> read(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .read(path)
                    .then(v -> {
                        res.accept(v);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .write(path)
                    .then(v -> {
                        res.accept(v);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    /**
     * Creates a complete value without replacing any existing target entry. True means this call
     * published the value; false means an entry already exists. IO failures remain failures, and a
     * failed call may have published a complete value before a subsequent durability/cleanup error.
     *
     * The value is copied before queueing and limited to {@link #MAX_CREATE_IF_ABSENT_BYTES} bytes.
     * Only the private copy is erased after backend completion. Cancelling the returned view does
     * not cancel the private publication, which may still commit. This is ordinary desktop storage,
     * not a vault against privileged processes or host access-control administration.
     * Platforms without direct task completion observation are rejected. An observation failure
     * abandons work that has not started; an already-started publication retains its private copy
     * until the backend settles.
     */
    public AsyncTask<Boolean> createIfAbsent(String path, byte[] completeValue) {
        Objects.requireNonNull(path, "Store path required");
        Objects.requireNonNull(completeValue, "Complete store value required");
        if (completeValue.length > MAX_CREATE_IF_ABSENT_BYTES) {
            throw new IllegalArgumentException("Conditional store value exceeds limit");
        }
        // Reject uniformly unsupported platforms early; the actual queued task may differ.
        AsyncTask.completed(null).observeCompletion(ignored -> {}, ignored -> {});
        byte[] snapshot = Arrays.copyOf(completeValue, completeValue.length);
        AtomicReference<CreateDispatch> dispatch = new AtomicReference<>(CreateDispatch.QUEUED);
        AtomicBoolean settled = new AtomicBoolean();
        Runnable abandonBeforeStart = () -> {
            // Claim exclusive ownership before erasing. Dispatch must win the opposite transition.
            if (dispatch.compareAndSet(CreateDispatch.QUEUED, CreateDispatch.ABANDONED)) {
                Arrays.fill(snapshot, (byte) 0);
            }
        };
        try {
            AsyncTask<Boolean> privateTask = NGEPlatform.get().getVStoreQueue().enqueue((resolve, reject) -> {
                if (!dispatch.compareAndSet(CreateDispatch.QUEUED, CreateDispatch.STARTED)) {
                    reject.accept(new IllegalStateException("Conditional store creation abandoned before dispatch"));
                    return;
                }
                AsyncTask<Boolean> publication;
                try {
                    publication = Objects.requireNonNull(backend.createIfAbsent(path, snapshot));
                } catch (Throwable failure) {
                    Arrays.fill(snapshot, (byte) 0);
                    settled.set(true);
                    reject.accept(failure);
                    return;
                }
                try {
                    publication.observeCompletion(created -> {
                        if (settled.compareAndSet(false, true)) {
                            Arrays.fill(snapshot, (byte) 0);
                            if (created == null) reject.accept(new NullPointerException("Conditional store result required"));
                            else resolve.accept(created);
                        }
                    }, failure -> {
                        if (settled.compareAndSet(false, true)) {
                            Arrays.fill(snapshot, (byte) 0);
                            reject.accept(failure);
                        }
                    });
                } catch (Throwable unsupportedObservation) {
                    // An unsupported observer is an error, never an executor-affine approximation.
                    // Do not erase memory still owned by a backend whose completion is unobservable.
                    if (publication.isDone() && settled.compareAndSet(false, true)) Arrays.fill(snapshot, (byte) 0);
                    reject.accept(unsupportedObservation);
                }
            });
            privateTask.observeCompletion(ignored -> {}, failure -> abandonBeforeStart.run());
            // A separate no-affinity promise keeps caller cancellation away from the private queue.
            return NGEPlatform.get().wrapPromise((resolve, reject) -> {
                try {
                    privateTask.observeCompletion(resolve, failure -> {
                        abandonBeforeStart.run();
                        reject.accept(failure);
                    });
                } catch (Throwable failure) {
                    abandonBeforeStart.run();
                    reject.accept(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            abandonBeforeStart.run();
            throw failure;
        }
    }

    public AsyncTask<Boolean> exists(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .exists(path)
                    .then(v -> {
                        res.accept(v);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<Void> delete(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .delete(path)
                    .then(v -> {
                        res.accept(v);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<List<String>> listAll() {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .listAll()
                    .then(v -> {
                        res.accept(v);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<Void> writeFully(String path, byte data[]) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .write(path)
                    .then(out -> {
                        try (OutputStream output = out) {
                            output.write(data);
                        } catch (Throwable error) {
                            rej.accept(error);
                            return null;
                        }
                        res.accept(null);
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<byte[]> readFully(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                backend
                    .read(path)
                    .then(in -> {
                        try {
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            while ((bytesRead = in.read(buffer)) != -1) {
                                bos.write(buffer, 0, bytesRead);
                            }
                            res.accept(bos.toByteArray());
                        } catch (Exception e) {
                            rej.accept(e);
                        } finally {
                            try {
                                in.close();
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Error closing input stream", e);
                            }
                        }
                        return null;
                    })
                    .catchException(rej);
            });
    }
}
