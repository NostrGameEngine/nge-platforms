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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;

public abstract class NGEPlatform {

    private static volatile BrowserInterceptor browserInterceptor;
    private static volatile VStoreInterceptor storeInterceptor;
    private static volatile NGEPlatform platform;
    private static final Logger logger = Logger.getLogger(NGEPlatform.class.getName());

    public static void set(NGEPlatform platform) {
        NGEPlatform.platform = platform;
    }

    public static NGEPlatform get() {
        if (NGEPlatform.platform == null) { // DCL
            synchronized (NGEPlatform.class) {
                if (NGEPlatform.platform == null) {
                    logger.warning("Platform not set, using default JVM platform.");
                    String defaultPlatformClass = "org.ngengine.platform.jvm.JVMAsyncPlatform";
                    try {
                        Class<?> clazz = Class.forName(defaultPlatformClass);
                        NGEPlatform.platform = (NGEPlatform) clazz.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load default platform: " + defaultPlatformClass, e);
                    }
                }
            }
        }
        return NGEPlatform.platform;
    }

    public static void setBrowserInterceptor(BrowserInterceptor interceptor) {
        NGEPlatform.browserInterceptor = interceptor;
    }

    public static BrowserInterceptor getBrowserInterceptor() {
        return NGEPlatform.browserInterceptor;
    }

    public static void setStoreInterceptor(VStoreInterceptor interceptor) {
        NGEPlatform.storeInterceptor = interceptor;
    }

    public static VStoreInterceptor getStoreInterceptor() {
        return NGEPlatform.storeInterceptor;
    }

    public abstract byte[] generatePrivateKey();

    public abstract byte[] genPubKey(byte[] secKey);

    public abstract String toJSON(Collection obj);

    public abstract String toJSON(Map obj);

    public abstract <T> T fromJSON(String json, Class<T> claz);

