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

import static org.ngengine.platform.NGEUtils.dbg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class JVMWebsocketTransport implements WebsocketTransport, WebSocket.Listener {

    private static final Logger logger = Logger.getLogger(JVMWebsocketTransport.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);
    private static final int BUFFER_INITIAL_SIZE = 8192;
    private static final int BINARY_BUFFER_GROWTH_STEP = 16 * 1024;

    private volatile WebSocket openWebSocket;
    private volatile int maxMessageSize = -1;
    private final StringBuilder messageBuffer = new StringBuilder(BUFFER_INITIAL_SIZE);
    private ByteBuffer binaryBuffer = ByteBuffer.allocate(BUFFER_INITIAL_SIZE);

    private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final JVMAsyncPlatform platform;
    private final HttpClient httpClient;
    private final Executor executor;

    // Lifecycle, receive buffers and the send queue share a stable monitor.
    private final Object lifecycleMonitor = new Object();
    private ConnectionAttempt currentAttempt;
    private final List<CompletableFuture<?>> queuedTasks = new ArrayList<>();
    private CompletableFuture<?> futureQueue = CompletableFuture.completedFuture(null);

    public JVMWebsocketTransport(JVMAsyncPlatform platform, Executor executor) {
        this.platform = platform;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.httpClient = platform.getWebsocketHttpClient();
    }

    @Override
    public boolean isConnected() {
        return this.openWebSocket != null;
    }

    @Override
    public void setMaxMessageSize(int maxMessageSize) {
        if (maxMessageSize != -1 && maxMessageSize <= 0) {
            throw new IllegalArgumentException("maxMessageSize must be -1 or greater than 0");
        }
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    private int getEffectiveMaxMessageSize() {
        if (maxMessageSize != -1) {
            return maxMessageSize;
        }
        long transportLimit = platform.getMemoryLimits().getTransportLimit();
        if (transportLimit <= 0L || transportLimit > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid transport limit in MemoryLimits: " + transportLimit);
        }
        return (int) transportLimit;
    }

    public <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> task) {
        return platform.wrapPromise((res, rej) -> {
            CompletableFuture<T> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                synchronized (lifecycleMonitor) {
                    queuedTasks.remove(result);
                }
                if (error == null) {
                    res.accept(value);
                } else {
                    rej.accept(unwrap(error));
                }
            });
            synchronized (lifecycleMonitor) {
                queuedTasks.add(result);
                try {
                    // A failed send must not poison all following sends.
                    CompletableFuture<?> previous = futureQueue;
                    futureQueue = result;
                    previous.handle((value, error) -> null).thenRunAsync(() -> {
                        if (!result.isDone()) {
                            try {
                                task.accept(result::complete, result::completeExceptionally);
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        }
                    }, executor).whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(unwrap(error));
                        }
                    });
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    // Called under lifecycleMonitor after detaching the old socket. Snapshot
    // tasks so completion callbacks cannot modify the queue being retired.
    private List<CompletableFuture<?>> resetBuffersAndQueue() {
        messageBuffer.setLength(0);
        binaryBuffer.clear();
        List<CompletableFuture<?>> previousTasks = new ArrayList<>(queuedTasks);
        queuedTasks.clear();
        futureQueue = CompletableFuture.completedFuture(null);
        return previousTasks;
    }

    private void rejectQueuedTasks(List<CompletableFuture<?>> tasks, Throwable error) {
        for (CompletableFuture<?> task : tasks) {
            task.completeExceptionally(error);
        }
    }

    private void abort(WebSocket socket) {
        if (socket != null) {
            try {
                socket.abort();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "Failed to abort WebSocket", error);
            }
        }
    }

    private void abortStale(WebSocket socket) {
        synchronized (lifecycleMonitor) {
            if (socket != openWebSocket) {
                abort(socket);
            }
        }
    }

    private void cleanup(WebSocket socket, String reason, boolean graceful) {
        if (socket == null) {
            return;
        }
        if (!graceful) {
            abort(socket);
            return;
        }
        try {
            // Never wait behind the transport's send queue, including a failed
            // or stalled send. Sending a close frame alone does not close input.
            socket.sendClose(WebSocket.NORMAL_CLOSURE, reason != null ? reason : "Closed by client")
                .orTimeout(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((value, error) -> abort(socket));
        } catch (Throwable error) {
            abort(socket);
        }
    }

    // All callers hold lifecycleMonitor. Publish retirement before cancelling
    // futures or notifying task continuations, which can reenter this transport.
    private void retire(ConnectionAttempt attempt, Throwable error, String reason, boolean graceful) {
        if (attempt == null || attempt.retired) {
            return;
        }
        attempt.retired = true;
        CompletableFuture<WebSocket> pending = attempt.pending;
        WebSocket socket = attempt.socket;
        attempt.pending = null;
        attempt.socket = null;
        if (openWebSocket == socket) {
            openWebSocket = null;
        }
        List<CompletableFuture<?>> previousTasks = currentAttempt == attempt ? resetBuffersAndQueue() : List.of();
        // Start physical cleanup before task continuations can run user code.
        if (pending != null) {
            pending.cancel(true);
        }
        cleanup(socket, reason, graceful);
        rejectQueuedTasks(previousTasks, error);
        attempt.result.completeExceptionally(error);
    }

    @Override
    public AsyncTask<Void> connect(String url) {
        logger.finest("Connecting to WebSocket: " + url);
        ConnectionAttempt attempt = new ConnectionAttempt();
        synchronized (lifecycleMonitor) {
            ConnectionAttempt previous = currentAttempt;
            IOException replaced = new IOException("WebSocket connection replaced");
            currentAttempt = attempt;
            openWebSocket = null;
            List<CompletableFuture<?>> previousTasks = resetBuffersAndQueue();
            retire(previous, replaced, null, false);
            rejectQueuedTasks(previousTasks, replaced);
        }
        AsyncTask<Void> task = platform.wrapPromise((res, rej) -> {
            attempt.result.whenComplete((value, error) -> {
                if (error == null) {
                    res.accept(null);
                } else {
                    rej.accept(unwrap(error));
                }
            });
        });
        try {
            URI uri = JVMNetworkSecurity.safeWebSocketUri(url);
            synchronized (lifecycleMonitor) {
                if (currentAttempt != attempt || attempt.retired) {
                    return task;
                }
            }
            CompletableFuture<WebSocket> pending = httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, attempt);
            boolean stale;
            synchronized (lifecycleMonitor) {
                stale = currentAttempt != attempt || attempt.retired;
                if (!stale) {
                    attempt.pending = pending;
                }
            }
            pending.whenComplete((socket, error) -> completeConnect(attempt, socket, error));
            if (stale) {
                pending.cancel(true);
            }
        } catch (Throwable error) {
            synchronized (lifecycleMonitor) {
                retire(attempt, error, null, false);
            }
        }
        return task;
    }

    private void completeConnect(ConnectionAttempt attempt, WebSocket socket, Throwable error) {
        synchronized (lifecycleMonitor) {
            if (currentAttempt != attempt || attempt.retired) {
                abortStale(socket);
                return;
            }
            attempt.pending = null;
            if (error != null) {
                retire(attempt, unwrap(error), null, false);
                abortStale(socket);
            } else if (socket != null && (attempt.socket == null || attempt.socket == socket)) {
                // buildAsync completion and the listener's onOpen can arrive
                // in either order. Retain the socket so close can abort it even
                // before onOpen has published the connected state.
                attempt.socket = socket;
                attempt.result.complete(null);
            } else {
                retire(attempt, new IOException("Unexpected WebSocket connect result"), null, false);
                abortStale(socket);
            }
        }
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        logger.finest("Closing WebSocket: " + reason);
        synchronized (lifecycleMonitor) {
            ConnectionAttempt attempt = currentAttempt;
            if (attempt != null && !attempt.retired) {
                retire(attempt, new IOException("WebSocket closed by client"), reason, true);
                for (WebsocketTransportListener listener : listeners) {
                    if (currentAttempt != attempt) {
                        break;
                    }
                    try {
                        listener.onConnectionClosedByClient(reason);
                    } catch (Exception error) {
                        logger.warning("Error in close listener: " + error);
                    }
                }
            }
        }
        return platform.wrapPromise((res, rej) -> res.accept(null));
    }

    private final class ConnectionAttempt implements WebSocket.Listener {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private CompletableFuture<WebSocket> pending;
        private WebSocket socket;
        private boolean opened;
        private boolean retired;

        @Override
        public void onOpen(WebSocket webSocket) {
            open(this, webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            return JVMWebsocketTransport.this.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return JVMWebsocketTransport.this.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            synchronized (lifecycleMonitor) {
                if (currentAttempt != this || retired || openWebSocket != webSocket) {
                    abortStale(webSocket);
                    return CompletableFuture.completedFuture(null);
                }
                return WebSocket.Listener.super.onPing(webSocket, message);
            }
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            synchronized (lifecycleMonitor) {
                if (currentAttempt != this || retired || openWebSocket != webSocket) {
                    abortStale(webSocket);
                    return CompletableFuture.completedFuture(null);
                }
                return WebSocket.Listener.super.onPong(webSocket, message);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return closed(this, webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failed(this, webSocket, error);
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (lifecycleMonitor) {
            if (webSocket != openWebSocket || webSocket == null) {
                abortStale(webSocket);
                return CompletableFuture.completedFuture(null);
            }
            messageBuffer.append(data);
            int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
            if (messageBuffer.length() > effectiveMaxMessageSize) {
                int currentLength = messageBuffer.length();
                messageBuffer.setLength(0);
                throw new IllegalArgumentException(
                    "Incoming text message too large: " + currentLength + " chars (max " + effectiveMaxMessageSize + ")"
                );
            }
            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                for (WebsocketTransportListener listener : listeners) {
                    if (openWebSocket != webSocket) {
                        break;
                    }
                    try {
                        listener.onConnectionMessage(message);
                    } catch (Exception e) {
                        logger.warning("Error in message listener: " + e);
                    }
                }
            }
            if (openWebSocket == webSocket) {
                webSocket.request(1);
            }
            return null;
        }
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        synchronized (lifecycleMonitor) {
            if (webSocket != openWebSocket || webSocket == null) {
                abortStale(webSocket);
                return CompletableFuture.completedFuture(null);
            }
            ensureBinaryCapacity(binaryBuffer.position() + data.remaining());
            binaryBuffer.put(data);

            if (last) {
                binaryBuffer.flip();
                ByteBuffer message = ByteBuffer.allocate(binaryBuffer.remaining()).put(binaryBuffer);
                message.flip();
                binaryBuffer.clear();
                for (WebsocketTransportListener listener : listeners) {
                    if (openWebSocket != webSocket) {
                        break;
                    }
                    try {
                        listener.onConnectionBinaryMessage(message.asReadOnlyBuffer());
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in binary message listener", e);
                    }
                }
            }
            if (openWebSocket == webSocket) {
                webSocket.request(1);
            }
            return null;
        }
    }

    private void ensureBinaryCapacity(int requiredCapacity) {
        int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
        if (requiredCapacity > effectiveMaxMessageSize) {
            throw new IllegalArgumentException(
                "Incoming binary message too large: " + requiredCapacity + " bytes (max " + effectiveMaxMessageSize + ")"
            );
        }
        if (requiredCapacity <= binaryBuffer.capacity()) {
            return;
        }
        int missing = requiredCapacity - binaryBuffer.capacity();
        int steps = (missing + BINARY_BUFFER_GROWTH_STEP - 1) / BINARY_BUFFER_GROWTH_STEP;
        int newCapacity = binaryBuffer.capacity() + (steps * BINARY_BUFFER_GROWTH_STEP);
        newCapacity = Math.min(newCapacity, effectiveMaxMessageSize);
        ByteBuffer grown = ByteBuffer.allocate(newCapacity);
        binaryBuffer.flip();
        grown.put(binaryBuffer);
        binaryBuffer = grown;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // Only the listener bound to a buildAsync attempt may attach a socket.
        // This legacy Listener entry point cannot identify a pending attempt.
        abortStale(webSocket);
    }

    private void open(ConnectionAttempt attempt, WebSocket webSocket) {
        synchronized (lifecycleMonitor) {
            if (attempt == null || currentAttempt != attempt || attempt.retired) {
                abortStale(webSocket);
                return;
            }
            if (attempt.socket != null && attempt.socket != webSocket) {
                abortStale(webSocket);
                return;
            }
            if (attempt.opened) {
                return;
            }
            logger.finest("WebSocket opened");
            attempt.socket = webSocket;
            attempt.opened = true;
            openWebSocket = webSocket;
            for (WebsocketTransportListener listener : listeners) {
                if (currentAttempt != attempt || attempt.retired) {
                    break;
                }
                try {
                    listener.onConnectionOpen();
                } catch (Exception error) {
                    logger.warning("Error in open listener: " + error);
                }
            }
            if (currentAttempt == attempt && !attempt.retired) {
                webSocket.request(1);
            }
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        synchronized (lifecycleMonitor) {
            return closed(currentAttempt, webSocket, statusCode, reason);
        }
    }

    private CompletionStage<?> closed(ConnectionAttempt attempt, WebSocket webSocket, int statusCode, String reason) {
        synchronized (lifecycleMonitor) {
            if (attempt == null || currentAttempt != attempt || attempt.retired ||
                webSocket == null || attempt.socket != webSocket) {
                abortStale(webSocket);
                return CompletableFuture.completedFuture(null);
            }
            logger.finest("WebSocket closed: " + statusCode + " " + reason);
            retire(attempt, new IOException("WebSocket closed by server: " + reason), null, false);
            for (WebsocketTransportListener listener : listeners) {
                if (currentAttempt != attempt) {
                    break;
                }
                try {
                    listener.onConnectionClosedByServer(reason);
                } catch (Exception error) {
                    logger.warning("Error in close listener: " + error);
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        synchronized (lifecycleMonitor) {
            if (webSocket != null && openWebSocket == webSocket) {
                failed(currentAttempt, webSocket, error);
            } else {
                abortStale(webSocket);
            }
        }
    }

    private void failed(ConnectionAttempt attempt, WebSocket webSocket, Throwable error) {
        synchronized (lifecycleMonitor) {
            if (attempt == null || currentAttempt != attempt || attempt.retired ||
                (attempt.socket != null && attempt.socket != webSocket)) {
                abortStale(webSocket);
                return;
            }
            logger.warning("WebSocket error: " + error);
            boolean wasOpen = attempt.opened;
            retire(attempt, error, null, false);
            abortStale(webSocket);
            for (WebsocketTransportListener listener : listeners) {
                if (currentAttempt != attempt) {
                    break;
                }
                try {
                    listener.onConnectionError(error);
                } catch (Exception listenerError) {
                    logger.warning("Error in error listener: " + listenerError);
                }
            }
            if (wasOpen) {
                for (WebsocketTransportListener listener : listeners) {
                    if (currentAttempt != attempt) {
                        break;
                    }
                    try {
                        listener.onConnectionClosedByServer("lost connection");
                    } catch (Exception listenerError) {
                        logger.warning("Error in close listener: " + listenerError);
                    }
                }
            }
        }
    }

    @Override
    public AsyncTask<Void> sendBinary(ByteBuffer data) {
        WebSocket ws = this.openWebSocket;

        return platform.wrapPromise((res, rej) -> {
            try {
                if (ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }
                try {
                    final ByteBuffer source = data.duplicate();
                    final int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
                    final int totalBytes = source.remaining();
                    if (totalBytes <= effectiveMaxMessageSize) {
                        enqueue((rs0, rj0) -> {
                            if (ws != openWebSocket) {
                                throw new IllegalStateException("WebSocket connection replaced or closed");
                            }
                            assert dbg(() -> {
                                logger.finest("Sending full binary message: " + totalBytes);
                            });
                            ws
                                .sendBinary(source, true)
                                .handle((result, error) -> {
                                    if (error != null) {
                                        rej.accept(error);
                                        rj0.accept(error);
                                    } else {
                                        res.accept(null);
                                        rs0.accept(null);
                                    }
                                    return null;
                                });
                        }).catchException(rej);
                    } else {
                        enqueue((rs0, rj0) -> {
                            if (ws != openWebSocket) {
                                throw new IllegalStateException("WebSocket connection replaced or closed");
                            }
                            CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(null);
                            while (source.hasRemaining()) {
                                int chunkSize = Math.min(source.remaining(), effectiveMaxMessageSize);
                                ByteBuffer chunk = source.slice();
                                chunk.limit(chunkSize);
                                source.position(source.position() + chunkSize);
                                final boolean isLast = !source.hasRemaining();
                                future =
                                    future.thenComposeAsync(
                                        r -> {
                                            if (ws != openWebSocket) {
                                                throw new IllegalStateException("WebSocket connection replaced or closed");
                                            }
                                            return ws.sendBinary(chunk, isLast);
                                        },
                                        executor
                                    );
                            }
                            future.handle((result, error) -> {
                                if (error != null) {
                                    rej.accept(error);
                                    rj0.accept(error);
                                } else {
                                    res.accept(null);
                                    rs0.accept(null);
                                }
                                return null;
                            });
                        }).catchException(rej);
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> send(String message) {
        WebSocket ws = this.openWebSocket;

        return platform.wrapPromise((res, rej) -> {
            try {
                if (ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }
                try {
                    final int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
                    int messageLength = message.length();
                    // Send entirely or in chunks if needed
                    if (messageLength <= effectiveMaxMessageSize) {
                        enqueue((rs0, rj0) -> {
                            if (ws != openWebSocket) {
                                throw new IllegalStateException("WebSocket connection replaced or closed");
                            }
                            assert dbg(() -> {
                                logger.finest("Sending full message: " + message.length());
                            });
                            ws
                                .sendText(message, true)
                                .handle((result, error) -> {
                                    if (error != null) {
                                        rej.accept(error);
                                        rj0.accept(error);
                                    } else {
                                        res.accept(null);
                                        rs0.accept(null);
                                    }
                                    return null;
                                });
                        }).catchException(rej);
                    } else {
                        enqueue((rs0, rj0) -> {
                            if (ws != openWebSocket) {
                                throw new IllegalStateException("WebSocket connection replaced or closed");
                            }
                            int position = 0;
                            CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(null);
                            while (position < messageLength) {
                                // send in chunks
                                int start = position;
                                int end = Math.min(position + effectiveMaxMessageSize, messageLength);
                                assert dbg(() -> {
                                    logger.finest("Sending chunk: " + start + " " + end);
                                });
                                final String chunk = message.substring(start, end);
                                final boolean isLast = end >= messageLength;
                                // chain chunks
                                future =
                                    future.thenComposeAsync(
                                        r -> {
                                            if (ws != openWebSocket) {
                                                throw new IllegalStateException("WebSocket connection replaced or closed");
                                            }
                                            return ws.sendText(chunk, isLast);
                                        },
                                        executor
                                    );
                                position = end;
                            }
                            future.handle((result, error) -> {
                                if (error != null) {
                                    rej.accept(error);
                                    rj0.accept(error);
                                } else {
                                    res.accept(null);
                                    rs0.accept(null);
                                }
                                return null;
                            });
                        }).catchException(rej);
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public void addListener(WebsocketTransportListener listener) {
        assert !listeners.contains(listener) : "Listener already added";
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(WebsocketTransportListener listener) {
        listeners.remove(listener);
    }
}
