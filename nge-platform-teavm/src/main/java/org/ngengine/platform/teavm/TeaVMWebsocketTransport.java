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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSUndefined;

public class TeaVMWebsocketTransport implements WebsocketTransport {

    private static final Logger logger = Logger.getLogger(TeaVMWebsocketTransport.class.getName());

    private volatile BrowserWebSocket ws;
    private volatile int maxMessageSize = -1;
    private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final TeaVMPlatform platform;
    private final AsyncExecutor asyncExecutor;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private final Object sendQueueMonitor = new Object();
    private AsyncTask<Void> sendQueueTail = null;

    public TeaVMWebsocketTransport(TeaVMPlatform platform) {
        this.platform = platform;
        this.asyncExecutor = platform.newAsyncExecutor();
    }

    void setWs(JSObject socket) {
        this.ws = (BrowserWebSocket) socket;
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

    // Native browser WebSocket interface definition
    private interface BrowserWebSocket extends JSObject {
        @JSProperty
        int getReadyState();

        @JSProperty("binaryType")
        void setBinaryType(String type);

        void send(String data);
        void send(Buffer data);

        void close(int code, String reason);
    }

    // Move the creation method outside the interface
    @JSBody(params = { "url" }, script = "return new WebSocket(url);")
    private static native BrowserWebSocket createWebSocket(String url);

    @Override
    public AsyncTask<Void> connect(String url) {
        if (this.ws != null) {
            return platform.runAsync(() -> null);
        }
        try {
            this.ws = createWebSocket(url);
            this.ws.setBinaryType("arraybuffer");
            TeaVMBinds.websocketInitEventQueue(this.ws);
            JSPromise<JSUndefined> openPromise = TeaVMBinds.websocketOpenPromise(this.ws, (int) CONNECT_TIMEOUT.toMillis());
            BrowserWebSocket socket = this.ws;
            platform.runAsync(() -> {
                pumpEvents(socket);
                return null;
            });
            return platform.runAsync(() -> {
                openPromise.await();
                for (WebsocketTransportListener listener : listeners) {
                    try {
                        listener.onConnectionOpen();
                    } catch (Exception error) {
                        logger.log(Level.WARNING, "Error in onConnectionOpen listener", error);
                    }
                }
                return null;
            });
        } catch (Throwable error) {
            return platform.wrapPromise((resolve, reject) -> reject.accept(error));
        }
    }

    private void pumpEvents(BrowserWebSocket socket) {
        try {
            while (this.ws == socket) {
                int type = TeaVMBinds.websocketEventType(socket);
                if (type == 0) {
                    TeaVMBinds.eventQueueWaitPromise(socket).await();
                    continue;
                }
                try {
                    if (type == 1) {
                        dispatchText(socket, TeaVMBinds.websocketEventText(socket));
                    } else if (type == 2) {
                        dispatchBinary(socket);
                    } else if (type == 3) {
                        this.ws = null;
                        String reason = TeaVMBinds.websocketEventText(socket);
                        for (WebsocketTransportListener listener : listeners) {
                            listener.onConnectionClosedByServer(reason);
                        }
                    } else if (type == 4) {
                        IOException error = new IOException(TeaVMBinds.websocketEventText(socket));
                        for (WebsocketTransportListener listener : listeners) {
                            listener.onConnectionError(error);
                        }
                    }
                } finally {
                    TeaVMBinds.websocketConsumeEvent(socket);
                }
            }
        } finally {
            TeaVMBinds.eventQueueDispose(socket);
        }
    }

    private void dispatchText(BrowserWebSocket socket, String message) {
        int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
        if (message.length() > effectiveMaxMessageSize) {
            rejectOversizedIncoming(socket, "text", message.length(), effectiveMaxMessageSize);
            return;
        }
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionMessage(message);
            } catch (Exception error) {
                logger.log(Level.WARNING, "Error in onConnectionMessage listener", error);
            }
        }
    }

    private void dispatchBinary(BrowserWebSocket socket) {
        int length = TeaVMBinds.websocketEventBinaryLength(socket);
        int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
        if (length > effectiveMaxMessageSize) {
            rejectOversizedIncoming(socket, "binary", length, effectiveMaxMessageSize);
            return;
        }
        ByteBuffer message = platform.getNativeAllocator().malloc(Math.max(1, length));
        message.limit(length);
        int written = TeaVMBinds.websocketReadBinaryEvent(socket, message);
        message.position(0);
        message.limit(written);
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionBinaryMessage(message.asReadOnlyBuffer());
            } catch (Exception error) {
                logger.log(Level.WARNING, "Error in onConnectionBinaryMessage listener", error);
            }
        }
    }

    private void rejectOversizedIncoming(BrowserWebSocket socket, String kind, int length, int limit) {
        IllegalArgumentException error = new IllegalArgumentException(
            "Incoming " +
            kind +
            " message too large: " +
            length +
            (kind.equals("text") ? " chars" : " bytes") +
            " (max " +
            limit +
            ")"
        );
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionError(error);
            } catch (Exception listenerError) {
                logger.log(Level.WARNING, "Error in onConnectionError listener", listenerError);
            }
        }
        socket.close(1009, "Message too big");
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        return this.platform.wrapPromise((res, rej) -> {
                try {
                    if (this.ws != null) {
                        final String r = reason != null ? reason : "Closed by client";
                        BrowserWebSocket wsToClose = this.ws;
                        this.ws = null;
                        TeaVMBinds.eventQueueDispose(wsToClose);

                        for (WebsocketTransportListener listener : listeners) {
                            try {
                                listener.onConnectionClosedByClient(reason);
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error in onConnectionClosedByClient listener", e);
                            }
                        }

                        // Use NORMAL_CLOSURE code 1000
                        wsToClose.close(1000, r);
                        res.accept(null);
                    } else {
                        res.accept(null);
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<Void> send(String message) {
        return enqueueSend((res, rej) -> {
            try {
                if (this.ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }
                int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
                if (message.length() > effectiveMaxMessageSize) {
                    rej.accept(
                        new IllegalArgumentException(
                            "Outgoing text message too large: " +
                            message.length() +
                            " chars (max " +
                            effectiveMaxMessageSize +
                            ")"
                        )
                    );
                    return;
                }
                this.ws.send(message);
                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> sendBinary(ByteBuffer data) {
        return enqueueSend((res, rej) -> {
            try {
                if (this.ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }
                int effectiveMaxMessageSize = getEffectiveMaxMessageSize();
                if (data.remaining() > effectiveMaxMessageSize) {
                    rej.accept(
                        new IllegalArgumentException(
                            "Outgoing binary message too large: " +
                            data.remaining() +
                            " bytes (max " +
                            effectiveMaxMessageSize +
                            ")"
                        )
                    );
                    return;
                }
                this.ws.send(data.duplicate());
                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    private AsyncTask<Void> enqueueSend(
        java.util.function.BiConsumer<java.util.function.Consumer<Void>, java.util.function.Consumer<Throwable>> op
    ) {
        return platform.wrapPromise((res, rej) -> {
            synchronized (sendQueueMonitor) {
                if (sendQueueTail == null) {
                    sendQueueTail = platform.wrapPromise((forward, ignored) -> forward.accept(null));
                }
                sendQueueTail =
                    sendQueueTail.compose(ignored -> {
                        return platform.wrapPromise((forward, ignoredErr) -> {
                            op.accept(
                                value -> {
                                    res.accept(value);
                                    forward.accept(null);
                                },
                                err -> {
                                    rej.accept(err);
                                    forward.accept(null);
                                }
                            );
                        });
                    });
            }
        });
    }

    @Override
    public void addListener(WebsocketTransportListener listener) {
        this.listeners.add(listener);
    }

    @Override
    public void removeListener(WebsocketTransportListener listener) {
        this.listeners.remove(listener);
    }

    @Override
    public boolean isConnected() {
        if (this.ws == null) {
            return false;
        }
        int state = this.ws.getReadyState();
        return state == 1; // WebSocket.OPEN
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
}