    public abstract byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey);

    public abstract byte[] hmac(byte[] key, byte[] data1, byte[] data2);

    public abstract byte[] hkdf_extract(byte[] salt, byte[] ikm);

    public abstract byte[] hkdf_expand(byte[] prk, byte[] info, int length);

    public abstract String base64encode(byte[] data);

    public abstract byte[] base64decode(String data);

    public abstract byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption);

    public abstract WebsocketTransport newTransport();

    public abstract RTCTransport newRTCTransport(RTCSettings settings, String connId, Collection<String> stunServers);

    public abstract String sha256(String data);

    public abstract byte[] sha256(byte[] data);

    public abstract String sign(String data, byte privKey[]) throws FailedToSignException;

    public abstract boolean verify(String data, String sign, byte pubKey[]);

    public abstract AsyncTask<String> signAsync(String data, byte privKey[]);

    public abstract AsyncTask<Boolean> verifyAsync(String data, String sign, byte pubKey[]);

    public abstract byte[] randomBytes(int n);

    public final AsyncExecutor newAsyncExecutor() {
        return newAsyncExecutor(null);
    }

    public abstract AsyncExecutor newAsyncExecutor(Object hint);

    /**
     * @deprecated use {@link #newAsyncExecutor(Object)} instead
     */
    @Deprecated
    public final AsyncExecutor newRelayExecutor() {
        return newAsyncExecutor("relay");
    }

    /**
     * @deprecated use {@link #newAsyncExecutor(Object)} instead
     */
    @Deprecated
    public final AsyncExecutor newSubscriptionExecutor() {
        return newAsyncExecutor("sub");
    }

    /**
     * @deprecated use {@link #newAsyncExecutor(Object)} instead
     */
    @Deprecated
    public final AsyncExecutor newSignerExecutor() {
        return newAsyncExecutor("signer");
    }

    /**
     * @deprecated use {@link #newAsyncExecutor(Object)} instead
     */
    @Deprecated
    public final AsyncExecutor newPoolExecutor() {
        return newAsyncExecutor("pool");
    }

    public abstract <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor);

    public abstract <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func);

    /**
     * Waits for all promises to resolve.
     * <p>
     * If one of the promises fails, the returned promise is fails with the same error.
     * </p>
     *
     * @param <T> the type of the promises
     * @param promises the list of promises
     * @return a promise that resolves to a list of results
     */
    public <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.size() == 0) {
                res.accept(new ArrayList<>());
                return;
            }

            AtomicInteger count = new AtomicInteger(promises.size());
            List<T> results = new ArrayList<>(count.get());
            for (int i = 0; i < count.get(); i++) {
                results.add(null);
            }

            for (int i = 0; i < promises.size(); i++) {
                final int index = i;
                AsyncTask<T> promise = promises.get(i);

                promise
                    .catchException(e -> {
                        logger.log(Level.WARNING, "Error in awaitAll", e);
                        rej.accept(e);
                    })
                    .then(result -> {
                        int remaining = count.decrementAndGet();
                        synchronized (results) {
                            results.set(index, result);
                        }
                        if (remaining == 0) {
                            res.accept(results);
                        }
                        return null;
                    });
            }
        });
    }

    /**
     * Waits for any promise to resolve.
     * @param <T> the type of the promises
     * @param promises the list of promises
     * @return a promise that resolves to the result of the first resolved promise
     *
     * <p>
     * If all promises fail, the returned promise fails with an exception, otherwise it resolves
     * with the result of the first resolved promise.
     * </p>
     */
    public <T> AsyncTask<T> awaitAny(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.size() == 0) {
                res.accept(null);
                return;
            }
            AtomicInteger count = new AtomicInteger(promises.size());
            AtomicBoolean resolved = new AtomicBoolean(false);
            for (int i = 0; i < promises.size(); i++) {
                AsyncTask<T> promise = promises.get(i);
                promise
                    .catchException(e -> {
                        logger.log(Level.WARNING, "Error in awaitAny " + e);
                        int remaining = count.decrementAndGet();
                        if (remaining == 0) {
                            rej.accept(new Exception("All promises failed"));
                        }
                    })
                    .then(result -> {
                        if (!resolved.getAndSet(true)) {
                            res.accept(result);
                        }
                        return null;
                    });
            }
        });
    }

    /**
     * Waits for any promise to resolve and match the filter.
     * Same as awaitAny but with a filter that the result must match.
     * If the result does not match the filter, it is ignored and the next promise is
     * waited for.
     *
     * <p>
     * If all promises fail or none match the filter, the returned promise fails with an exception,
     * otherwise it resolves with the result of the first resolved promise that matches the filter.
     * </p>
     *
     * @param <T>  the type of the promises
     * @param promises the list of promises
     * @param filter the filter to match
     * @return a promise that resolves to the result of the first resolved promise that matches the filter
     */
    public <T> AsyncTask<T> awaitAny(List<AsyncTask<T>> promises, Predicate<T> filter) {
        return wrapPromise((res, rej) -> {
            if (promises.size() == 0) {
                res.accept(null);
                return;
            }

            AtomicInteger count = new AtomicInteger(promises.size());
            AtomicBoolean resolved = new AtomicBoolean(false);

            for (int i = 0; i < promises.size(); i++) {
                AsyncTask<T> promise = promises.get(i);
                promise
                    .catchException(e -> {
                        logger.log(Level.WARNING, "Error in awaitAny with filter", e);
                        int remaining = count.decrementAndGet();
                        if (remaining == 0) {
                            rej.accept(new Exception("All promises failed"));
                        }
                    })
                    .then(result -> {
                        if (!resolved.getAndSet(true)) {
                            if (filter.test(result)) {
                                res.accept(result);
                            } else {
                                int remaining = count.decrementAndGet();
                                if (remaining == 0) {
                                    rej.accept(new Exception("No promises matched the filter"));
                                }
                            }
                        }
                        return null;
                    });
            }
        });
    }

    /**
     * Awaits for all promises to settle (either resolve or reject).
     * The returned promise always resolves with the list of all promises.
     * @param <T> the type of the promises
     * @param promises the list of promises
     * @return a promise that resolves to the list of all promises
     */
    public <T> AsyncTask<List<AsyncTask<T>>> awaitAllSettled(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.size() == 0) {
                res.accept(new ArrayList<>());
                return;
            }
            AtomicInteger count = new AtomicInteger(promises.size());
            for (int i = 0; i < promises.size(); i++) {
                AsyncTask<T> promise = promises.get(i);
                promise
                    .catchException(e -> {
                        int remaining = count.decrementAndGet();
                        if (remaining == 0) {
                            res.accept(promises);
                        }
                    })
                    .then(result -> {
                        int remaining = count.decrementAndGet();
                        if (remaining == 0) {
                            res.accept(promises);
                        }
                        return null;
                    });
            }
        });
    }

    public abstract long getTimestampSeconds();

    public abstract <T> Queue<T> newConcurrentQueue(Class<T> claz);

    public AsyncTask<String> httpGet(String url, Duration timeout, Map<String, String> headers) {
        return wrapPromise((res, rej) -> {
            httpRequest("GET", url, null, timeout, headers)
                .then(r -> {
                    if (!r.status()) {
                        rej.accept(new IOException("HTTP error: " + r.statusCode()));
                    } else {
                        byte[] data = r.body();
                        res.accept(new String(data, StandardCharsets.UTF_8));
                    }
                    return null;
                })
                .catchException(e -> {
                    rej.accept(e);
                });
        });
    }

    /**
     * @deprecated use {@link #httpRequest(String, String, byte[], Duration, Map)} instead
     */
    @Deprecated
    public AsyncTask<byte[]> httpGetBytes(String url, Duration timeout, Map<String, String> headers) {
        return wrapPromise((res, rej) -> {
            httpRequest("GET", url, null, timeout, headers)
                .then(r -> {
                    if (!r.status()) {
                        rej.accept(new IOException("HTTP error: " + r.statusCode()));
                    } else {
                        byte[] data = r.body();
                        res.accept(data);
                    }
                    return null;
                })
                .catchException(e -> {
                    rej.accept(e);
                });
        });
    }

    public abstract AsyncTask<NGEHttpResponse> httpRequest(
        String method,
        String inurl,
        byte[] body,
        Duration timeout,
        Map<String, String> headers
    );

    public abstract void setClipboardContent(String data);

    public abstract AsyncTask<String> getClipboardContent();

    public abstract void openInWebBrowser(String url);

    public abstract byte[] scrypt(byte[] P, byte[] S, int N, int r, int p, int dkLen);

    public abstract byte[] xchacha20poly1305(
        byte[] key,
        byte[] nonce,
        byte[] data,
        byte[] associatedData,
        boolean forEncryption
    );

    public abstract String nfkc(String str);

    public abstract VStore getDataStore(String appName, String storeName);

    public abstract VStore getCacheStore(String appName, String cacheName);

    public abstract Runnable registerFinalizer(Object obj, Runnable finalizer);

    private static final List<String> LOCAL_HOSTS = List.of("localhost", "localhost.localdomain", "local", "localdomain");

    /**
     * Checks if the given URI is a loopback address.
     * Polyfill for non jcl compliant platforms.
     *
     * This method should be overriden by a platform specific implementation
     * @param uri the URI to check
     * @return true if the URI is a loopback address, false otherwise
     */
    public boolean isLoopbackAddress(URI uri) {
        String host = uri.getHost().toLowerCase();
        if (
            host.isEmpty() ||
            LOCAL_HOSTS.contains(host) ||
            host.startsWith("127.") ||
            host.startsWith("0.") ||
            host.equals("::1") ||
            host.equalsIgnoreCase("0:0:0:0:0:0:0:1")
        ) {
            return true;
        }
        return false;
    }

    public abstract InputStream openResource(String resourceName) throws IOException;

    /**
     * Encrypts or decrypts data using AES-256 in CBC mode with PKCS7 padding.
     *
     * @param key           A 32-byte key for AES-256
     * @param iv            A 16-byte initialization vector
     * @param data          The data to encrypt or decrypt
     * @param forEncryption True for encryption, false for decryption
     * @return The encrypted or decrypted data
     */
    public abstract byte[] aes256cbc(byte[] key, byte[] iv, byte[] data, boolean forEncryption);

    private final AtomicReference<ExecutionQueue> vstoreQueue = new AtomicReference<>(null);

    public ExecutionQueue getVStoreQueue() {
        return vstoreQueue.updateAndGet(current -> {
            if (current == null) {
                return newExecutionQueue();
            }
            return current;
        });
    }

    public ExecutionQueue newExecutionQueue() {
        return new ExecutionQueue();
    }

    public ExecutionQueue newExecutionQueue(AsyncExecutor exc) {
        return new ExecutionQueue(exc);
    }

    /**
     * Run platform specific script
     */
    public void callFunction(String function, Object args, Consumer<Object> res, Consumer<Throwable> rej) {
        throw new UnsupportedOperationException("This platform does not support dynamic function calls");
    }

    public void canCallFunction(String function, Consumer<Boolean> res) {
        res.accept(false);
    }

    public abstract String getPlatformName();

    public void runInThread(Thread thread, Consumer<Runnable> enqueue, Runnable action) {
        Thread current = Thread.currentThread();
        if (thread.equals(current) && thread.getName().equals(current.getName())) {
            action.run();
        } else {
            enqueue.accept(action);
        }
    }
}
