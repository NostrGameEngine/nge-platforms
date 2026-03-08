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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback.Message;
import tel.schich.libdatachannel.DataChannelInitSettings;
import tel.schich.libdatachannel.DataChannelReliability;
import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.LibDataChannel;
import tel.schich.libdatachannel.LibDataChannelArchDetect;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

public class JVMRTCTransport implements RTCTransport {

    private static final Logger logger = Logger.getLogger(JVMRTCTransport.class.getName());

    static {
        try {
            LibDataChannelArchDetect.initialize();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to initialize LibDataChannel", t);
            throw new RuntimeException("Failed to initialize LibDataChannel", t);
        }
    }

    private final List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private PeerConnectionConfiguration config;
    private String connId;
    private PeerConnection conn;
    private final Map<DataChannel, RTCDataChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<RTCDataChannel>>> pendingIncomingChannelResolvers = new ConcurrentHashMap<>();
    private volatile boolean isInitiator;
    private final List<RTCTransportIceCandidate> trackedRemoteCandidates = new CopyOnWriteArrayList<>();
    private final List<RTCTransportIceCandidate> pendingRemoteCandidates = new CopyOnWriteArrayList<>();
    private AsyncExecutor executor;
    private volatile boolean connected = false;
    private RTCSettings settings;
    private static volatile boolean libAllocator = false;
    private final CopyOnWriteArrayList<Consumer<Throwable>> pendingReadyRejectors = new CopyOnWriteArrayList<>();

    public JVMRTCTransport() {
        logger.fine("JVMRTCTransport initialized");
        configureLibDataChannelAllocatorIfRequested();
    }

    private static synchronized void configureLibDataChannelAllocatorIfRequested() {
        if (libAllocator) {
            return;
        }
        LibDataChannel.setAllocator(size -> {
            ByteBuffer b = NGEPlatform.get().getNativeAllocator().calloc(1, size);
            if (b == null) {
                throw new IllegalStateException("Native allocator returned null buffer for size " + size);
            }
            return b;
        });
        libAllocator = true;
    }

