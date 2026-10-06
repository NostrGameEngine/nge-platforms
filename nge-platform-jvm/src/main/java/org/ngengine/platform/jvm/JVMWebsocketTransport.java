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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private final Set<WebSocket> closingSockets = Collections.newSetFromMap(new IdentityHashMap<>());
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
        return enqueue(null, task);
    }

    private <T> AsyncTask<T> enqueue(ConnectionAttempt owner, BiConsumer<Consumer<T>, Consumer<Throwable>> task) {
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
            CompletableFuture<?> previous;
            synchronized (lifecycleMonitor) {
                if (owner != null && (currentAttempt != owner || owner.retired)) {
                    previous = null;
                } else {
                    queuedTasks.add(result);
                    previous = futureQueue;
                    futureQueue = result;
                }
            }
            if (previous == null) {
                result.completeExceptionally(new IOException("WebSocket connection replaced or closed"));
                return;
            }
            try {
                // Scheduling can execute inline, including with a direct
                // executor or an already completed predecessor. Do it unlocked.
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
        boolean stale;
        synchronized (lifecycleMonitor) {
            stale = socket != openWebSocket && !closingSockets.contains(socket);
        }
        if (stale) {
            abort(socket);
        }
    }

    private void cleanup(ConnectionAttempt attempt, WebSocket socket, String reason, boolean graceful) {
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
                .whenComplete((value, error) -> finishGracefulClose(attempt, socket));
        } catch (Throwable error) {
            finishGracefulClose(attempt, socket);
        }
    }

    private void finishGracefulClose(ConnectionAttempt attempt, WebSocket socket) {
        CompletableFuture<WebSocket> pending;
        synchronized (lifecycleMonitor) {
            if (attempt.closingSocket != socket) {
                return;
            }
            attempt.closingSocket = null;
            closingSockets.remove(socket);
            pending = attempt.pending;
            attempt.pending = null;
        }
        abort(socket);
        if (pending != null) {
            pending.cancel(true);
        }
    }

    // Only stage effects under lifecycleMonitor: cancellation, socket methods
    // and CompletableFuture completion can all invoke external code inline.
    private void retire(ConnectionAttempt attempt, Throwable error, String reason, boolean graceful, List<Runnable> effects) {
        if (attempt == null || attempt.retired) {
            return;
        }
        attempt.retired = true;
        attempt.events.clear();
        CompletableFuture<WebSocket> pending = attempt.pending;
        WebSocket socket = attempt.socket;
        boolean ownsGracefulClose = graceful && socket != null;
        if (ownsGracefulClose) {
            // buildAsync can still be pending after onOpen/onClose. Its
            // cancellation and stale callbacks must not abort this close send.
            attempt.closingSocket = socket;
            closingSockets.add(socket);
        } else {
            attempt.pending = null;
        }
        attempt.socket = null;
        if (openWebSocket == socket) {
            openWebSocket = null;
        }
        List<CompletableFuture<?>> previousTasks = currentAttempt == attempt ? resetBuffersAndQueue() : List.of();
        // Start physical cleanup before task continuations can run user code.
        effects.add(() -> {
            if (pending != null && !ownsGracefulClose) {
                pending.cancel(true);
            }
            cleanup(attempt, socket, reason, graceful);
            rejectQueuedTasks(previousTasks, error);
        });
        settleConnect(attempt, error, effects);
    }

    // Claim the outcome while state is locked; publish it after unlocking.
    private void settleConnect(ConnectionAttempt attempt, Throwable error, List<Runnable> effects) {
        if (!attempt.resultSettled) {
            attempt.resultSettled = true;
            effects.add(() -> {
                if (error == null) {
                    attempt.result.complete(null);
                } else {
                    attempt.result.completeExceptionally(error);
                }
            });
        }
    }

    private void runEffects(List<Runnable> effects) {
        for (Runnable effect : effects) {
            effect.run();
        }
    }

    @Override
    public AsyncTask<Void> connect(String url) {
        logger.finest("Connecting to WebSocket: " + url);
        ConnectionAttempt attempt = new ConnectionAttempt();
        List<Runnable> effects = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            ConnectionAttempt previous = currentAttempt;
            IOException replaced = new IOException("WebSocket connection replaced");
            currentAttempt = attempt;
            openWebSocket = null;
            List<CompletableFuture<?>> previousTasks = resetBuffersAndQueue();
            retire(previous, replaced, null, false, effects);
            effects.add(() -> rejectQueuedTasks(previousTasks, replaced));
        }
        runEffects(effects);
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
            boolean cancelPending;
            synchronized (lifecycleMonitor) {
                boolean stale = currentAttempt != attempt || attempt.retired;
                cancelPending = stale && attempt.closingSocket == null;
                if (!cancelPending) {
                    attempt.pending = pending;
                }
            }
            pending.whenComplete((socket, error) -> completeConnect(attempt, socket, error));
            if (cancelPending) {
                pending.cancel(true);
            }
        } catch (Throwable error) {
            List<Runnable> failureEffects = new ArrayList<>();
            synchronized (lifecycleMonitor) {
                retire(attempt, error, null, false, failureEffects);
            }
            runEffects(failureEffects);
        }
        return task;
    }

    private void completeConnect(ConnectionAttempt attempt, WebSocket socket, Throwable error) {
        List<Runnable> effects = new ArrayList<>();
        boolean staleSocket = false;
        synchronized (lifecycleMonitor) {
            if (currentAttempt != attempt || attempt.retired) {
                staleSocket = true;
            } else if (error != null) {
                attempt.pending = null;
                retire(attempt, unwrap(error), null, false, effects);
                staleSocket = true;
            } else if (socket != null && (attempt.socket == null || attempt.socket == socket)) {
                // buildAsync completion and the listener's onOpen can arrive
                // in either order. Retain the socket so close can abort it even
                // before onOpen has published the connected state.
                attempt.pending = null;
                attempt.socket = socket;
                attempt.handshakeCompleted = true;
                if (attempt.openReady) {
                    settleConnect(attempt, null, effects);
                }
            } else {
                attempt.pending = null;
                retire(attempt, new IOException("Unexpected WebSocket connect result"), null, false, effects);
                staleSocket = true;
            }
        }
        runEffects(effects);
        if (staleSocket) {
            abortStale(socket);
        }
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        logger.finest("Closing WebSocket: " + reason);
        List<Runnable> effects = new ArrayList<>();
        ConnectionAttempt attempt;
        ListenerEvent event = null;
        synchronized (lifecycleMonitor) {
            attempt = currentAttempt;
            if (attempt != null && !attempt.retired) {
                retire(attempt, new IOException("WebSocket closed by client"), reason, true, effects);
                event = new ListenerEvent(attempt, null, false, listener -> listener.onConnectionClosedByClient(reason), null);
            }
        }
        runEffects(effects);
        offerEvent(event);
        return platform.wrapPromise((res, rej) -> res.accept(null));
    }

    private final class ConnectionAttempt implements WebSocket.Listener {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private CompletableFuture<WebSocket> pending;
        private WebSocket socket;
        private WebSocket closingSocket;
        private boolean opened;
        private boolean openReady;
        private boolean handshakeCompleted;
        private boolean retired;
        private boolean resultSettled;
        private final ArrayDeque<ListenerEvent> events = new ArrayDeque<>();
        private boolean dispatching;

        @Override
        public void onOpen(WebSocket webSocket) {
            open(this, webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            return receivedText(this, webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return receivedBinary(this, webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (!isCurrent(this, webSocket, true)) {
                abortStale(webSocket);
                return CompletableFuture.completedFuture(null);
            }
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (!isCurrent(this, webSocket, true)) {
                abortStale(webSocket);
                return CompletableFuture.completedFuture(null);
            }
            return WebSocket.Listener.super.onPong(webSocket, message);
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

    // Events own their attempt, socket and listener snapshot. One unlocked
    // drainer per attempt preserves admission order without blocking lifecycle
    // changes or a replacement attempt behind a user callback.
    private final class ListenerEvent {
        private final ConnectionAttempt attempt;
        private final WebSocket socket;
        private final boolean requireOpen;
        private final List<WebsocketTransportListener> recipients;
        private final Consumer<WebsocketTransportListener> callback;
        private final Runnable after;

        private ListenerEvent(ConnectionAttempt attempt, WebSocket socket, boolean requireOpen,
                              Consumer<WebsocketTransportListener> callback, Runnable after) {
            this.attempt = attempt;
            this.socket = socket;
            this.requireOpen = requireOpen;
            this.recipients = callback == null ? List.of() : List.copyOf(listeners);
            this.callback = callback;
            this.after = after;
        }
    }

    private boolean isCurrent(ConnectionAttempt attempt, WebSocket socket, boolean requireOpen) {
        // Admission is guarded, but an admitted callback or socket operation
        // can overlap a later retirement. Never hold this lock across it.
        synchronized (lifecycleMonitor) {
            return attempt != null && currentAttempt == attempt &&
                (!requireOpen || (socket != null && !attempt.retired && attempt.socket == socket && openWebSocket == socket));
        }
    }

    private void offerEvent(ListenerEvent event) {
        if (event == null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (currentAttempt != event.attempt) {
                return;
            }
            event.attempt.events.addLast(event);
        }
        drainEvents(event.attempt);
    }

    private void drainEvents(ConnectionAttempt attempt) {
        synchronized (lifecycleMonitor) {
            if (attempt.dispatching) {
                return;
            }
            attempt.dispatching = true;
        }
        try {
            while (true) {
                ListenerEvent event;
                synchronized (lifecycleMonitor) {
                    event = attempt.events.pollFirst();
                    if (event == null) {
                        attempt.dispatching = false;
                        return;
                    }
                }
                for (WebsocketTransportListener listener : event.recipients) {
                    if (!isCurrent(event.attempt, event.socket, event.requireOpen)) {
                        break;
                    }
                    try {
                        event.callback.accept(listener);
                    } catch (Exception error) {
                        logger.log(Level.WARNING, "Error in WebSocket listener", error);
                    }
                }
                if (event.after != null && isCurrent(event.attempt, event.socket, event.requireOpen)) {
                    event.after.run();
                }
            }
        } catch (Throwable error) {
            synchronized (lifecycleMonitor) {
                attempt.dispatching = false;
            }
            throw error;
        }
    }

    private void requestCurrent(ConnectionAttempt attempt, WebSocket socket, boolean opening) {
        if (!isCurrent(attempt, socket, true)) {
            return;
        }
        socket.request(1);
        if (opening) {
            List<Runnable> effects = new ArrayList<>();
            synchronized (lifecycleMonitor) {
                if (currentAttempt == attempt && !attempt.retired && openWebSocket == socket) {
                    attempt.openReady = true;
                    if (attempt.handshakeCompleted) {
                        settleConnect(attempt, null, effects);
                    }
                }
            }
            runEffects(effects);
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        ConnectionAttempt attempt;
        synchronized (lifecycleMonitor) {
            attempt = currentAttempt;
        }
        return receivedText(attempt, webSocket, data, last);
    }

    private CompletionStage<?> receivedText(ConnectionAttempt attempt, WebSocket webSocket, CharSequence data, boolean last) {
        boolean stale;
        synchronized (lifecycleMonitor) {
            stale = attempt == null || currentAttempt != attempt || attempt.retired ||
                webSocket == null || attempt.socket != webSocket || webSocket != openWebSocket;
            if (!stale) {
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
                    attempt.events.addLast(new ListenerEvent(attempt, webSocket, true,
                        listener -> listener.onConnectionMessage(message), () -> requestCurrent(attempt, webSocket, false)));
                } else {
                    attempt.events.addLast(new ListenerEvent(attempt, webSocket, true, null,
                        () -> requestCurrent(attempt, webSocket, false)));
                }
            }
        }
        if (stale) {
            abortStale(webSocket);
            return CompletableFuture.completedFuture(null);
        }
        drainEvents(attempt);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        ConnectionAttempt attempt;
        synchronized (lifecycleMonitor) {
            attempt = currentAttempt;
        }
        return receivedBinary(attempt, webSocket, data, last);
    }

    private CompletionStage<?> receivedBinary(ConnectionAttempt attempt, WebSocket webSocket, ByteBuffer data, boolean last) {
        boolean stale;
        synchronized (lifecycleMonitor) {
            stale = attempt == null || currentAttempt != attempt || attempt.retired ||
                webSocket == null || attempt.socket != webSocket || webSocket != openWebSocket;
            if (!stale) {
                ensureBinaryCapacity(binaryBuffer.position() + data.remaining());
                binaryBuffer.put(data);

                if (last) {
                    binaryBuffer.flip();
                    ByteBuffer message = ByteBuffer.allocate(binaryBuffer.remaining()).put(binaryBuffer);
                    message.flip();
                    binaryBuffer.clear();
                    attempt.events.addLast(new ListenerEvent(attempt, webSocket, true,
                        listener -> listener.onConnectionBinaryMessage(message.asReadOnlyBuffer()),
                        () -> requestCurrent(attempt, webSocket, false)));
                } else {
                    attempt.events.addLast(new ListenerEvent(attempt, webSocket, true, null,
                        () -> requestCurrent(attempt, webSocket, false)));
                }
            }
        }
        if (stale) {
            abortStale(webSocket);
            return CompletableFuture.completedFuture(null);
        }
        drainEvents(attempt);
        return null;
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
        boolean stale = false;
        synchronized (lifecycleMonitor) {
            if (attempt == null || currentAttempt != attempt || attempt.retired ||
                (attempt.socket != null && attempt.socket != webSocket)) {
                stale = true;
            } else if (attempt.opened) {
                return;
            } else {
                logger.finest("WebSocket opened");
                attempt.socket = webSocket;
                attempt.opened = true;
                openWebSocket = webSocket;
                attempt.events.addLast(new ListenerEvent(attempt, webSocket, true,
                    WebsocketTransportListener::onConnectionOpen, () -> requestCurrent(attempt, webSocket, true)));
            }
        }
        if (stale) {
            abortStale(webSocket);
        } else {
            drainEvents(attempt);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        ConnectionAttempt attempt;
        synchronized (lifecycleMonitor) {
            attempt = currentAttempt;
        }
        return closed(attempt, webSocket, statusCode, reason);
    }

    private CompletionStage<?> closed(ConnectionAttempt attempt, WebSocket webSocket, int statusCode, String reason) {
        List<Runnable> effects = new ArrayList<>();
        ListenerEvent event = null;
        synchronized (lifecycleMonitor) {
            if (attempt != null && currentAttempt == attempt && !attempt.retired &&
                webSocket != null && attempt.socket == webSocket) {
                logger.finest("WebSocket closed: " + statusCode + " " + reason);
                // Send the reciprocal Close before aborting; an immediate abort
                // prevents the JDK from completing the server's closing handshake.
                retire(attempt, new IOException("WebSocket closed by server: " + reason), "", true, effects);
                event = new ListenerEvent(attempt, null, false, listener -> listener.onConnectionClosedByServer(reason), null);
            }
        }
        runEffects(effects);
        if (event == null) {
            abortStale(webSocket);
        }
        offerEvent(event);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        ConnectionAttempt attempt;
        synchronized (lifecycleMonitor) {
            attempt = webSocket != null && openWebSocket == webSocket ? currentAttempt : null;
        }
        failed(attempt, webSocket, error);
    }

    private void failed(ConnectionAttempt attempt, WebSocket webSocket, Throwable error) {
        List<Runnable> effects = new ArrayList<>();
        ListenerEvent errorEvent = null;
        ListenerEvent closeEvent = null;
        synchronized (lifecycleMonitor) {
            if (attempt != null && currentAttempt == attempt && !attempt.retired &&
                (attempt.socket == null || attempt.socket == webSocket)) {
                logger.warning("WebSocket error: " + error);
                boolean wasOpen = attempt.opened;
                retire(attempt, error, null, false, effects);
                errorEvent = new ListenerEvent(attempt, null, false, listener -> listener.onConnectionError(error), null);
                if (wasOpen) {
                    closeEvent = new ListenerEvent(attempt, null, false,
                        listener -> listener.onConnectionClosedByServer("lost connection"), null);
                }
            }
        }
        runEffects(effects);
        abortStale(webSocket);
        // Admit both terminal notifications together; a reentrant reconnect
        // from an error listener suppresses the old close notification.
        if (errorEvent != null) {
            synchronized (lifecycleMonitor) {
                if (currentAttempt == attempt) {
                    attempt.events.addLast(errorEvent);
                    if (closeEvent != null) {
                        attempt.events.addLast(closeEvent);
                    }
                }
            }
            drainEvents(attempt);
        }
    }

    @Override
    public AsyncTask<Void> sendBinary(ByteBuffer data) {
        WebSocket ws;
        ConnectionAttempt owner;
        synchronized (lifecycleMonitor) {
            ws = openWebSocket;
            owner = currentAttempt;
        }

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
                        enqueue(owner, (rs0, rj0) -> {
                            if (!isCurrent(owner, ws, true)) {
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
                        enqueue(owner, (rs0, rj0) -> {
                            if (!isCurrent(owner, ws, true)) {
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
                                            if (!isCurrent(owner, ws, true)) {
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
        WebSocket ws;
        ConnectionAttempt owner;
        synchronized (lifecycleMonitor) {
            ws = openWebSocket;
            owner = currentAttempt;
        }

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
                        enqueue(owner, (rs0, rj0) -> {
                            if (!isCurrent(owner, ws, true)) {
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
                        enqueue(owner, (rs0, rj0) -> {
                            if (!isCurrent(owner, ws, true)) {
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
                                            if (!isCurrent(owner, ws, true)) {
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
