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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.VStore;
import org.ngengine.platform.teavm.TeaVMBinds.FinalizerCallback;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.ngengine.platform.transport.NGEHttpResponseStream;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;
import org.teavm.jso.JSObject;

public class TeaVMPlatform extends NGEPlatform {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    private AsyncExecutor defaultExecutor = newAsyncExecutor();

    @Override
    public byte[] generatePrivateKey() {
        return TeaVMBinds.generatePrivateKey();
    }

    @Override
    public byte[] genPubKey(byte[] secKey) {
        return TeaVMBinds.genPubKey(secKey);
    }

    @Override
    public String toJSON(Collection obj) {
        return TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(obj));
    }

    @Override
    public String toJSON(Map obj) {
        return TeaVMBinds.toJSON(TeaVMJsConverter.toJSObject(obj));
    }

    @Override
    public <T> T fromJSON(String json, Class<T> claz) {
        JSObject jsObj = (JSObject) TeaVMBinds.fromJSON(json);
        return TeaVMJsConverter.toJavaObject(jsObj, claz);
    }

    @Override
    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) {
        return TeaVMBinds.secp256k1SharedSecret(privKey, pubKey);
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2) {
        return TeaVMBinds.hmac(key, data1, data2);
    }

    @Override
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) {
        return TeaVMBinds.hkdf_extract(salt, ikm);
    }

    @Override
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length) {
        return TeaVMBinds.hkdf_expand(prk, info, length);
    }

    @Override
    public String base64encode(byte[] data) {
        return TeaVMBinds.base64encode(data);
    }

    @Override
    public byte[] base64decode(String data) {
        return TeaVMBinds.base64decode(data);
    }

    @Override
    public byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption) {
        return TeaVMBinds.chacha20(key, nonce, data);
    }

    @Override
    public String sha256(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] hash = TeaVMBinds.sha256(bytes);
        return NGEUtils.bytesToHex(hash);
    }

    @Override
    public byte[] sha256(byte[] data) {
        return TeaVMBinds.sha256(data);
    }

    @Override
    public String sign(String data, byte priv[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = TeaVMBinds.sign(dataB, priv);
        return NGEUtils.bytesToHex(sig);
    }

    @Override
    public boolean verify(String data, String sign, byte pub[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = NGEUtils.hexToByteArray(sign);
        return TeaVMBinds.verify(dataB, pub, sig);
    }

    @Override
    public byte[] randomBytes(int n) {
        return TeaVMBinds.randomBytes(n);
    }

    @Override
    public long getTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    static class TeaVMPromise<T> {

        public T result;
        public Throwable error;
        public boolean completed = false;
        public boolean failed = false;
        private final List<Consumer<T>> thenCallbacks = new ArrayList<>();
        private final List<Consumer<Throwable>> catchCallbacks = new ArrayList<>();
        private int promiseId = -1;

        public TeaVMPromise() {
            StringBuilder stackTrace = new StringBuilder("StackTrace:\n");
            Exception e = new Exception();
            for (StackTraceElement ste : e.getStackTrace()) {
                stackTrace.append(ste.toString()).append("\n");
            }
            newPromise();
        }

        private void newPromise() {
            int promiseId = TeaVMBinds.newPromise();
            this.promiseId = promiseId;
            NGEPlatform
                .get()
                .registerFinalizer(
                    this,
                    () -> {
                        TeaVMBinds.freePromise(promiseId);
                    }
                );
        }

        public void resolve(T value) {
            if (!completed) {
                this.result = value;
                this.completed = true;
                for (Consumer<T> callback : thenCallbacks) {
                    callback.accept(value);
                }
                TeaVMBinds.resolvePromise(this.promiseId);
            }
        }

        public void reject(Throwable error) {
            if (!completed) {
                this.error = error;
                this.completed = true;
                this.failed = true;
                for (Consumer<Throwable> callback : catchCallbacks) {
                    callback.accept(error);
                }
                TeaVMBinds.rejectPromise(this.promiseId);
            }
        }

        public TeaVMPromise<T> then(Consumer<T> onFulfilled) {
            if (completed && !failed) {
                onFulfilled.accept(result);
            } else if (!completed) {
                thenCallbacks.add(onFulfilled);
            }
            return this;
        }

        public TeaVMPromise<T> catchError(Consumer<Throwable> onRejected) {
            if (completed && failed) {
                onRejected.accept(error);
            } else if (!completed) {
                catchCallbacks.add(onRejected);
            }
            return this;
        }

        public Object await() throws Exception {
            TeaVMBindsAsync.waitPromise(promiseId);
            if (this.failed) {
                throw new ExecutionException("Promise failed with error", this.error);
            }
            return this.result;
        }
    }

    @Override
    public <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor) {
        TeaVMPromise<T> promise = new TeaVMPromise<>();

        if (executor == null) {
            try {
                func.accept(promise::resolve, promise::reject);
            } catch (Throwable e) {
                promise.reject(e);
            }
        } else {
            executor.run(() -> {
                try {
                    func.accept(promise::resolve, promise::reject);
                } catch (Throwable e) {
                    promise.reject(e);
                }
                return null;
            });
        }

        return new AsyncTask<T>() {
            @Override
            public T await() throws Exception {
                return (T) promise.await();
            }

            @Override
            public boolean isDone() {
                return promise.completed;
            }

            @Override
            public boolean isFailed() {
                return promise.failed;
            }

            @Override
            public boolean isSuccess() {
                return promise.completed && !promise.failed;
            }

            @Override
            public <R> AsyncTask<R> then(ThrowableFunction<T, R> func2) {
                return promisify(
                    (res, rej) -> {
                        promise
                            .then(result -> {
                                try {
                                    res.accept(func2.apply(result));
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                            })
                            .catchError(rej::accept);
                    },
                    executor
                );
            }

            @Override
            public AsyncTask<T> catchException(Consumer<Throwable> func2) {
                promise.catchError(func2);
                return this;
            }

            @Override
            public <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> func2) {
                return promisify(
                    (res, rej) -> {
                        promise
                            .then(result -> {
                                try {
                                    try {
                                        AsyncTask<R> task2 = func2.apply(result);
                                        task2.catchException(exc -> {
                                            rej.accept(exc);
                                        });
                                        task2.then(r -> {
                                            res.accept(r);
                                            return null;
                                        });
                                    } catch (Throwable e) {
                                        rej.accept(e);
                                    }
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                            })
                            .catchError(rej::accept);
                    },
                    executor
                );
            }

            @Override
            public void cancel() {}
        };
    }

    @Override
    public <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
        return (AsyncTask<T>) promisify(func, null);
    }

    private class ExecutorThread implements Executor {

        private String name = "Executor";
        private final LinkedList<Runnable> tasks = new LinkedList<>();
        private volatile boolean running = true;

        public ExecutorThread(int n) {}

        public void start() {
            Thread t = new Thread(() -> {
                while (running) {
                    Runnable task = null;
                    synchronized (tasks) {
                        if (tasks.isEmpty()) {
                            try {
                                tasks.wait(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            continue;
                        }
                        if (!tasks.isEmpty()) {
                            task = tasks.removeFirst();
                        }
                    }
                    try {
                        if (task != null) task.run();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            });
            t.setName(name + " Worker");
            t.start();
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public void execute(Runnable command) {
            if (!running) throw new IllegalStateException("Executor already shutdown");
            Thread t = new Thread(() -> {
                synchronized (tasks) {
                    tasks.add(command);
                    tasks.notifyAll();
                }
            });
            t.setName(name + " Scheduler");
            t.start();
        }

        public void close() {
            running = false;
            Thread t = new Thread(() -> {
                synchronized (tasks) {
                    tasks.notifyAll();
                }
            });
            t.setName(name + " Closer");
            t.start();
            tasks.clear();
        }
    }

    private AsyncExecutor newJsExecutor() {
        ExecutorThread executorThread = new ExecutorThread(3);
        executorThread.start();

        AtomicReference<Runnable> closer = new AtomicReference<>();

        AsyncExecutor aexc = new AsyncExecutor() {
            @Override
            public <T> AsyncTask<T> run(Callable<T> r) {
                return wrapPromise((res, rej) -> {
                    executorThread.execute(() -> {
                        try {
                            res.accept(r.call());
                        } catch (Exception e) {
                            rej.accept(e);
                        }
                    });
                });
            }

            @Override
            public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit) {
                long delayMs = unit.toMillis(delay);

                if (delayMs == 0) {
                    return run(r);
                }

                return wrapPromise((res, rej) -> {
                    TeaVMBinds.setTimeout(
                        () -> {
                            run(r)
                                .then(result -> {
                                    res.accept(result);
                                    return null;
                                })
                                .catchException(exc -> {
                                    rej.accept(exc);
                                });
                        },
                        NGEUtils.safeInt(delayMs)
                    );
                });
            }

            @Override
            public void close() {
                closer.get().run();
            }
        };
        closer.set(registerFinalizer(aexc, () -> executorThread.close()));
        return aexc;
    }

    @Override
    public WebsocketTransport newTransport() {
        return new TeaVMWebsocketTransport(this);
    }

    @Override
    public <T> Queue<T> newConcurrentQueue(Class<T> claz) {
        return new LinkedBlockingDeque<T>();
    }

    @Override
    public AsyncTask<String> signAsync(String data, byte privKey[]) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(sign(data, privKey));
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            defaultExecutor
        );
    }

    @Override
    public AsyncTask<Boolean> verifyAsync(String data, String sign, byte pubKey[]) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(verify(data, sign, pubKey));
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            defaultExecutor
        );
    }

    @Override
    public AsyncExecutor newAsyncExecutor(Object hint) {
        return newJsExecutor();
    }

    @Override
    public void setClipboardContent(String data) {
        TeaVMBinds.setClipboardContent(data);
    }

    @Override
    public AsyncTask<String> getClipboardContent() {
        return promisify(
            (res, rej) -> {
                TeaVMBinds.getClipboardContentAsync(
                    result -> {
                        res.accept(result);
                    },
                    error -> {
                        rej.accept(new Exception(error));
                    }
                );
            },
            defaultExecutor
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public AsyncTask<NGEHttpResponse> httpRequest(
        String method,
        String inurl,
        byte[] body,
        Duration timeout,
        Map<String, String> headers
    ) {
        String url = NGEUtils.safeURI(inurl).toString();

        String reqHeaders = headers != null ? toJSON(headers) : null;

        byte[] reqBody = body != null ? body : new byte[0];

        return promisify(
            (res, rej) -> {
                TeaVMBinds.fetchAsync(
                    method,
                    url,
                    reqHeaders,
                    reqBody,
                    (int) ((timeout != null ? timeout : HTTP_TIMEOUT).toMillis()),
                    r -> {
                        try {
                            String jsonHeaders = r.getHeaders();
                            int statusCode = r.getStatus();

                            Map<String, List<String>> respHeaders = NGEPlatform.get().fromJSON(jsonHeaders, Map.class);
                            boolean status = statusCode >= 200 && statusCode < 300;
                            byte[] data = status ? r.getBody() : new byte[0];

                            NGEHttpResponse ngeResp = new NGEHttpResponse(statusCode, respHeaders, data, status);
                            res.accept(ngeResp);
                        } catch (Throwable e) {
                            rej.accept(e);
                        }
                    },
                    e -> {
                        rej.accept(new RuntimeException("Fetch error: " + e));
                    }
                );
            },
            defaultExecutor
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public AsyncTask<NGEHttpResponseStream> httpRequestStream(
            String method,
            String inurl,
            byte[] body,
            Duration timeout,
            Map<String, String> headers) {
        String url = NGEUtils.safeURI(inurl).toString();

        String reqHeaders = headers != null ? toJSON(headers) : null;

        byte[] reqBody = body != null ? body : new byte[0];

        return promisify((res, rej) -> {
            TeaVMBinds.fetchStreamAsync(
                    method,
                    url,
                    reqHeaders,
                    reqBody,
                    (int) ((timeout != null ? timeout : HTTP_TIMEOUT).toMillis()),
                    r -> {
                        try {
                            String jsonHeaders = r.getHeaders();
                            int statusCode = r.getStatus();

                            Map<String, List<String>> respHeaders = NGEPlatform.get().fromJSON(jsonHeaders, Map.class);
                            boolean status = statusCode >= 200 && statusCode < 300;
                            TeaVMReadableStreamWrapperInputStream is = new TeaVMReadableStreamWrapperInputStream(r.getBody());
                            NGEHttpResponseStream ngeResp = new NGEHttpResponseStream(statusCode, respHeaders, is, status);
                            res.accept(ngeResp);
                        } catch (Throwable e) {
                            rej.accept(e);
                        }
                    },
                    e -> {
                        rej.accept(new RuntimeException("Fetch error: " + e));
                    });
        }, defaultExecutor);
    }

    @Override
    public RTCTransport newRTCTransport(RTCSettings settings, String connId, Collection<String> stunServers) {
        TeaVMRTCTransport transport = new TeaVMRTCTransport();
        transport.start(settings, newAsyncExecutor(TeaVMRTCTransport.class), connId, stunServers);
        return transport;
    }

    @Override
    public void openInWebBrowser(String url) {
        TeaVMBinds.openURL(url);
    }

    @Override
    public byte[] scrypt(byte[] P, byte[] S, int N, int r, int p2, int dkLen) {
        return TeaVMBindsAsync.scrypt(P, S, N, r, p2, dkLen);
    }

    @Override
    public byte[] xchacha20poly1305(byte[] key, byte[] nonce, byte[] data, byte[] associatedData, boolean forEncryption) {
        return TeaVMBinds.xchacha20poly1305(key, nonce, data, associatedData, forEncryption);
    }

    @Override
    public String nfkc(String str) {
        return TeaVMBinds.nfkc(str);
    }

    @Override
    public VStore getDataStore(String appName, String storeName) {
        return new VStore(new IndexedDbVStore(appName + "-data-" + storeName));
    }

    @Override
    public VStore getCacheStore(String appName, String cacheName) {
        return new VStore(new IndexedDbVStore(appName + "-cache-" + cacheName));
    }

    @Override
    public Runnable registerFinalizer(Object obj, Runnable finalizer) {
        FinalizerCallback callable = TeaVMBinds.registerFinalizer(
            obj,
            () -> {
                new Thread(() -> {
                    finalizer.run();
                })
                    .start();
            }
        );
        return () -> {
            callable.call();
        };
    }

    @Override
    public InputStream openResource(String resourceName) throws IOException {
        if (!TeaVMBinds.hasBundledResource(resourceName)) {
            throw new IOException("Resource not found: " + resourceName);
        }
        byte[] data = TeaVMBinds.getBundledResource(resourceName);
        if (data == null) {
            throw new IOException("Resource not found: " + resourceName);
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        return inputStream;
    }

    @Override
    public byte[] aes256cbc(byte[] key, byte[] iv, byte[] data, boolean forEncryption) {
        return TeaVMBinds.aes256cbc(key, iv, data, forEncryption);
    }

    @Override
    public String getPlatformName() {
        return TeaVMBinds.getPlatformName();
    }

    @Override
    public void callFunction(String function, Object args, Consumer<Object> res, Consumer<Throwable> rej) {
        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("args", args);
        TeaVMBinds.callFunction(
            function,
            toJSON(argsMap),
            json -> {
                Map<String, Object> r = fromJSON(json.stringValue(), Map.class);
                res.accept(r.get("result"));
            },
            err -> {
                rej.accept(new Exception(err));
            }
        );
    }

    @Override
    public void canCallFunction(String function, Consumer<Boolean> res) {
        TeaVMBinds.canCallFunction(
            function,
            canjs -> {
                boolean can = canjs.booleanValue();
                if (can) {
                    res.accept(true);
                } else {
                    res.accept(false);
                }
            }
        );
    }

    @Override
    public void runInThread(Thread thread, Consumer<Runnable> enqueue, Runnable action) {
        enqueue.accept(action);
    }
}
