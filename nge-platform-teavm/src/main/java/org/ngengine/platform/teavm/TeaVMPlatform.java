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
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEAllocator;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.ThrowableFunction;
import org.ngengine.platform.VStore;
import org.ngengine.platform.secp256k1.Secp256k1RecoverableSignature;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.ngengine.platform.transport.NGEHttpResponseStream;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;
import org.teavm.classlib.PlatformDetector;
import org.teavm.jso.JSObject;

public class TeaVMPlatform extends NGEPlatform {

    private static final NGEAllocator allocator = new TeaVMNGEAllocator();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    private static final Cleaner CLEANER = Cleaner.create();
    private AsyncExecutor defaultExecutor = newAsyncExecutor();

    @Override
    public NGEAllocator getNativeAllocator() {
        return allocator;
    }

    @Override
    public byte[] generatePrivateKey() {
        try {
            byte[] prikey = TeaVMBinds.generatePrivateKey();
            verifyRandomness(prikey, 32);
            return prikey;
        } catch (Exception e) {
            panic("Failed to generate private key: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public ByteBuffer generatePrivateKeyBuffer() {
        try {
            ByteBuffer output = allocateOutput(32);
            finishOutput(output, TeaVMBinds.generatePrivateKeyBuffer(output));
            verifyRandomness(output, 32);
            return output;
        } catch (Exception e) {
            panic("Failed to generate private key: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] genPubKey(byte[] secKey) {
        return TeaVMBinds.genPubKey(secKey);
    }

    @Override
    public ByteBuffer genPubKey(ByteBuffer secKey) {
        ByteBuffer output = allocateOutput(32);
        return finishOutput(output, TeaVMBinds.genPubKeyBuffer(directInput(secKey), output));
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

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> normalizeHttpHeaders(String jsonHeaders) {
        Map<String, Object> rawHeaders = NGEPlatform.get().fromJSON(jsonHeaders, Map.class);
        Map<String, List<String>> out = new HashMap<>();
        if (rawHeaders == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : rawHeaders.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            Object value = entry.getValue();
            List<String> values = new ArrayList<>();
            if (value instanceof List) {
                for (Object item : (List<Object>) value) {
                    if (item != null) {
                        values.add(String.valueOf(item));
                    }
                }
            } else if (value != null) {
                values.add(String.valueOf(value));
            }
            out.put(key, values);
        }
        return out;
    }

    @Override
    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) {
        return TeaVMBinds.secp256k1SharedSecret(privKey, pubKey);
    }

    @Override
    public ByteBuffer secp256k1SharedSecret(ByteBuffer privKey, ByteBuffer pubKey) {
        ByteBuffer output = allocateOutput(33);
        return finishOutput(output, TeaVMBinds.secp256k1SharedSecretBuffer(directInput(privKey), directInput(pubKey), output));
    }

    @Override
    public boolean secp256k1PrivateKeyVerify(byte[] privateKey) {
        return TeaVMBinds.secp256k1PrivateKeyVerify(privateKey);
    }

    @Override
    public boolean secp256k1PrivateKeyVerify(ByteBuffer privateKey) {
        return TeaVMBinds.secp256k1PrivateKeyVerifyBuffer(directInput(privateKey));
    }

    @Override
    public boolean secp256k1PublicKeyVerify(byte[] publicKey) {
        return TeaVMBinds.secp256k1PublicKeyVerify(publicKey);
    }

    @Override
    public boolean secp256k1PublicKeyVerify(ByteBuffer publicKey) {
        return TeaVMBinds.secp256k1PublicKeyVerifyBuffer(directInput(publicKey));
    }

    @Override
    public byte[] secp256k1PublicKeyCreate(byte[] privateKey, boolean compressed) {
        return TeaVMBinds.secp256k1PublicKeyCreate(privateKey, compressed);
    }

    @Override
    public ByteBuffer secp256k1PublicKeyCreate(ByteBuffer privateKey, boolean compressed) {
        ByteBuffer output = allocateOutput(compressed ? 33 : 65);
        return finishOutput(output, TeaVMBinds.secp256k1PublicKeyCreateBuffer(directInput(privateKey), compressed, output));
    }

    @Override
    public Secp256k1RecoverableSignature secp256k1SignRecoverable(byte[] hash32, byte[] privateKey) {
        byte[] recovered = TeaVMBinds.secp256k1SignRecoverable(hash32, privateKey);
        if (recovered == null || recovered.length != 65) {
            throw new IllegalArgumentException("Recoverable signature must be 65 bytes");
        }

        int recoveryId = recovered[0] & 0xFF;
        if (recoveryId < 0 || recoveryId > 3) {
            throw new IllegalArgumentException("recoveryId must be in [0..3]");
        }

        byte[] signature64 = Arrays.copyOfRange(recovered, 1, 65);
        return new Secp256k1RecoverableSignature(signature64, recoveryId);
    }

    @Override
    public Secp256k1RecoverableSignature secp256k1SignRecoverable(ByteBuffer hash32, ByteBuffer privateKey) {
        ByteBuffer recovered = allocateOutput(65);
        finishOutput(
            recovered,
            TeaVMBinds.secp256k1SignRecoverableBuffer(directInput(hash32), directInput(privateKey), recovered)
        );

        int recoveryId = recovered.get(0) & 0xff;
        byte[] signature64 = new byte[64];
        ByteBuffer signatureView = recovered.duplicate();
        signatureView.position(1);
        signatureView.get(signature64);
        return new Secp256k1RecoverableSignature(signature64, recoveryId);
    }

    @Override
    public byte[] secp256k1RecoverPublicKey(byte[] hash32, byte[] signature64, int recoveryId, boolean compressed) {
        return TeaVMBinds.secp256k1RecoverPublicKey(hash32, signature64, recoveryId, compressed);
    }

    @Override
    public ByteBuffer secp256k1RecoverPublicKey(ByteBuffer hash32, ByteBuffer signature64, int recoveryId, boolean compressed) {
        ByteBuffer output = allocateOutput(compressed ? 33 : 65);
        return finishOutput(
            output,
            TeaVMBinds.secp256k1RecoverPublicKeyBuffer(
                directInput(hash32),
                directInput(signature64),
                recoveryId,
                compressed,
                output
            )
        );
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2) {
        return TeaVMBinds.hmac(key, data1, data2);
    }

    @Override
    public ByteBuffer hmac(ByteBuffer key, ByteBuffer data1, ByteBuffer data2) {
        ByteBuffer output = allocateOutput(32);
        return finishOutput(output, TeaVMBinds.hmacBuffer(directInput(key), directInput(data1), directInput(data2), output));
    }

    @Override
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) {
        return TeaVMBinds.hkdf_extract(salt, ikm);
    }

    @Override
    public ByteBuffer hkdf_extract(ByteBuffer salt, ByteBuffer ikm) {
        ByteBuffer output = allocateOutput(32);
        return finishOutput(output, TeaVMBinds.hkdfExtractBuffer(directInput(salt), directInput(ikm), output));
    }

    @Override
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length) {
        return TeaVMBinds.hkdf_expand(prk, info, length);
    }

    @Override
    public ByteBuffer hkdf_expand(ByteBuffer prk, ByteBuffer info, int length) {
        ByteBuffer output = allocateOutput(length);
        return finishOutput(output, TeaVMBinds.hkdfExpandBuffer(directInput(prk), directInput(info), length, output));
    }

    @Override
    public String base64encode(byte[] data) {
        return TeaVMBinds.base64encode(data);
    }

    @Override
    public String base64encode(ByteBuffer data) {
        return TeaVMBinds.base64encodeBuffer(directInput(data));
    }

    @Override
    public byte[] base64decode(String data) {
        return TeaVMBinds.base64decode(data);
    }

    @Override
    public ByteBuffer base64decodeBuffer(String data) {
        int capacity = Math.max(1, ((data.length() + 3) / 4) * 3);
        ByteBuffer output = allocateOutput(capacity);
        return finishOutput(output, TeaVMBinds.base64decodeBuffer(data, output));
    }

    @Override
    public byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption) {
        return TeaVMBinds.chacha20(key, nonce, data);
    }

    @Override
    public ByteBuffer chacha20(ByteBuffer key, ByteBuffer nonce, ByteBuffer data, boolean forEncryption) {
        ByteBuffer input = directInput(data);
        ByteBuffer output = allocateOutput(input.remaining());
        return finishOutput(output, TeaVMBinds.chacha20Buffer(directInput(key), directInput(nonce), input, output));
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
    public ByteBuffer sha256(ByteBuffer data) {
        ByteBuffer output = allocateOutput(32);
        return finishOutput(output, TeaVMBinds.sha256Buffer(directInput(data), output));
    }

    @Override
    public String schnorrSign(String data, byte priv[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = TeaVMBinds.sign(dataB, priv);
        return NGEUtils.bytesToHex(sig);
    }

    @Override
    public String schnorrSign(String data, ByteBuffer privKey) {
        ByteBuffer message = directHex(data);
        ByteBuffer output = allocateOutput(64);
        finishOutput(output, TeaVMBinds.signBuffer(message, directInput(privKey), output));
        return NGEUtils.bytesToHex(output);
    }

    @Override
    public boolean schnorrVerify(String data, String sign, byte pub[]) {
        byte dataB[] = NGEUtils.hexToByteArray(data);
        byte sig[] = NGEUtils.hexToByteArray(sign);
        return TeaVMBinds.verify(dataB, pub, sig);
    }

    @Override
    public boolean schnorrVerify(String data, String sign, ByteBuffer pubKey) {
        return TeaVMBinds.verifyBuffer(directHex(data), directInput(pubKey), directHex(sign));
    }

    private void verifyRandomness(byte bytes[], int n) throws Exception {
        if (n <= 0) throw new IllegalArgumentException("Requested byte length must be positive");

        if (bytes == null) throw new Exception("Received null bytes");
        if (bytes.length != n) throw new Exception("Generated bytes length mismatch: expected " + n + ", got " + bytes.length);

        boolean allZero = true;
        boolean allSame = true;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) allZero = false;
            if (i > 0 && bytes[i] != bytes[i - 1]) allSame = false;
        }
        if (allZero) throw new Exception("Generated bytes are all zero");
        if (allSame) throw new Exception("Generated bytes are all the same value");
    }

    private void verifyRandomness(ByteBuffer bytes, int n) throws Exception {
        if (n <= 0) throw new IllegalArgumentException("Requested byte length must be positive");
        if (bytes == null) throw new Exception("Received null bytes");
        if (bytes.remaining() != n) {
            throw new Exception("Generated bytes length mismatch: expected " + n + ", got " + bytes.remaining());
        }

        boolean allZero = true;
        boolean allSame = true;
        byte first = bytes.get(bytes.position());
        for (int i = 0; i < bytes.remaining(); i++) {
            byte value = bytes.get(bytes.position() + i);
            if (value != 0) allZero = false;
            if (i > 0 && value != first) allSame = false;
        }
        if (allZero) throw new Exception("Generated bytes are all zero");
        if (allSame) throw new Exception("Generated bytes are all the same value");
    }

    @Override
    public byte[] randomBytes(int n) {
        try {
            byte[] bytes = TeaVMBinds.randomBytes(n);
            verifyRandomness(bytes, n);
            return bytes;
        } catch (Exception e) {
            panic("Failed to generate random bytes: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public ByteBuffer randomBytesBuffer(int n) {
        try {
            ByteBuffer output = allocateOutput(n);
            finishOutput(output, TeaVMBinds.randomBytesBuffer(output));
            verifyRandomness(output, n);
            return output;
        } catch (Exception e) {
            panic("Failed to generate random bytes: " + e.getMessage());
            throw new RuntimeException(e);
        }
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
        private final JSObject promiseHandle;

        public TeaVMPromise() {
            promiseHandle = TeaVMBinds.newPromise();
        }

        public void resolve(T value) {
            if (!completed) {
                this.result = value;
                this.completed = true;
                for (Consumer<T> callback : thenCallbacks) {
                    callback.accept(value);
                }
                TeaVMBinds.resolvePromise(this.promiseHandle);
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
                TeaVMBinds.rejectPromise(this.promiseHandle);
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
            TeaVMBinds.getPromise(promiseHandle).await();
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
            try {
                AsyncTask<?> submitted = executor.run(() -> {
                    try {
                        func.accept(promise::resolve, promise::reject);
                    } catch (Throwable e) {
                        promise.reject(e);
                    }
                    return null;
                });
                if (submitted != null) {
                    submitted.catchException(promise::reject);
                }
            } catch (Throwable e) {
                promise.reject(e);
            }
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

    private AsyncExecutor newJsExecutor() {
        AtomicBoolean closed = new AtomicBoolean();

        AsyncExecutor aexc = new AsyncExecutor() {
            @Override
            public <T> AsyncTask<T> run(Callable<T> r) {
                if (closed.get()) {
                    return wrapPromise((res, rej) -> rej.accept(new IllegalStateException("Executor already shutdown")));
                }
                return wrapPromise((res, rej) -> {
                    Thread worker = new Thread(() -> {
                        try {
                            res.accept(r.call());
                        } catch (Throwable e) {
                            rej.accept(e);
                        }
                    });
                    worker.setName("TeaVM Executor");
                    worker.start();
                });
            }

            @Override
            public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit) {
                long delayMs = unit.toMillis(delay);

                if (delayMs == 0) {
                    return run(r);
                }

                return run(() -> {
                    TeaVMBinds.delayPromise(NGEUtils.safeInt(delayMs)).await();
                    return r.call();
                });
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        return aexc;
    }

    <T> AsyncTask<T> runAsync(Callable<T> task) {
        return defaultExecutor.run(task);
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
    public AsyncTask<String> schnorrSignAsync(String data, byte privKey[]) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(schnorrSign(data, privKey));
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            defaultExecutor
        );
    }

    @Override
    public AsyncTask<String> schnorrSignAsync(String data, ByteBuffer privKey) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(schnorrSign(data, privKey));
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            defaultExecutor
        );
    }

    @Override
    public AsyncTask<Boolean> schnorrVerifyAsync(String data, String sign, byte pubKey[]) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(schnorrVerify(data, sign, pubKey));
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            defaultExecutor
        );
    }

    @Override
    public AsyncTask<Boolean> schnorrVerifyAsync(String data, String sign, ByteBuffer pubKey) {
        return promisify(
            (res, rej) -> {
                try {
                    res.accept(schnorrVerify(data, sign, pubKey));
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
        return defaultExecutor.run(() -> TeaVMBinds.getClipboardContentPromise().await().stringValue());
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

        String reqHeaders = headers != null ? toJSON(headers) : null;

        byte[] reqBody = body != null ? body : new byte[0];
        int timeoutMs = (int) ((timeout != null ? timeout : HTTP_TIMEOUT).toMillis());

        return defaultExecutor.run(() ->
            toHttpResponse(TeaVMBinds.fetchPromise(method, url, reqHeaders, reqBody, timeoutMs).await())
        );
    }

    @Override
    public AsyncTask<NGEHttpResponse> httpRequest(
        String method,
        String inurl,
        ByteBuffer body,
        Duration timeout,
        Map<String, String> headers
    ) {
        String url = NGEUtils.safeURI(inurl).toString();
        String reqHeaders = headers != null ? toJSON(headers) : null;
        ByteBuffer reqBody = body != null ? directInput(body) : directInput(ByteBuffer.allocate(0));
        int timeoutMs = (int) ((timeout != null ? timeout : HTTP_TIMEOUT).toMillis());

        return defaultExecutor.run(() ->
            toHttpResponse(TeaVMBinds.fetchBufferPromise(method, url, reqHeaders, reqBody, timeoutMs).await())
        );
    }

    @Override
    public AsyncTask<NGEHttpResponseStream> httpRequestStream(
        String method,
        String inurl,
        byte[] body,
        Duration timeout,
        Map<String, String> headers
    ) {
        String url = NGEUtils.safeURI(inurl).toString();

        String reqHeaders = headers != null ? toJSON(headers) : null;

        byte[] reqBody = body != null ? body : new byte[0];
        int timeoutMs = (int) ((timeout != null ? timeout : HTTP_TIMEOUT).toMillis());

        return defaultExecutor.run(() -> {
            TeaVMHttpStreamResponse response = TeaVMBinds
                .fetchStreamPromise(method, url, reqHeaders, reqBody, timeoutMs)
                .await();
            int statusCode = response.getStatus();
            Map<String, List<String>> respHeaders = normalizeHttpHeaders(response.getHeaders());
            boolean status = statusCode >= 200 && statusCode < 300;
            TeaVMReadableStreamWrapperInputStream input = new TeaVMReadableStreamWrapperInputStream(response.getBody());
            return new NGEHttpResponseStream(statusCode, respHeaders, input, status);
        });
    }

    @Override
    public RTCTransport newRTCTransport(Duration p2pAttemptTimeout, String connId, Collection<String> stunServers) {
        TeaVMRTCTransport transport = new TeaVMRTCTransport();
        transport.start(p2pAttemptTimeout, newAsyncExecutor(TeaVMRTCTransport.class), connId, stunServers);
        return transport;
    }

    @Override
    public void openInWebBrowser(String url) {
        TeaVMBinds.openURL(url);
    }

    @Override
    public byte[] scrypt(byte[] P, byte[] S, int N, int r, int p2, int dkLen) {
        ByteBuffer derived = scrypt(directInput(ByteBuffer.wrap(P)), directInput(ByteBuffer.wrap(S)), N, r, p2, dkLen);
        byte[] result = new byte[derived.remaining()];
        derived.get(result);
        return result;
    }

    @Override
    public ByteBuffer scrypt(ByteBuffer password, ByteBuffer salt, int n, int r, int p, int dkLen) {
        ByteBuffer output = allocateOutput(dkLen);
        int written = TeaVMBinds
            .scryptBufferPromise(directInput(password), directInput(salt), n, r, p, dkLen, output)
            .await()
            .intValue();
        return finishOutput(output, written);
    }

    @Override
    public byte[] xchacha20poly1305(byte[] key, byte[] nonce, byte[] data, byte[] associatedData, boolean forEncryption) {
        return TeaVMBinds.xchacha20poly1305(key, nonce, data, associatedData, forEncryption);
    }

    @Override
    public ByteBuffer xchacha20poly1305(
        ByteBuffer key,
        ByteBuffer nonce,
        ByteBuffer data,
        ByteBuffer associatedData,
        boolean forEncryption
    ) {
        ByteBuffer input = directInput(data);
        int capacity = forEncryption ? Math.addExact(input.remaining(), 16) : input.remaining();
        ByteBuffer output = allocateOutput(capacity);
        return finishOutput(
            output,
            TeaVMBinds.xchacha20poly1305Buffer(
                directInput(key),
                directInput(nonce),
                input,
                directInput(associatedData),
                forEncryption,
                output
            )
        );
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
        Cleaner.Cleanable cleanable = CLEANER.register(obj, finalizer);
        return cleanable::clean;
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
    public ByteBuffer aes256cbc(ByteBuffer key, ByteBuffer iv, ByteBuffer data, boolean forEncryption) {
        ByteBuffer input = directInput(data);
        int capacity = forEncryption ? Math.addExact(input.remaining(), 16) : input.remaining();
        ByteBuffer output = allocateOutput(capacity);
        return finishOutput(
            output,
            TeaVMBinds.aes256cbcBuffer(directInput(key), directInput(iv), input, forEncryption, output)
        );
    }

    @Override
    public String getPlatformName() {
        String backend = PlatformDetector.isWebAssemblyGC() ? "TeaVM Wasm GC" : "TeaVM JavaScript";
        return backend + " (" + TeaVMBinds.getRuntimeName() + ")";
    }

    private static ByteBuffer allocateOutput(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be non-negative");
        }
        return allocator.malloc(Math.max(1, capacity));
    }

    private NGEHttpResponse toHttpResponse(TeaVMHttpResponse response) {
        int statusCode = response.getStatus();
        Map<String, List<String>> headers = normalizeHttpHeaders(response.getHeaders());
        byte[] body = readHttpResponseBody(response, this);
        return new NGEHttpResponse(statusCode, headers, body, statusCode >= 200 && statusCode < 300);
    }

    static byte[] readHttpResponseBody(TeaVMHttpResponse response, NGEPlatform platform) {
        if (!PlatformDetector.isWebAssemblyGC()) {
            byte[] body = response.getBody();
            if (body == null) {
                return new byte[0];
            }
            if (!platform.getMemoryLimits().checkForData(body.length)) {
                throw new IllegalArgumentException("Response body exceeds buffer limits");
            }
            return body;
        }

        int length = TeaVMBinds.httpResponseBodyLength(response);
        if (!platform.getMemoryLimits().checkForData(length)) {
            throw new IllegalArgumentException("Response body exceeds buffer limits");
        }
        ByteBuffer directBody = allocateOutput(length);
        finishOutput(directBody, TeaVMBinds.copyHttpResponseBody(response, directBody));
        byte[] body = new byte[directBody.remaining()];
        directBody.get(body);
        return body;
    }

    private static ByteBuffer directInput(ByteBuffer input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        ByteBuffer view = input.slice();
        if (view.isDirect()) {
            return view;
        }

        ByteBuffer direct = allocateOutput(view.remaining());
        direct.limit(view.remaining());
        direct.put(view);
        direct.flip();
        // @JSBuffer exposes a buffer's capacity, not its current limit. Slicing
        // makes the JavaScript view cover exactly the caller's remaining bytes,
        // including the zero-length case.
        return direct.slice();
    }

    private static ByteBuffer directHex(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex value must contain an even number of characters");
        }

        ByteBuffer output = allocateOutput(value.length() / 2);
        output.limit(value.length() / 2);
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal value");
            }
            output.put((byte) ((high << 4) | low));
        }
        output.flip();
        return output;
    }

    private static ByteBuffer finishOutput(ByteBuffer output, int length) {
        if (length < 0 || length > output.capacity()) {
            throw new IllegalStateException(
                "Native operation returned invalid output length " + length + " for capacity " + output.capacity()
            );
        }
        output.position(0);
        output.limit(length);
        return output;
    }

    @Override
    public void callFunction(String function, Object args, Consumer<Object> res, Consumer<Throwable> rej) {
        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("args", args);
        defaultExecutor
            .run(() -> {
                String json = TeaVMBinds.callFunctionPromise(function, toJSON(argsMap)).await().stringValue();
                Map<String, Object> result = fromJSON(json, Map.class);
                return result.get("result");
            })
            .then(result -> {
                res.accept(result);
                return null;
            })
            .catchException(rej);
    }

    @Override
    public void canCallFunction(String function, Consumer<Boolean> res) {
        defaultExecutor
            .run(() -> TeaVMBinds.canCallFunctionPromise(function).await().booleanValue())
            .then(canCall -> {
                res.accept(canCall);
                return null;
            })
            .catchException(error -> {
                res.accept(false);
            });
    }

    @Override
    public void runInThread(Thread thread, Consumer<Runnable> enqueue, Runnable action) {
        enqueue.accept(action);
    }

    public void panic(String err) {
        System.err.println("PANIC: " + err);
        TeaVMBinds.panic(err);
        throw new RuntimeException("PANIC: " + err);
    }
}
