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


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.VStore;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;
import org.teavm.jso.JSBody;
import org.teavm.jso.ajax.ReadyStateChangeHandler;
import org.teavm.jso.ajax.XMLHttpRequest;

public class TeaVMPlatform extends NGEPlatform {

    @JSBody(script = "return window.nostr4j_jsBinds;")
    private static native TeaVMBinds getBinds();

    @Override
    public byte[] generatePrivateKey()  {
        return getBinds().generatePrivateKey();
    }

    @Override
    public byte[] genPubKey(byte[] secKey)  {
        return getBinds().genPubKey(secKey);
    }

    @Override
    public String toJSON(Object obj) {
        return getBinds().toJSON(obj);
    }

    @Override
    public <T> T fromJSON(String json, Class<T> claz)  {
        return (T) getBinds().fromJSON(json);
    }

    @Override
    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) {
        return getBinds().secp256k1SharedSecret(privKey, pubKey);
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2) {
        return getBinds().hmac(key, data1, data2);
    }

    @Override
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) {
        return getBinds().hkdf_extract(salt, ikm);
    }

    @Override
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length){
        return getBinds().hkdf_expand(prk, info, length);
    }

    @Override
    public String base64encode(byte[] data)  {
        return getBinds().base64encode(data);
    }

    @Override
    public byte[] base64decode(String data)  {
        return getBinds().base64decode(data);
    }

    @Override
    public byte[] chacha20(
            byte[] key,
            byte[] nonce,
            byte[] data,
            boolean forEncryption)  {
        return getBinds().chacha20(key, nonce, data);
    }

    @Override
    public String sha256(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] hash = getBinds().sha256(bytes);
        return NGEUtils.bytesToHex(hash);
    }

    @Override
    public byte[] sha256(byte[] data) {
        return getBinds().sha256(data);
    }

    @Override
    public String sign(String data, byte priv[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = getBinds().sign(dataB, priv);
        return NGEUtils.bytesToHex(sig);
    }

    @Override
    public boolean verify(String data, String sign, byte pub[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = NGEUtils.hexToByteArray(sign);
        return getBinds().verify(dataB, pub, sig);
    }

    @Override
    public byte[] randomBytes(int n) {
        return getBinds().randomBytes(n);
    }

    @Override
    public long getTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    @Override
    public <T> AsyncTask<T> promisify(
            BiConsumer<Consumer<T>, Consumer<Throwable>> func,
            AsyncExecutor executor) {
        TeaVMBinds.TeaVMPromise<T> promise = new TeaVMBinds.TeaVMPromise<>();

        func.accept(promise::resolve, promise::reject);

        return new AsyncTask<T>() {
            @Override
            public T await() throws InterruptedException, ExecutionException {
                if (!promise.completed) {
                    throw new UnsupportedOperationException(
                            "Blocking await() is not supported in TeaVM");
                }

                if (promise.failed) {
                    if (promise.error instanceof Exception) {
                        throw new ExecutionException( promise.error);
                    } else {
                        throw new ExecutionException(promise.error);
                    }
                }

                return promise.result;
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
            public <R> AsyncTask<R> then(Function<T, R> func2) {
                return promisify((res, rej) -> {
                    promise
                            .then(result -> {
                                try {
                                    res.accept(func2.apply(result));
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                            })
                            .catchError(rej::accept);
                }, executor);
            }

            @Override
            public AsyncTask<T> catchException(Consumer<Throwable> func2) {
                promise.catchError(func2);
                return this;
            }

            @Override
            public <R> AsyncTask<R> compose(Function<T, AsyncTask<R>> func2) {
                return promisify((res, rej) -> {
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
                }, executor);
            }

            @Override
            public void cancel() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException("Unimplemented method 'cancel'");
            }
        };
    }

    @Override
    public <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
        return (AsyncTask<T>) promisify(func, null);
    }

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

    private AsyncExecutor newJsExecutor() {
        return new AsyncExecutor() {
            @Override
            public <T> AsyncTask<T> run(Callable<T> r) {
                return wrapPromise((res, rej) -> {
                    // Execute on the next event loop cycle
                    getBinds()
                            .setTimeout(
                                    () -> {
                                        try {
                                            res.accept(r.call());
                                        } catch (Exception e) {
                                            rej.accept(e);
                                        }
                                    },
                                    0);
                });
            }

            @Override
            public <T> AsyncTask<T> runLater(
                    Callable<T> r,
                    long delay,
                    TimeUnit unit) {
                long delayMs = unit.toMillis(delay);

                if (delayMs == 0) {
                    return run(r);
                }

                return wrapPromise((res, rej) -> {
                    getBinds()
                            .setTimeout(
                                    () -> {
                                        try {
                                            res.accept(r.call());
                                        } catch (Exception e) {
                                            rej.accept(e);
                                        }
                                    },
                                    (int) delayMs);
                });
            }

            @Override
            public void close() {
                 
            }
        };
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
        return promisify((res, rej) -> {
            try {
                res.accept(sign(data, privKey));
            } catch (Exception e) {
                rej.accept(e);
            }
        }, null);
    }

    @Override
    public AsyncTask<Boolean> verifyAsync(String data, String sign, byte pubKey[]) {
        return promisify((res, rej) -> {
            try {
                res.accept(verify(data, sign, pubKey));
            } catch (Exception e) {
                rej.accept(e);
            }
        }, null);
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
    public AsyncTask<String> httpGet(String url, Duration timeout, Map<String, String> headers) {
        // TODO: timeout
        return wrapPromise((res, rej) -> {
            try {
                XMLHttpRequest xhr = XMLHttpRequest.create();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    xhr.setRequestHeader(entry.getKey(), entry.getValue());
                }
                xhr.open("GET", url);
                xhr.setOnReadyStateChange(
                        new ReadyStateChangeHandler() {
                            @Override
                            public void stateChanged() {
                                if (xhr.getReadyState() == XMLHttpRequest.DONE) {
                                    int status = xhr.getStatus();
                                    if (status >= 200 && status < 300) {
                                        res.accept(xhr.getResponseText());
                                    } else {
                                        rej.accept(
                                                new IOException(
                                                        "HTTP error: " +
                                                                status +
                                                                " " +
                                                                xhr.getStatusText()));
                                    }
                                }
                            }
                        });

                xhr.send();
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public void setClipboardContent(String data) {
        getBinds().setClipboardContent(data);
    }

    @Override
    public String getClipboardContent() {
        return getBinds().getClipboardContent();
    }

    @Override
    public AsyncTask<byte[]> httpGetBytes(String url, Duration timeout, Map<String, String> headers) {
        // TODO
        throw new UnsupportedOperationException("Unimplemented method 'newRTCTransport'");
    }

    @Override
    public RTCTransport newRTCTransport(RTCSettings settings, String connId, Collection<String> stunServers) {
        // TODO: Implement RTCTransport for TeaVM
        throw new UnsupportedOperationException("Unimplemented method 'newRTCTransport'");
    }


    @Override
    public void openInWebBrowser(String url) {
        try {
            // getBinds().openInWebBrowser(url);
        } catch (Exception e) {
        }
    }

    @Override
    public byte[] scrypt(byte[] P, byte[] S, int N, int r, int p2, int dkLen) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'scrypt'");
    }

    @Override
    public byte[] xchacha20poly1305(byte[] key, byte[] nonce, byte[] data, byte[] associatedData,
            boolean forEncryption) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'xchacha20poly1305'");
    }

    @Override
    public String nfkc(String str) {
        
        throw new UnsupportedOperationException("Unimplemented method 'nfkc'");
    }

    



    @Override
    public VStore getDataStore(String appName, String storeName) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDataStore'");
    }

    @Override
    public VStore getCacheStore(String appName, String cacheName) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getCacheStore'");
    }

    
}
