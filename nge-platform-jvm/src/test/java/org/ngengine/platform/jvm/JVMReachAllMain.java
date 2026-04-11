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
package org.ngengine.platform.jvm;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.VStore;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;

/**
 * Best-effort reachability harness for GraalVM metadata generation.
 * It intentionally exercises broad JVM platform code paths and reflection access.
 */
public final class JVMReachAllMain {

    private JVMReachAllMain() {}

    public static void main(String[] args) throws Exception {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        try {
            NGEPlatform.set(platform);
        } catch (IllegalStateException ignored) {
            // Platform may already be initialized in-process by previous calls.
        }

        exerciseReflectionSurface();
        exerciseDirectReflectionInvocations(platform);
        exerciseDirectInvocations(platform);
        exerciseCryptoAndEncoding(platform);
        exerciseAllocators(platform);
        exerciseAsyncAndTasks(platform);
        exerciseStorage(platform);
        exerciseTransports(platform);
        exerciseHttpRequests(platform);
        exerciseUtilityClasses();
    }

    private static void exerciseCryptoAndEncoding(JVMAsyncPlatform platform) {
        byte[] message = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String messageHex = NGEUtils.bytesToHex(message);
        byte[] privateKey = platform.generatePrivateKey();
        byte[] publicKey = platform.genPubKey(privateKey);

        safeRun("sha256-string", () -> platform.sha256("reach-all"));
        safeRun("sha256-bytes", () -> platform.sha256(message));
        safeRun("to-json-collection", () -> platform.toJSON(List.of("a", "b", "c")));
        safeRun(
            "to-json-map",
            () -> {
                Map<String, Object> map = new HashMap<>();
                map.put("ok", true);
                map.put("n", 1);
                return platform.toJSON(map);
            }
        );
        safeRun("from-json", () -> platform.fromJSON("{\"name\":\"reach\"}", ReachDto.class));
        safeRun(
            "sign-verify",
            () -> {
                String sig = platform.schnorrSign(messageHex, privateKey);
                return platform.schnorrVerify(messageHex, sig, publicKey);
            }
        );
        safeRun(
            "bc-ecdsa-keypair",
            () -> {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                }
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
                kpg.initialize(256);
                return kpg.generateKeyPair();
            }
        );
        safeRun(
            "bc-ecdsa-reflect-touch",
            () -> {
                Class<?> clazz = Class.forName("org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi$ECDSA");
                touchClassReflection(clazz);
                return null;
            }
        );
        safeRun("secp256k1-shared-secret", () -> platform.secp256k1SharedSecret(privateKey, publicKey));
        safeRun("hmac", () -> platform.hmac(platform.randomBytes(32), message, "suffix".getBytes(StandardCharsets.UTF_8)));
        safeRun(
            "hkdf",
            () -> {
                byte[] prk = platform.hkdf_extract(null, message);
                return platform.hkdf_expand(prk, "info".getBytes(StandardCharsets.UTF_8), 32);
            }
        );
        safeRun("base64", () -> platform.base64decode(platform.base64encode(message)));
        safeRun("chacha20", () -> platform.chacha20(platform.randomBytes(32), platform.randomBytes(12), message, true));
        safeRun(
            "xchacha20poly1305",
            () -> {
                byte[] key = platform.randomBytes(32);
                byte[] nonce = platform.randomBytes(24);
                byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);
                byte[] enc = platform.xchacha20poly1305(key, nonce, message, aad, true);
                return platform.xchacha20poly1305(key, nonce, enc, aad, false);
            }
        );
        safeRun(
            "aes256cbc",
            () -> {
                byte[] key = platform.randomBytes(32);
                byte[] iv = platform.randomBytes(16);
                byte[] enc = platform.aes256cbc(key, iv, message, true);
                return platform.aes256cbc(key, iv, enc, false);
            }
        );
        safeRun(
            "scrypt",
            () ->
                platform.scrypt("pw".getBytes(StandardCharsets.UTF_8), "salt".getBytes(StandardCharsets.UTF_8), 1024, 8, 1, 32)
        );
        safeRun("nfkc", () -> platform.nfkc("ＡＢＣ"));
        safeRun("timestamp", platform::getTimestampSeconds);
        safeRun("loopback-check", () -> platform.isLoopbackAddress(URI.create("http://127.0.0.1:8080")));
        safeRun(
            "open-resource",
            () -> {
                try (InputStream in = platform.openResource("META-INF/MANIFEST.MF")) {
                    return in != null;
                }
            }
        );
    }

    private static void exerciseAllocators(JVMAsyncPlatform platform) {
        safeRun(
            "allocator-malloc-free",
            () -> {
                ByteBuffer buf = platform.getNativeAllocator().malloc(64);
                platform.getNativeAllocator().free(buf);
                return null;
            }
        );
        safeRun(
            "allocator-calloc-realloc-free",
            () -> {
                ByteBuffer buf = platform.getNativeAllocator().calloc(1, 64);
                ByteBuffer resized = platform.getNativeAllocator().realloc(buf, 128);
                platform.getNativeAllocator().free(resized);
                return null;
            }
        );
        safeRun(
            "allocator-guard-before",
            () -> {
                JVMNGEAllocatorGuard.beforeAlloc(128);
                JVMNGEAllocatorGuard.notifyGC();
                return null;
            }
        );
    }

    private static void exerciseAsyncAndTasks(JVMAsyncPlatform platform) {
        AsyncExecutor executor = platform.newAsyncExecutor("reach-all");
        try {
            safeRun("promisify", () -> await(platform.promisify((res, rej) -> res.accept("ok"), executor)));
            safeRun("wrap-promise", () -> await(platform.wrapPromise((res, rej) -> res.accept(123))));
            safeRun("executor-run", () -> await(executor.run(() -> "v")));
            safeRun("executor-run-later", () -> await(executor.runLater(() -> "later", 1, TimeUnit.MILLISECONDS)));
            safeRun(
                "sign-verify-async",
                () -> {
                    byte[] sk = platform.generatePrivateKey();
                    byte[] pk = platform.genPubKey(sk);
                    byte[] msg = "async".getBytes(StandardCharsets.UTF_8);
                    String msgHex = NGEUtils.bytesToHex(msg);
                    String sig = await(platform.schnorrSignAsync(msgHex, sk));
                    return await(platform.schnorrVerifyAsync(msgHex, sig, pk));
                }
            );
            safeRun(
                "new-concurrent-queue",
                () -> {
                    Queue<String> q = platform.newConcurrentQueue(String.class);
                    q.offer("x");
                    return q.poll();
                }
            );
            safeRun(
                "register-finalizer",
                () -> {
                    Runnable cancel = platform.registerFinalizer(new Object(), () -> {});
                    cancel.run();
                    return null;
                }
            );
        } finally {
            safeRun(
                "executor-close",
                () -> {
                    executor.close();
                    return null;
                }
            );
        }
    }

    private static void exerciseStorage(JVMAsyncPlatform platform) {
        safeRun(
            "filesystem-vstore",
            () -> {
                Path tmp = Files.createTempDirectory("nge-reachall");
                FileSystemVStore backend = new FileSystemVStore(tmp);
                await(backend.write("a.txt")).write("hello".getBytes(StandardCharsets.UTF_8));
                await(backend.read("a.txt")).close();
                await(backend.exists("a.txt"));
                await(backend.listAll());
                await(backend.delete("a.txt"));
                return null;
            }
        );

        safeRun(
            "platform-vstore",
            () -> {
                VStore store = platform.getCacheStore("nge-reachall", "cache");
                await(store.writeFully("b.txt", "data".getBytes(StandardCharsets.UTF_8)));
                await(store.readFully("b.txt"));
                await(store.exists("b.txt"));
                await(store.listAll());
                await(store.delete("b.txt"));
                return null;
            }
        );
    }

    private static void exerciseTransports(JVMAsyncPlatform platform) {
        safeRun(
            "websocket-transport",
            () -> {
                WebsocketTransport ws = platform.newTransport();
                // Basic connected state check
                ws.isConnected();

                // If we have the JVM implementation, inject a mock java.net.http.WebSocket to exercise send/receive code paths
                try {
                    if (ws instanceof JVMWebsocketTransport) {
                        JVMWebsocketTransport jws = (JVMWebsocketTransport) ws;

                        java.net.http.WebSocket mock = new java.net.http.WebSocket() {
                            @Override
                            public java.util.concurrent.CompletableFuture<java.net.http.WebSocket> sendText(
                                CharSequence data,
                                boolean last
                            ) {
                                return java.util.concurrent.CompletableFuture.completedFuture(this);
                            }

                            @Override
                            public java.util.concurrent.CompletableFuture<java.net.http.WebSocket> sendBinary(
                                java.nio.ByteBuffer data,
                                boolean last
                            ) {
                                return java.util.concurrent.CompletableFuture.completedFuture(this);
                            }

                            @Override
                            public java.util.concurrent.CompletableFuture<java.net.http.WebSocket> sendPing(
                                java.nio.ByteBuffer message
                            ) {
                                return java.util.concurrent.CompletableFuture.completedFuture(this);
                            }

                            @Override
                            public java.util.concurrent.CompletableFuture<java.net.http.WebSocket> sendPong(
                                java.nio.ByteBuffer message
                            ) {
                                return java.util.concurrent.CompletableFuture.completedFuture(this);
                            }

                            @Override
                            public java.util.concurrent.CompletableFuture<java.net.http.WebSocket> sendClose(
                                int statusCode,
                                String reason
                            ) {
                                return java.util.concurrent.CompletableFuture.completedFuture(this);
                            }

                            @Override
                            public void request(long n) {}

                            @Override
                            public String getSubprotocol() {
                                return null;
                            }

                            @Override
                            public boolean isInputClosed() {
                                return false;
                            }

                            @Override
                            public boolean isOutputClosed() {
                                return false;
                            }

                            @Override
                            public void abort() {}
                        };

                        try {
                            java.lang.reflect.Field f = JVMWebsocketTransport.class.getDeclaredField("openWebSocket");
                            f.setAccessible(true);
                            f.set(jws, mock);
                        } catch (Throwable ignored) {}

                        // Trigger lifecycle callbacks directly
                        try {
                            jws.onOpen(mock);
                            jws.onText(mock, "reach-all-hello", true);
                            jws.onBinary(mock, java.nio.ByteBuffer.wrap(new byte[] { 1, 2, 3 }), true);

                            // Send small message
                            await(ws.send("hello"));

                            // Send a large text message to exercise chunking path
                            StringBuilder big = new StringBuilder();
                            for (int i = 0; i < 70_000; i++) big.append('x');
                            await(ws.send(big.toString()));

                            // Send a large binary message to exercise binary chunking and ensureBinaryCapacity
                            java.nio.ByteBuffer bigBuf = java.nio.ByteBuffer.allocate(70_000);
                            for (int i = 0; i < 70_000; i++) bigBuf.put((byte) (i & 0xFF));
                            bigBuf.flip();
                            await(ws.sendBinary(bigBuf));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                // Close gracefully (best-effort)
                try {
                    await(ws.close("reach-all-close"));
                } catch (Throwable ignored) {}

                return null;
            }
        );

        safeRun(
            "rtc-transport",
            () -> {
                Duration p2pAttemptTimeout = Duration.ofSeconds(2);
                Collection<String> stun = new ArrayList<>();
                RTCTransport rtc = platform.newRTCTransport(p2pAttemptTimeout, "reach-all-conn", stun);
                rtc.getName();
                rtc.isConnected();
                rtc.close();
                return null;
            }
        );
    }

    private static void exerciseHttpRequests(JVMAsyncPlatform platform) {
        safeRun(
            "http-local-server",
            () -> {
                final java.net.ServerSocket server = new java.net.ServerSocket(0);
                Thread t = new Thread(() -> {
                    try {
                        for (int i = 0; i < 2; i++) {
                            java.net.Socket s = server.accept();
                            try {
                                java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(s.getInputStream())
                                );
                                String line;
                                while ((line = r.readLine()) != null && !line.isEmpty()) {}
                                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                                java.io.OutputStream os = s.getOutputStream();
                                os.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(
                                            StandardCharsets.UTF_8
                                        )
                                );
                                os.write(body);
                                os.flush();
                                s.close();
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {} finally {
                        try {
                            server.close();
                        } catch (Throwable ignored) {}
                    }
                });
                t.setDaemon(true);
                t.start();
                int port = server.getLocalPort();
                try {
                    await(platform.httpRequest("GET", "http://127.0.0.1:" + port + "/", null, Duration.ofSeconds(5), null));
                } catch (Throwable ignored) {}
                try {
                    await(
                        platform.httpRequestStream("GET", "http://127.0.0.1:" + port + "/", null, Duration.ofSeconds(5), null)
                    )
                        .body()
                        .close();
                } catch (Throwable ignored) {}
                return null;
            }
        );
    }

    private static void exerciseUtilityClasses() {
        safeRun(
            "util",
            () -> {
                Util.bytesFromInt(42);
                Util.bytesFromBigInteger(BigInteger.TEN);
                Util.bigIntFromBytes(new byte[] { 1, 2, 3 });
                Util.bigIntFromBytes(new byte[] { 1, 2, 3, 4 }, 1, 2);
                Util.xor(new byte[] { 1, 2 }, new byte[] { 3, 4 }, null);
                Util.hchacha20(new byte[32], new byte[16]);
                Util.getSystemCachePath("nge-reachall");
                Util.getSystemDataPath("nge-reachall");
                return null;
            }
        );

        safeRun(
            "point",
            () -> {
                Point g = Point.getG();
                Point.add(g, g);
                Point.mul(g, BigInteger.valueOf(2));
                Point.bytesFromPoint(g);
                Point.liftX(g.toBytes());
                Point.schnorrVerify(BigInteger.ONE, g, BigInteger.ONE);
                return null;
            }
        );

        safeRun(
            "schnorr",
            () -> {
                byte[] msg = "reach".getBytes(StandardCharsets.UTF_8);
                byte[] sk = Schnorr.generatePrivateKey();
                byte[] pk = Schnorr.genPubKey(sk);
                byte[] sig = Schnorr.sign(msg, sk, new byte[32]);
                return Schnorr.verify(msg, pk, sig);
            }
        );
    }

    private static void exerciseReflectionSurface() {
        List<String> classNames = List.of(
            "org.ngengine.platform.jvm.JVMAsyncPlatform",
            "org.ngengine.platform.jvm.JVMNGEAllocator",
            "org.ngengine.platform.jvm.JVMNGEAllocatorGuard",
            "org.ngengine.platform.jvm.JVMRTCTransport",
            "org.ngengine.platform.jvm.JVMWebsocketTransport",
            "org.ngengine.platform.jvm.FileSystemVStore",
            "org.ngengine.platform.jvm.Point",
            "org.ngengine.platform.jvm.Schnorr",
            "org.ngengine.platform.jvm.Util"
        );

        for (String className : classNames) {
            safeRun(
                "reflect-" + className,
                () -> {
                    Class<?> clazz = Class.forName(className);
                    touchClassReflection(clazz);
                    for (Class<?> nested : clazz.getDeclaredClasses()) {
                        touchClassReflection(nested);
                    }
                    return null;
                }
            );
        }
    }

    private static void exerciseDirectReflectionInvocations(JVMAsyncPlatform platform) {
        // Make explicit reflective calls to constructors and methods to force metadata generation
        safeRun(
            "reflect-invoke-filesystemvstore",
            () -> {
                try {
                    Class<?> fsClass = Class.forName("org.ngengine.platform.jvm.FileSystemVStore");
                    java.lang.reflect.Constructor<?> ctor = fsClass.getDeclaredConstructor(java.nio.file.Path.class);
                    ctor.setAccessible(true);
                    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("nge-reachall");
                    Object fs = ctor.newInstance(tmp);
                    java.lang.reflect.Method writeMeth = fsClass.getDeclaredMethod("write", java.lang.String.class);
                    writeMeth.setAccessible(true);
                    Object writeTask = writeMeth.invoke(fs, "reflect.txt");
                    // try calling common VStore methods via reflection
                    java.lang.reflect.Method readMeth = fsClass.getDeclaredMethod("read", java.lang.String.class);
                    readMeth.setAccessible(true);
                    readMeth.invoke(fs, "reflect.txt");
                    java.lang.reflect.Method listMeth = fsClass.getDeclaredMethod("listAll");
                    listMeth.setAccessible(true);
                    listMeth.invoke(fs);

                    // If write returned an AsyncTask, await and close the stream where possible
                    try {
                        if (writeTask != null) {
                            java.lang.reflect.Method await = writeTask.getClass().getMethod("await");
                            Object os = await.invoke(writeTask);
                            if (os instanceof java.io.OutputStream) {
                                try {
                                    ((java.io.OutputStream) os).write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    ((java.io.OutputStream) os).close();
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "reflect-invoke-allocator",
            () -> {
                try {
                    Class<?> allocClass = Class.forName("org.ngengine.platform.jvm.JVMNGEAllocator");
                    Object allocInstance = allocClass.getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method malloc = allocClass.getDeclaredMethod("malloc", int.class);
                    malloc.setAccessible(true);
                    Object buf = malloc.invoke(allocInstance, 32);
                    java.lang.reflect.Method realloc = allocClass.getDeclaredMethod(
                        "realloc",
                        java.nio.ByteBuffer.class,
                        int.class
                    );
                    realloc.setAccessible(true);
                    Object resized = realloc.invoke(allocInstance, buf, 64);
                    java.lang.reflect.Method free = allocClass.getDeclaredMethod("free", java.nio.ByteBuffer.class);
                    free.setAccessible(true);
                    free.invoke(allocInstance, resized != null ? resized : buf);
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "reflect-invoke-guard",
            () -> {
                try {
                    Class<?> guard = Class.forName("org.ngengine.platform.jvm.JVMNGEAllocatorGuard");
                    java.lang.reflect.Method before = guard.getDeclaredMethod("beforeAlloc", long.class);
                    before.setAccessible(true);
                    before.invoke(null, 128L);
                    java.lang.reflect.Method notify = guard.getDeclaredMethod("notifyGC");
                    notify.setAccessible(true);
                    notify.invoke(null);
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "reflect-invoke-util-point-schnorr",
            () -> {
                try {
                    Class<?> util = Class.forName("org.ngengine.platform.jvm.Util");
                    java.lang.reflect.Method getCache = util.getDeclaredMethod("getSystemCachePath", String.class);
                    getCache.setAccessible(true);
                    getCache.invoke(null, "nge-reachall");

                    Class<?> point = Class.forName("org.ngengine.platform.jvm.Point");
                    java.lang.reflect.Method getG = point.getDeclaredMethod("getG");
                    getG.setAccessible(true);
                    Object g = getG.invoke(null);
                    java.lang.reflect.Method bytesFrom = point.getDeclaredMethod("bytesFromPoint", point);
                    bytesFrom.setAccessible(true);
                    bytesFrom.invoke(null, g);

                    java.lang.reflect.Method addMeth = point.getDeclaredMethod("add", point, point);
                    addMeth.setAccessible(true);
                    addMeth.invoke(null, g, g);

                    Class<?> schnorr = Class.forName("org.ngengine.platform.jvm.Schnorr");
                    java.lang.reflect.Method genPk = schnorr.getDeclaredMethod("genPubKey", byte[].class);
                    genPk.setAccessible(true);
                    genPk.invoke(null, new Object[] { platform.generatePrivateKey() });
                } catch (Throwable ignored) {}
                return null;
            }
        );
    }

    private static void exerciseDirectInvocations(JVMAsyncPlatform platform) {
        safeRun(
            "direct-invoke-filesystemvstore",
            () -> {
                try {
                    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("nge-reachall");
                    FileSystemVStore fs = new FileSystemVStore(tmp);
                    org.ngengine.platform.AsyncTask<java.io.OutputStream> outTask = fs.write("direct.txt");
                    try {
                        java.io.OutputStream os = outTask.await();
                        try {
                            os.write("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            os.close();
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                    fs.exists("direct.txt");
                    fs.listAll();
                    fs.delete("direct.txt");
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "direct-invoke-allocator",
            () -> {
                try {
                    JVMNGEAllocator alloc = new JVMNGEAllocator();
                    java.nio.ByteBuffer b = alloc.malloc(64);
                    java.nio.ByteBuffer r = alloc.realloc(b, 128);
                    alloc.free(r != null ? r : b);
                    alloc.mallocAligned(32, 16);
                    alloc.address(b);
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "direct-invoke-guard",
            () -> {
                try {
                    JVMNGEAllocatorGuard.beforeAlloc(256);
                    JVMNGEAllocatorGuard.notifyGC();
                } catch (Throwable ignored) {}
                return null;
            }
        );

        safeRun(
            "direct-invoke-util-point-schnorr",
            () -> {
                try {
                    Util.bytesFromInt(12345);
                    Util.bytesFromBigInteger(java.math.BigInteger.valueOf(42));
                    Util.bigIntFromBytes(new byte[] { 1, 2, 3 });
                    Util.hchacha20(new byte[32], new byte[16]);

                    Point g = Point.getG();
                    Point.add(g, g);
                    Point.mul(g, java.math.BigInteger.valueOf(3));
                    Point.bytesFromPoint(g);
                    Point.liftX(g.toBytes());

                    byte[] sk = Schnorr.generatePrivateKey();
                    byte[] pk = Schnorr.genPubKey(sk);
                    Schnorr.sign("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8), sk, new byte[32]);
                } catch (Throwable ignored) {}
                return null;
            }
        );
    }

    private static void touchClassReflection(Class<?> clazz) {
        try {
            clazz.getDeclaredConstructors();
            clazz.getDeclaredMethods();
            clazz.getDeclaredFields();
            clazz.getDeclaredClasses();
            clazz.getMethods();
            clazz.getFields();
            clazz.getConstructors();
        } catch (Throwable ignored) {}
    }

    private static <T> T await(AsyncTask<T> task) throws Exception {
        return task.await();
    }

    private static <T> T safeRun(String label, ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class ReachDto {

        String name;
    }
}
