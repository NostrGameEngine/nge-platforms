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

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExecutionQueue implements Closeable {

    private static final Logger logger = Logger.getLogger(ExecutionQueue.class.getName());

    private AtomicReference<AsyncTask<Void>> queue = new AtomicReference<>(null);
    private final AsyncExecutor executor;
    private final Runnable close;

    public ExecutionQueue() {
        this(null);
    }

    public ExecutionQueue(AsyncExecutor executor) {
        if (executor == null) {
            this.executor = NGEUtils.getPlatform().newAsyncExecutor();
            close = NGEPlatform.get().registerFinalizer(this, () -> this.executor.close());
        } else {
            this.executor = executor;
            close = () -> {};
        }
    }

    public <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> runnable) {
        NGEPlatform platform = NGEUtils.getPlatform();

        return platform.wrapPromise((res, rej) -> {
            synchronized (queue) {
                // Get or create the initial queue
                AsyncTask<Void> currentQueue = queue.updateAndGet(current -> {
                    if (current == null) {
                        // Create initial resolved promise
                        return platform.wrapPromise((resolve, reject) -> {
                            resolve.accept(null);
                        });
                    }
                    return current;
                });

                // Chain the new task to the queue
                AsyncTask<T> newTask = currentQueue.compose(ignored -> {
                    return platform.wrapPromise((_i0, _i1) -> {
                        runnable.accept(
                            r -> {
                                try {
                                    res.accept(r);
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                                _i0.accept(null);
                            },
                            e -> {
                                try {
                                    rej.accept(e);
                                } catch (Throwable ex) {
                                    logger.log(Level.SEVERE, "Error in task rejection", ex);
                                }
                                _i0.accept(null);
                            }
                        );
                    });
                });

                // Update the queue to point to the new task (cast to Void for queue management)
                queue.set(
                    newTask.compose(result -> {
                        return platform.wrapPromise((resolve, reject) -> {
                            resolve.accept(null);
                        });
                    })
                );
            }
        });
    }

    @Override
    public void close() throws IOException {
        AsyncTask task = queue.get();
        if (task != null) {
            task.cancel();
        }
        close.run();
    }
}
