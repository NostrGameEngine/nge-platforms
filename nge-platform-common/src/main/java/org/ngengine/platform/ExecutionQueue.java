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

public class ExecutionQueue implements Closeable {

    private AtomicReference<AsyncTask> queue = new AtomicReference(null);
    private final AsyncExecutor executor;
    private final Runnable close;

    public ExecutionQueue() {
        executor = NGEUtils.getPlatform().newAsyncExecutor();
        close = NGEPlatform.get().registerFinalizer(this, () -> executor.close());
    }

    @SuppressWarnings("unchecked")
    protected <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> runnable) {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            // synchronized(queue){
            //     AsyncTask<T> nq;
            //     if(queue.get()==null){
            //         nq=platform.promisify(runnable,executor);
            //         queue.set(nq);
            //     }else{
            //         AsyncTask<T> q=queue.get();
            //         nq=q.compose((r -> {
            //             return platform.wrapPromise(runnable);
            //         }));
            //         queue.set(nq);
            //     }
            //     nq.then(r -> {
            //         res.accept(r);
            //         return null;
            //     });
            //     nq.catchException(ex -> {
            //         rej.accept(ex);
            //     });
            // }
            synchronized (queue) {
                AsyncTask<T> q = queue.updateAndGet(current -> {
                    if (current == null) {
                        current =
                            platform.promisify(
                                (rs, rj) -> {
                                    rs.accept(null);
                                },
                                executor
                            );
                    }
                    current =
                        current.compose(
                            (
                                r -> {
                                    return platform.wrapPromise(runnable);
                                }
                            )
                        );
                    return current;
                });
                q.then(r -> {
                    res.accept(r);
                    return null;
                });
                q.catchException(ex -> {
                    rej.accept(ex);
                });
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