    @Override
    public void start(RTCSettings settings, AsyncExecutor executor, String connId, Collection<String> stunServers) {
        this.settings = settings;
        this.executor = executor;
        Collection<URI> stunUris = new ArrayList<>();
        for (String server : stunServers) {
            try {
                URI uri = new URI("stun:" + server);
                stunUris.add(uri);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid STUN server URI: " + server, e);
            }
        }

        logger.finer("Using STUN servers: " + stunUris);
        this.config = PeerConnectionConfiguration.DEFAULT.withIceServers(stunUris).withDisableAutoNegotiation(false);
        this.connId = connId;
        this.conn = PeerConnection.createPeer(this.config);
        logger.finer("PeerConnection created with ID: " + connId);
        this.conn.onIceStateChange.register((peer, state) -> {
                logger.finer("ICE state changed: " + state);

                if (state == IceState.RTC_ICE_FAILED) {
                    // for (RTCTransportListener listener : listeners) {
                    // listener.onRTCIceFailed();
                    // }
                    this.close();
                    // for (RTCTransportListener listener : listeners) {
                    // listener.onRTCChannelClosed();
                    // }
                } else if (state == IceState.RTC_ICE_CONNECTED) {
                    // for (RTCTransportListener listener : listeners) {
                    // listener.onRTCIceConnected();
                    // }
                }
            });

        // this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
        // if (state == PeerState.RTC_CLOSED) {
        // connected = false;
        // } else if (state == PeerState.RTC_CONNECTED) {
        // connected = true;
        // }
        // });

        this.conn.onLocalCandidate.register((PeerConnection peer, String candidate, String mediaId) -> {
                logger.fine("Local ICE candidate: " + candidate);
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onLocalRTCIceCandidate(new RTCTransportIceCandidate(candidate, mediaId));
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error sending local candidate", e);
                    }
                }
            });
        this.conn.onStateChange.register((peer, state) -> {
                logger.fine("Peer connection state changed: " + state);
                if (state == PeerState.RTC_CONNECTED) {
                    this.connected = true;
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onRTCConnected();
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error notifying connection", e);
                        }
                    }
                } else if (state == PeerState.RTC_CLOSED || state == PeerState.RTC_FAILED) {
                    this.connected = false;
                    String reason = state == PeerState.RTC_FAILED ? "failed" : "closed";
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onRTCDisconnected(reason);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error notifying disconnect", e);
                        }
                    }
                }
            });
        this.conn.onDataChannel.register((peer, nativeChannel) -> {
                confChannel(nativeChannel)
                    .catchException(e -> logger.log(Level.WARNING, "Error configuring incoming channel", e))
                    .then(channel -> {
                        resolvePendingIncomingChannel(channel);
                        return null;
                    });
            });

        this.executor.runLater(
                () -> {
                    if (connected) return null;
                    logger.warning("RTC Connection attempt timed out, closing connection");
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onRTCDisconnected("timeout");
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error sending local candidate", e);
                        }
                    }
                    this.close();
                    return null;
                },
                settings.getP2pAttemptTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    AsyncTask<RTCDataChannel> confChannel(DataChannel channel) {
        RTCDataChannel existing = channels.get(channel);
        if (existing != null) {
            return existing.ready();
        }

        NGEPlatform platform = NGEUtils.getPlatform();

        DataChannelReliability rel = channel.reliability();
        boolean ordered = !rel.isUnordered();
        boolean reliable = !rel.isUnreliable();
        int maxRetransmits = rel.maxRetransmits();
        Duration maxPacketLifeTime = rel.maxPacketLifeTime() == null || rel.maxPacketLifeTime().isZero()
            ? null
            : rel.maxPacketLifeTime();

        @SuppressWarnings("unchecked")
        Consumer<?>[] cs = new Consumer[2];
        AsyncTask<RTCDataChannel> p = platform.wrapPromise((res, rej) -> {
            cs[0] = res;
            cs[1] = rej;
        });
        Consumer<RTCDataChannel> res1 = (Consumer<RTCDataChannel>) cs[0];
        Consumer<Throwable> rej1 = (Consumer<Throwable>) cs[1];

        pendingReadyRejectors.add(rej1);
        RTCDataChannel wrapper = new RTCDataChannel(
            channel.label(),
            channel.protocol(),
            ordered,
            reliable,
            maxRetransmits,
            maxPacketLifeTime
        ) {
            @Override
            public AsyncTask<RTCDataChannel> ready() {
                return p;
            }

            @Override
            public AsyncTask<Void> write(ByteBuffer message) {
                NGEPlatform platform = NGEUtils.getPlatform();
                return this.ready()
                    .compose(_ignored -> {
                        return platform.wrapPromise((res, rej) -> {
                            try {
                                boolean isDirectBuffer = message.isDirect();
                                if (!isDirectBuffer) {
                                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(message.remaining());
                                    directBuffer.put(message.duplicate());
                                    directBuffer.flip();
                                    channel.sendMessage(directBuffer);
                                } else {
                                    channel.sendMessage(message.duplicate());
                                }
                                res.accept(null);
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending message", e);
                                rej.accept(e);
                            }
                        });
                    });
            }

            @Override
            public AsyncTask<Number> getMaxMessageSize() {
                return AsyncTask.completed(channel.maxMessageSize());
            }

            @Override
            public AsyncTask<Number> getAvailableAmount() {
                return AsyncTask.completed(channel.availableAmount());
            }

            @Override
            public AsyncTask<Number> getBufferedAmount() {
                return AsyncTask.completed(channel.bufferedAmount());
            }

            @Override
            public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
                return AsyncTask.create((res, rej) -> {
                    try {
                        channel.bufferedAmountLowThreshold(threshold);
                        res.accept(null);
                    } catch (Throwable e) {
                        rej.accept(e);
                    }
                });
            }

            @Override
            public AsyncTask<Void> close() {
                return AsyncTask.create((res, rej) -> {
                    try {
                        channels.remove(channel);
                        channel.close();
                        res.accept(null);
                    } catch (Throwable e) {
                        rej.accept(e);
                    }
                });
            }
        };

        channels.put(channel, wrapper);

        Runnable completeReady = () -> {
            pendingReadyRejectors.remove(rej1);
            res1.accept(wrapper);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelReady(wrapper);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel ready", e);
                }
            }
        };

        Consumer<Exception> failReady = error -> {
            pendingReadyRejectors.remove(rej1);
            rej1.accept(error);
        };

        // timeout for good measure
        this.executor.runLater(
                () -> {
                    if (!channel.isOpen()) {
                        logger.warning("Data channel failed to open in time, closing channel");
                        try {
                            wrapper.close();
                        } catch (Exception e) {
                            logger.log(Level.FINE, "Error closing channel", e);
                        }
                        failReady.accept(new Exception("Channel open timeout"));
                    } else {
                        logger.fine("Data channel is open: " + wrapper.getName());
                        completeReady.run();
                    }
                    return null;
                },
                Objects.requireNonNull(this.settings).getP2pAttemptTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );

        channel.onError.register((c, error) -> {
            logger.log(Level.WARNING, "Channel error: " + error);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelError(wrapper, new Exception(error));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel error", e);
                }
            }
        });

        channel.onClosed.register(c -> {
            logger.fine("Channel closed: " + wrapper.getName());
            channels.remove(channel);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelClosed(wrapper);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel close", e);
                }
            }
            failReady.accept(new Exception("Channel closed"));
        });

        channel.onMessage.register(
            Message.handleBinary((c, buffer) -> {
                // copy buffer for memory safety
                // big buffer > 10MB  = native, small buffer = java heap
                ByteBuffer copy = buffer.remaining() > 10 * 1024 * 1024
                    ? NGEPlatform.get().getNativeAllocator().malloc(buffer.remaining())
                    : ByteBuffer.allocate(buffer.remaining());
                copy.put(buffer);
                copy.flip();
                buffer = copy;
                ////

                assert dbg(() -> logger.finest("Received message on channel " + wrapper.getName()));
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCBinaryMessage(wrapper, buffer);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error handling message", e);
                    }
                }
            })
        );

        channel.onBufferedAmountLow.register(c -> {
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCBufferedAmountLow(wrapper);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying buffered amount low", e);
                }
            }
        });

        channel.onOpen.register(c -> {
            logger.fine("Channel opened: " + wrapper.getName());
            completeReady.run();
        });

        if (channel.isOpen()) {
            logger.fine("Channel already opened: " + wrapper.getName());
            completeReady.run();
        }

        return p;
    }

    public String getName() {
        return connId;
    }

    private String resolveChannelLabel(String name) {
        return (name == null || name.isEmpty()) ? defaultChannelLabel() : name;
    }

    private void tryAddRemoteCandidate(RTCTransportIceCandidate candidate) {
        if (trackedRemoteCandidates.contains(candidate)) {
            return;
        }
        try {
            this.conn.addRemoteCandidate(candidate.getCandidate(), candidate.getSdpMid());
            trackedRemoteCandidates.add(candidate);
            pendingRemoteCandidates.remove(candidate);
        } catch (Throwable t) {
            if (!pendingRemoteCandidates.contains(candidate)) {
                pendingRemoteCandidates.add(candidate);
            }
        }
    }

    private void flushPendingRemoteCandidates() {
        if (pendingRemoteCandidates.isEmpty()) {
            return;
        }
        for (RTCTransportIceCandidate candidate : new ArrayList<>(pendingRemoteCandidates)) {
            tryAddRemoteCandidate(candidate);
        }
    }

    private void resolvePendingIncomingChannel(RTCDataChannel channel) {
        List<Consumer<RTCDataChannel>> waiters = pendingIncomingChannelResolvers.remove(channel.getName());
        if (waiters == null) {
            return;
        }
        for (Consumer<RTCDataChannel> waiter : waiters) {
            try {
                waiter.accept(channel);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Error resolving pending incoming channel waiter", t);
            }
        }
    }

    private AsyncTask<RTCDataChannel> awaitIncomingChannel(String label) {
        RTCDataChannel existing = getDataChannel(label);
        if (existing != null) {
            return existing.ready();
        }
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            RTCDataChannel current = getDataChannel(label);
            if (current != null) {
                current
                    .ready()
                    .catchException(rej::accept)
                    .then(channel -> {
                        res.accept(channel);
                        return null;
                    });
                return;
            }
            pendingIncomingChannelResolvers
                .computeIfAbsent(label, _k -> new CopyOnWriteArrayList<>())
                .add(channel -> {
                    channel
                        .ready()
                        .catchException(rej::accept)
                        .then(ready -> {
                            res.accept(ready);
                            return null;
                        });
                });
        });
    }

    @Override
    public AsyncTask<String> listen() {
        this.isInitiator = true;
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                this.conn.onLocalDescription.register((peer, sdp, type) -> {
                        if (type == SessionDescriptionType.OFFER) {
                            res.accept(sdp);
                        }
                    });

                DataChannelReliability reliability = DataChannelReliability.DEFAULT.withUnordered(false).withUnreliable(false);
                DataChannelInitSettings init = DataChannelInitSettings.DEFAULT.withReliability(reliability);

                String label = defaultChannelLabel();
                DataChannel nativeChannel = this.conn.createDataChannel(label, init);
                confChannel(nativeChannel).catchException(rej::accept);
            } catch (Throwable e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<RTCDataChannel> createDataChannel(
        String name,
        String protocol,
        boolean ordered,
        boolean reliable,
        int maxRetransmits,
        Duration maxPacketLifeTime
    ) {
        NGEPlatform platform = NGEUtils.getPlatform();
        String label = resolveChannelLabel(name);
        RTCDataChannel existing = getDataChannel(label);
        if (existing != null) {
            logger.fine("createDataChannel returning existing channel: " + label);
            return existing.ready();
        }

        if (!this.isInitiator) {
            logger.fine("Non-initiator createDataChannel is awaiting incoming channel: " + label);
            return awaitIncomingChannel(label);
        }

        return platform.wrapPromise((res, rej) -> {
            try {
                DataChannelReliability reliability = DataChannelReliability.DEFAULT
                    .withUnordered(!ordered)
                    .withUnreliable(!reliable);

                if (maxRetransmits >= 0) {
                    reliability = reliability.withMaxRetransmits(maxRetransmits);
                }

                if (maxPacketLifeTime != null) {
                    reliability = reliability.withMaxPacketLifeTime(maxPacketLifeTime);
                }

                DataChannelInitSettings init = DataChannelInitSettings.DEFAULT.withReliability(reliability);
                if (protocol != null) {
                    init = init.withProtocol(protocol);
                }

                DataChannel nativeChannel = this.conn.createDataChannel(label, init);
                confChannel(nativeChannel)
                    .catchException(rej::accept)
                    .then(channel -> {
                        res.accept(channel);
                        return null;
                    });
            } catch (Throwable e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<String> connect(String offerOrAnswer) {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (this.isInitiator) {
            logger.fine("Connect as initiator, use answer");
            String answer = offerOrAnswer;
            return platform.wrapPromise((res, rej) -> {
                try {
                    this.conn.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                    flushPendingRemoteCandidates();
                    res.accept(null);
                } catch (Throwable e) {
                    rej.accept(e);
                }
            });
        }

        logger.fine("Connect using offer");
        String offer = offerOrAnswer;

        return platform.wrapPromise((res, rej) -> {
            this.conn.onLocalDescription.register((peer, sdp, type) -> {
                    if (type == SessionDescriptionType.ANSWER) {
                        logger.fine("Answer ready");
                        res.accept(sdp);
                    }
                });

            this.conn.onStateChange.register((peer, state) -> {
                    if (state == PeerState.RTC_CLOSED) {
                        rej.accept(new Exception("Peer connection closed"));
                    } else if (state == PeerState.RTC_FAILED) {
                        rej.accept(new Exception("Peer connection failed"));
                    }
                });

            this.conn.setRemoteDescription(offer, SessionDescriptionType.OFFER);
            flushPendingRemoteCandidates();
        });
    }

    @Override
    public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {
        for (RTCTransportIceCandidate candidate : candidates) {
            logger.fine("Adding remote candidate: " + candidate);
            tryAddRemoteCandidate(candidate);
        }
    }

    @Override
    public void addListener(RTCTransportListener listener) {
        assert !listeners.contains(listener) : "Listener already added";
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(RTCTransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public RTCDataChannel getDataChannel(String name) {
        if (name == RTCTransport.DEFAULT_CHANNEL) name = defaultChannelLabel();
        for (RTCDataChannel channel : channels.values()) {
            if (name.equals(channel.getName())) {
                return channel;
            }
        }
        return null;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        Exception transportClosed = new Exception("Transport closed");
        for (Consumer<Throwable> rejector : new ArrayList<>(pendingReadyRejectors)) {
            try {
                rejector.accept(transportClosed);
            } catch (Exception e) {
                logger.log(Level.FINE, "Error rejecting pending ready task", e);
            }
        }
        pendingReadyRejectors.clear();

        for (DataChannel nativeChannel : new ArrayList<>(channels.keySet())) {
            try {
                nativeChannel.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing data channel", e);
            }
        }
        channels.clear();

        if (this.conn != null) {
            this.conn.close();
            this.conn = null;
        }

        this.connected = false;
    }
}
