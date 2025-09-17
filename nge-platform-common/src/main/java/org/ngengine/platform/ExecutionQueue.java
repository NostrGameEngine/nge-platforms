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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExecutionQueue implements Closeable {

    private static final Logger logger = Logger.getLogger(ExecutionQueue.class.getName());

    private final AsyncExecutor executor;
    private final Runnable close;
    private volatile AsyncTask<Void> current = null;
    private final AtomicInteger leakGuard = new AtomicInteger(0);

    protected ExecutionQueue() {
        this(null);
    }

    protected ExecutionQueue(AsyncExecutor executor) {
        if (executor == null) {
            this.executor = NGEUtils.getPlatform().newAsyncExecutor(ExecutionQueue.class);
            close = NGEPlatform.get().registerFinalizer(this, () -> this.executor.close());
        } else {
            this.executor = executor;
            close = () -> {};
        }
        if (logger.isLoggable(Level.FINEST)) {
            debug();
        }
    }

    private void debug() {
        this.executor.runLater(
                () -> {
                    int lg = leakGuard.get();
                    logger.log(Level.WARNING, "ExecutionQueue: " + lg + " pending tasks");
                    if (logger.isLoggable(Level.FINEST)) {
                        debug();
                    }
                    return null;
                },
                60000,
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
    }

    public synchronized <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> runnable) {
        NGEPlatform platform = NGEUtils.getPlatform();

        return platform.wrapPromise((res, rej) -> {
            if (current == null) {
                current =
                    platform.wrapPromise((forward, _ignored) -> {
                        forward.accept(null);
                    });
            }

            current =
                current.compose(ignored -> {
                    return platform.wrapPromise((forward, _ignored) -> {
                        runnable.accept(
                            r -> {
                                try {
                                    res.accept(r);
                                } catch (Throwable e) {
                                    logger.log(Level.WARNING, "Error in task", e);
                                    rej.accept(e);
                                }
                                forward.accept(null);
                            },
                            e -> {
                                try {
                                    rej.accept(e);
                                } catch (Throwable ex) {
                                    logger.log(Level.SEVERE, "Error in task rejection", ex);
                                }
                                forward.accept(null);
                            }
                        );
                    });
                });

            if (logger.isLoggable(Level.FINEST)) {
                leakGuard.incrementAndGet();
                NGEPlatform
                    .get()
                    .registerFinalizer(
                        current,
                        () -> {
                            leakGuard.decrementAndGet();
                        }
                    );
            }
        });
    }

    @Override
    public synchronized void close() throws IOException {
        if (current != null) {
            current.cancel();
        }
        close.run();
    }
}
