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
package org.ngengine.platform.teavm;

import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.teavm.jso.JSObject;

/**
 * Reuses idle cooperative threads without creating a zero-delay timer per job.
 * Jobs still execute inside TeaVM threads, so blocking await calls can suspend.
 * A suspended job does not prevent another job from starting on another worker.
 */
final class TeaVMAsyncExecutor implements AsyncExecutor {

    private static final int MAX_IDLE_WORKERS = 4;
    private final TeaVMPlatform platform;
    private final ArrayDeque<Worker> idle = new ArrayDeque<>();
    private boolean closed;

    TeaVMAsyncExecutor(TeaVMPlatform platform) {
        this.platform = platform;
    }

    private final class Worker implements Runnable {

        Runnable job;
        JSObject signal;

        @Override
        public void run() {
            while (true) {
                Runnable current = job;
                job = null;
                if (current != null) current.run();
                if (closed || idle.size() >= MAX_IDLE_WORKERS) return;
                signal = TeaVMBinds.newPromise();
                idle.addLast(this);
                try {
                    TeaVMBinds.getPromise(signal).await();
                } catch (Exception failure) {
                    idle.remove(this);
                    throw new IllegalStateException("Worker wakeup failed", failure);
                }
                signal = null;
            }
        }
    }

    @Override
    public <T> AsyncTask<T> run(Callable<T> task) {
        if (closed) {
            return platform.wrapPromise((resolve, reject) ->
                reject.accept(new IllegalStateException("Executor already shutdown"))
            );
        }
        return platform.wrapPromise((resolve, reject) -> {
            Runnable job = () -> {
                try {
                    resolve.accept(task.call());
                } catch (Throwable failure) {
                    reject.accept(failure);
                }
            };
            Worker worker = idle.pollFirst();
            if (worker == null) {
                worker = new Worker();
                worker.job = job;
                Thread thread = new Thread(worker);
                thread.setName("TeaVM Executor");
                thread.start();
            } else {
                worker.job = job;
                JSObject signal = worker.signal;
                // Use a macrotask to yield to browser I/O and rendering. Only the
                // private wakeup runs in the JS callback; user code resumes in its
                // managed thread through the native promise continuation.
                TeaVMBinds.runSoon(() -> TeaVMBinds.resolvePromise(signal));
            }
        });
    }

    @Override
    public <T> AsyncTask<T> runLater(Callable<T> task, long delay, TimeUnit unit) {
        long delayMs = unit.toMillis(delay);
        if (delayMs == 0) return run(task);
        return run(() -> {
            TeaVMBinds.delayPromise(NGEUtils.safeInt(delayMs)).await();
            return task.call();
        });
    }

    @Override
    public void close() {
        closed = true;
        Worker worker;
        while ((worker = idle.pollFirst()) != null) TeaVMBinds.resolvePromise(worker.signal);
    }
}
