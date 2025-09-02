/**
 * BSD 3-Clause License
 * ...
 */
package org.ngengine.platform.android;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class AndroidWebsocketTransport implements WebsocketTransport {

    private static final Logger logger = Logger.getLogger(AndroidWebsocketTransport.class.getName());
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65_536;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    private volatile WebSocket webSocket;
    private static final int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private final StringBuilder messageBuffer = new StringBuilder(8192);

    private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final AndroidThreadedPlatform platform;
    private final OkHttpClient httpClient;
    private final Executor executor;

    private final Object queueMonitor = new Object();
    private CompletableFuture<?> futureQueue = CompletableFuture.completedFuture(null);

    private volatile boolean isConnecting = false;
    private volatile boolean isClosing = false;

    public AndroidWebsocketTransport(AndroidThreadedPlatform platform, Executor executor) {
        this.platform = platform;
        this.executor = executor;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .writeTimeout(WRITE_TIMEOUT)
                .followRedirects(true)
                .followSslRedirects(true);

        this.httpClient = builder.build();
    }

    @Override
    public boolean isConnected() {
        return this.webSocket != null && !isClosing;
    }

    public <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> task) {
        return platform.wrapPromise((res, rej) -> {
            synchronized (queueMonitor) {
                futureQueue = futureQueue.thenComposeAsync(r -> {
                    CompletableFuture<T> future = new CompletableFuture<>();
                    try {
                        task.accept(
                                r0 -> {
                                    future.complete(r0);
                                    res.accept(r0);
                                },
                                e -> {
                                    future.completeExceptionally(e);
                                    rej.accept(e);
                                });
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                        rej.accept(e);
                    }
                    return future;
                }, executor);
            }
        });
    }

    @Override
    public AsyncTask<Void> connect(String url) {
        logger.finest("Connecting to WebSocket: " + url);
        if (isConnected() || isConnecting) {
            return platform.wrapPromise((res, rej) -> {
                rej.accept(new IllegalStateException("WebSocket already connected or connecting"));
            });
        }

        isConnecting = true;

        return platform.wrapPromise((res, rej) -> {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .build();

                WebSocketListener listener = new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        executor.execute(() -> {
                            logger.finest("WebSocket opened");
                            AndroidWebsocketTransport.this.webSocket = webSocket;
                            isConnecting = false;

                            for (WebsocketTransportListener listener : listeners) {
                                try {
                                    listener.onConnectionOpen();
                                } catch (Exception e) {
                                    logger.warning("Error in open listener: " + e);
                                }
                            }

                            res.accept(null);
                        });
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        executor.execute(() -> {
                            for (WebsocketTransportListener listener : listeners) {
                                try {
                                    listener.onConnectionMessage(text);
                                } catch (Exception e) {
                                    logger.warning("Error in message listener: " + e);
                                }
                            }
                        });
                    }

                    @Override
                    public void onClosing(WebSocket webSocket, int code, String reason) {
                        executor.execute(() -> {
                            logger.finest("WebSocket closing: " + code + " " + reason);
                            webSocket.close(code, reason);
                        });
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        executor.execute(() -> {
                            logger.finest("WebSocket closed: " + code + " " + reason);
                            AndroidWebsocketTransport.this.webSocket = null;
                            isClosing = false;

                            for (WebsocketTransportListener listener : listeners) {
                                try {
                                    listener.onConnectionClosedByServer(reason);
                                } catch (Exception e) {
                                    logger.warning("Error in close listener: " + e);
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        executor.execute(() -> {
                            logger.warning("WebSocket error: " + t);
                            AndroidWebsocketTransport.this.webSocket = null;
                            isConnecting = false;
                            isClosing = false;

                            for (WebsocketTransportListener listener : listeners) {
                                try {
                                    listener.onConnectionError(t);
                                    listener.onConnectionClosedByServer("lost connection");
                                } catch (Exception e) {
                                    logger.warning("Error in error listener: " + e);
                                }
                            }

                            if (isConnecting) {
                                rej.accept(t);
                            }
                        });
                    }
                };

                webSocket = httpClient.newWebSocket(request, listener);
            } catch (Exception e) {
                isConnecting = false;
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        logger.finest("Closing WebSocket: " + reason);

        if (webSocket == null || isClosing) {
            return platform.wrapPromise((res, rej) -> {
                res.accept(null);
            });
        }

        isClosing = true;
        WebSocket ws = this.webSocket;

        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionClosedByClient(reason);
            } catch (Exception e) {
                logger.warning("Error in close listener: " + e);
            }
        }

        return platform.wrapPromise((res, rej) -> {
            try {
                final String closeReason = reason != null ? reason : "Closed by client";
                boolean closed = ws.close(1000, closeReason);

                if (!closed) {
                    // Force close if normal close failed
                    this.webSocket = null;
                    isClosing = false;
                }

                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> send(String message) {
        WebSocket ws = this.webSocket;

        return platform.wrapPromise((res, rej) -> {
            try {
                if (ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }

                // OkHttp handles message fragmentation internally
                enqueue((rs0, rj0) -> {
                    boolean sent = ws.send(message);
                    if (sent) {
                        res.accept(null);
                        rs0.accept(null);
                    } else {
                        Exception ex = new IOException("Failed to send message");
                        rej.accept(ex);
                        rj0.accept(ex);
                    }
                });
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public void addListener(WebsocketTransportListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(WebsocketTransportListener listener) {
        listeners.remove(listener);
    }
}