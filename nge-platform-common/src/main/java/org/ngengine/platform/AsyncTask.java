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

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface AsyncTask<T> {
    void cancel();
    boolean isDone();
    boolean isFailed();
    boolean isSuccess();

    T await() throws Exception;

    <R> AsyncTask<R> then(ThrowableFunction<T, R> func2);

    <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> func2);
    AsyncTask<T> catchException(Consumer<Throwable> func2);

    /**
     * Blocks the current thread and awaits all the provided AsyncTask instances to complete and returns a list of their results.
     * If any of the AsyncTask instances fails, an exception is thrown.
     *
     * @param <T> the type of the result
     * @param tasks the list of AsyncTask instances
     * @return a list of results
     * @throws Exception if any of the AsyncTask instances fails
     */
    static <T> List<T> awaitAll(List<AsyncTask<T>> tasks) throws Exception {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAll(tasks).await();
    }

    /**
     * Blocks the current thread and awaits any of the provided AsyncTask instances to complete and returns its result.
     * If all the AsyncTask instances fail, an exception is thrown.
     * @param <T> the type of the result
     * @param tasks the list of AsyncTask instances
     * @return the result of one of the provided AsyncTask instances
     * @throws Exception if all the AsyncTask instances fail
     */
    static <T> T awaitAny(List<AsyncTask<T>> tasks) throws Exception {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAny(tasks).await();
    }

    /**
     * Blocks the current thread and awaits all the provided AsyncTask instances to complete (either resolved or failed) and returns a list of their results.
     * @param <T> the type of the result
     * @param tasks the list of AsyncTask instances
     * @return a list of AsyncTask instances that are either resolved or failed
     * @throws Exception if the waiting is interrupted
     */
    static <T> List<AsyncTask<T>> awaitAllSettled(List<AsyncTask<T>> tasks) throws Exception {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAllSettled(tasks).await();
    }

    /**
     * Returns an AsyncTask that resolved when all of the provided AsyncTask instances resolve, or fails if any of the provided AsyncTask instances fails.
     * @param <T> the type of the result
     * @param tasks the list of AsyncTask instances
     * @return an AsyncTask that resolves to a list of results
     */
    static <T> AsyncTask<List<T>> all(List<AsyncTask<T>> tasks) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAll(tasks);
    }

    /**
     * Returns an AsyncTask that resolves as soon as any of the provided AsyncTask instances resolves, or fails if all of the provided AsyncTask instances fail.
     * @param <T> the type of the result
     * @param tasks the list of AsyncTask instances
     * @return an AsyncTask that resolves to the result of one of the provided AsyncTask instances
     */
    static <T> AsyncTask<T> any(List<AsyncTask<T>> tasks) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAny(tasks);
    }

    /**
     * Returns an AsyncTask that resolves when all of the provided AsyncTask
     * instances have settled (either resolved or failed).
     *
     * @param <T>   the type of the result
     * @param tasks the list of AsyncTask instances
     * @return an AsyncTask that resolves to a list of AsyncTask instances that are
     *         either resolved or failed
     */
    static <T> AsyncTask<List<AsyncTask<T>>> allSettled(List<AsyncTask<T>> tasks) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.awaitAllSettled(tasks);
    }

    /**
     * Returns an already completed AsyncTask with the given value.
     * @param <T> the type of the result
     * @param value the value to complete the AsyncTask with
     * @return  an already completed AsyncTask
     */
    static <T> AsyncTask<T> completed(T value) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.wrapPromise((res, rej) -> res.accept(value));
    }

    /**
     * Returns an already failed AsyncTask with the given error.
     * @param <T> the type of the result
     * @param error the error to fail the AsyncTask with
     * @return an already failed AsyncTask
     */
    static <T> AsyncTask<T> failed(Throwable error) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.wrapPromise((res, rej) -> rej.accept(error));
    }


    /**
     * Create an AsyncTask wrapper around some asynchronous code.
     * @param <T> the type of the result
     * @param func the function that receives two parameters: a resolve function and a reject function. The resolve function must be called when the asynchronous operation completes successfully, and the reject function must be called when the asynchronous operation fails.
     * @return an AsyncTask that represents the asynchronous operation
     */
    public static <T> AsyncTask<T> create(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.wrapPromise(func);
    }

    /**
     * Same as {@link #create(BiConsumer)} but the function is executed in the provided executor.
     * @param <T> the type of the result
     * @param func the function that receives two parameters: a resolve function and a reject function. The resolve function must be called when the asynchronous operation completes successfully, and the reject function must be called when the asynchronous operation fails.
     * @param executor the executor to run the function in
     * @return an AsyncTask that represents the asynchronous operation  
     */
    public static <T> AsyncTask<T> create(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor) {
        NGEPlatform platform = NGEPlatform.get();
        return platform.promisify(func, executor);
    }
}
