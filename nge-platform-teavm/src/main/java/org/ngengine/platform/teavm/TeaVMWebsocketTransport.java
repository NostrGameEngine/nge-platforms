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
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;

public class TeaVMWebsocketTransport implements WebsocketTransport {

    private static final Logger logger = Logger.getLogger(TeaVMWebsocketTransport.class.getName());

    private volatile BrowserWebSocket ws;
    private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder aggregator = new StringBuilder();
    private final TeaVMPlatform platform;
    private final AsyncExecutor asyncExecutor;

    public TeaVMWebsocketTransport(TeaVMPlatform platform) {
        this.platform = platform;
        this.asyncExecutor = platform.newAsyncExecutor();
    }

    // Native browser WebSocket interface definition
    private interface BrowserWebSocket extends JSObject {
        @JSProperty
        int getReadyState();

        @JSProperty("onopen")
        void setOnOpen(EventListener handler);

        @JSProperty("onclose")
        void setOnClose(EventListener handler);

        @JSProperty("onmessage")
        void setOnMessage(EventListener handler);

        @JSProperty("onerror")
        void setOnError(EventListener handler);

        void send(String data);

        void close(int code, String reason);
    }

    // Move the creation method outside the interface
    @JSBody(params = { "url" }, script = "return new WebSocket(url);")
    private static native BrowserWebSocket createWebSocket(String url);

    private interface MessageEvent extends Event {
        @JSProperty("data")
        String getData();
    }

    private interface CloseEvent extends Event {
        @JSProperty("code")
        int getCode();

        @JSProperty("reason")
        String getReason();
    }

    @Override
    public AsyncTask<Void> connect(String url) {
        return this.platform.wrapPromise((res, rej) -> {
                try {
                    if (this.ws == null) {
                        this.ws = createWebSocket(url);

                        this.ws.setOnOpen(evt -> {
                                this.asyncExecutor.run(() -> {
                                        for (WebsocketTransportListener listener : listeners) {
                                            try {
                                                listener.onConnectionOpen();
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error in onConnectionOpen listener", e);
                                            }
                                        }
                                        res.accept(null);
                                        return null;
                                    });
                            });

                        this.ws.setOnMessage(evt -> {
                                this.asyncExecutor.run(() -> {
                                        String message = ((MessageEvent) evt).getData();
                                        aggregator.append(message);
                                        String fullMessage = aggregator.toString();
                                        for (WebsocketTransportListener listener : listeners) {
                                            try {
                                                listener.onConnectionMessage(fullMessage);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error in onConnectionMessage listener", e);
                                            }
                                        }
                                        aggregator.setLength(0);
                                        return null;
                                    });
                            });

                        this.ws.setOnClose(evt -> {
                                this.asyncExecutor.run(() -> {
                                        CloseEvent closeEvent = (CloseEvent) evt;
                                        String reason = closeEvent.getReason();
                                        if (ws != null) {
                                            ws = null;
                                            for (WebsocketTransportListener listener : listeners) {
                                                listener.onConnectionClosedByServer(reason);
                                            }
                                        }
                                        return null;
                                    });
                            });

                        this.ws.setOnError(evt -> {
                                this.asyncExecutor.run(() -> {
                                        rej.accept(new IOException("WebSocket error"));
                                        return null;
                                    });
                            });
                    } else {
                        res.accept(null);
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        return this.platform.wrapPromise((res, rej) -> {
                try {
                    if (this.ws != null) {
                        final String r = reason != null ? reason : "Closed by client";
                        BrowserWebSocket wsToClose = this.ws;
                        this.ws = null;

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
        return this.platform.wrapPromise((res, rej) -> {
                try {
                    if (this.ws == null) {
                        rej.accept(new IOException("WebSocket not connected"));
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
}
