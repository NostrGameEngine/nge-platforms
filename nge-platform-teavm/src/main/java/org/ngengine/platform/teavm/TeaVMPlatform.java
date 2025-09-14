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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

public class TeaVMPlatform extends NGEPlatform {

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

    class TeaVMPromise<T> {

        public T result;
        public Throwable error;
        public boolean completed = false;
        public boolean failed = false;
        private final List<Consumer<T>> thenCallbacks = new ArrayList<>();
        private final List<Consumer<Throwable>> catchCallbacks = new ArrayList<>();
        private Object monitor = new Object();

        public void resolve(T value) {
            if (!completed) {
                this.result = value;
                this.completed = true;
                for (Consumer<T> callback : thenCallbacks) {
                    callback.accept(value);
                }
            }
            synchronized (monitor) {
                monitor.notifyAll();
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
            }
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @JSExport
        public TeaVMPromise<T> then(Consumer<T> onFulfilled) {
            if (completed && !failed) {
                onFulfilled.accept(result);
            } else if (!completed) {
                thenCallbacks.add(onFulfilled);
            }
            return this;
        }

        @JSExport
        public TeaVMPromise<T> catchError(Consumer<Throwable> onRejected) {
            if (completed && failed) {
                onRejected.accept(error);
            } else if (!completed) {
                catchCallbacks.add(onRejected);
            }
            return this;
        }

        @JSExport
        public Object await() throws Exception {
            while (!this.completed && !this.failed) {
                synchronized (monitor) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Thread interrupted while waiting for promise resolution", e);
                    }
                }
            }
            if (this.failed) {
                throw new ExecutionException("Promise failed with error", this.error);
            }
            return this.result;
        }
     
    }

    @Override
    public <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor) {
        TeaVMPromise<T> promise = new TeaVMPromise<>();

        func.accept(promise::resolve, promise::reject);

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

    @Override
    public <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.isEmpty()) {
                res.accept(new ArrayList<>());
                return;
            }

            AtomicInteger count = new AtomicInteger(promises.size());
            List<T> results = new ArrayList<>(count.get());

            for (int i = 0; i < count.get(); i++) {
                results.add(null);
            }

            for (int i = 0; i < promises.size(); i++) {
                final int j = i;
                AsyncTask<T> p = promises.get(i);
                p
                    .catchException(e -> {
                        rej.accept(e);
                    })
                    .then(r -> {
                        results.set(j, r);
                        if (count.decrementAndGet() == 0) {
                            res.accept(results);
                        }
                        return null;
                    });
            }
        });
    }

    private class ExecutorThread implements Executor {

        private boolean closed = false;
        private List<Runnable> tasks = new LinkedList<>();
        private Thread threadPool[];

        public ExecutorThread(int n){
            threadPool = new Thread[n];
            for(int i=0; i<n; ++i){
                threadPool[i] = new Thread(this::run);
                threadPool[i].setName("TeaVMPlatform-ExecutorThread-"+i);
                threadPool[i].setDaemon(true);
            }
        }

        public void start(){
            for(Thread t : threadPool){
                t.start();
            }
        }



        public void run() {
            while (!closed) {
                synchronized (tasks) {
                    Runnable task = null;
                    if (!tasks.isEmpty()) {
                        task = tasks.remove(0);
                    }
                    if (task != null) {
                        try {
                            task.run();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    } else {
                        try {
                            tasks.wait(100);                        
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        @Override
        public void execute(Runnable command) {
            synchronized (tasks) {
                tasks.add(command);
                tasks.notifyAll();
            }
        }

        public void close() {
            closed = true;
            synchronized (tasks) {
                tasks.notifyAll();
            }
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
                    new Thread( // ensure its on the teavm suspendable context

                        () -> {
                            try{
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                rej.accept(e);
                                return;
                            }
                            run(r)
                                .then(result -> {
                                    res.accept(result);
                                    return null;
                                })
                                .catchException(exc -> {
                                    rej.accept(exc);
                                });
                        }
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
        return new LinkedList<T>();
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
            null
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
            null
        );
    }

    @Override
    public AsyncExecutor newAsyncExecutor(Object hint) {
        return newJsExecutor();
    }

    @Override
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

    @Override
    public void setClipboardContent(String data) {
        TeaVMBinds.setClipboardContent(data);
    }

    @Override
    public String getClipboardContent() {
        return TeaVMBindsAsync.getClipboardContent();
    }

    @Override
    public AsyncTask<NGEHttpResponse> httpRequest(
        String method,
        String inurl,
        byte[] body,
        Duration timeout,
        Map<String, String> headers
    ) {
        String url = NGEUtils.safeURI(inurl).toString();
        return wrapPromise((res, rej) -> {
            try {
                XMLHttpRequest xhr = new XMLHttpRequest();

                xhr.open(method.toUpperCase(), url, true);
                xhr.setResponseType("arraybuffer");

                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        xhr.setRequestHeader(entry.getKey(), entry.getValue());
                    }
                }

                xhr.setOnReadyStateChange(() -> {
                    int state = xhr.getReadyState();
                    if (state == XMLHttpRequest.DONE) {
                        int responseCode = xhr.getStatus();
                        if (responseCode == 0) {
                            responseCode = -1;
                        }

                        var array = new Int8Array((ArrayBuffer) xhr.getResponse());
                        byte[] bytes = new byte[array.getLength()];
                        for (int i = 0; i < bytes.length; ++i) {
                            bytes[i] = array.get(i);
                        }

                        int responseGroup = responseCode / 100;
                        if (responseGroup == 4 || responseGroup == 5) {
                            res.accept(
                                new NGEHttpResponse(responseCode, parseHeaders(xhr.getAllResponseHeaders()), bytes, false)
                            );
                        } else {
                            res.accept(
                                new NGEHttpResponse(responseCode, parseHeaders(xhr.getAllResponseHeaders()), bytes, true)
                            );
                        }
                    }
                });

                if (body != null) {
                    Int8Array array = new Int8Array(body.length);
                    for (int i = 0; i < body.length; ++i) {
                        array.set(i, body[i]);
                    }
                    xhr.send(array.getBuffer());
                } else {
                    xhr.send();
                }
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    private Map<String, List<String>> parseHeaders(String headers) {
        Map<String, List<String>> map = new HashMap<>();
        int index = 0;
        while (index < headers.length()) {
            int next = headers.indexOf("\r\n", index);
            if (next < 0) {
                next = headers.length();
            }

            int colon = headers.indexOf(':', index);
            if (colon < 0 || colon > next) {
                // No colon found, treat as invalid header line
                index = next + 2;
                continue;
            }

            String key = headers.substring(index, colon).trim();
            String value = headers.substring(colon + 1, next).trim();

            // Add to map, supporting multiple values per key
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);

            index = next + 2; // Move to start of next line
        }
        return map;
    }

    @Override
    public RTCTransport newRTCTransport(RTCSettings settings, String connId, Collection<String> stunServers) {
        TeaVMRTCTransport transport = new TeaVMRTCTransport();
        transport.start(settings, null, connId, stunServers);
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
        FinalizerCallback callable = TeaVMBinds.registerFinalizer(obj, ()->{
            new Thread(()->{
                finalizer.run();
            }).start();
        });
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
                rej.accept(err);
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
}
