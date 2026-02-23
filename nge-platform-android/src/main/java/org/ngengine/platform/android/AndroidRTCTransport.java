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
package org.ngengine.platform.android;

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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback.Message;
import tel.schich.libdatachannel.DataChannelInitSettings;
import tel.schich.libdatachannel.DataChannelReliability;
import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

public class AndroidRTCTransport implements RTCTransport {

    private static final Logger logger = Logger.getLogger(AndroidRTCTransport.class.getName());

    private final List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<DataChannel, RTCDataChannel> channelWrappers = new ConcurrentHashMap<>();
    private final Map<DataChannel, AsyncTask<RTCDataChannel>> channelReadyTasks = new ConcurrentHashMap<>();
    private final List<RTCTransportIceCandidate> trackedRemoteCandidates = new CopyOnWriteArrayList<>();

    private PeerConnectionConfiguration config;
    private String connId;
    private PeerConnection conn;
    private volatile boolean isInitiator;
    private AsyncExecutor executor;
    private volatile boolean connected;
    private RTCSettings settings;

    public AndroidRTCTransport() {
        logger.fine("AndroidRTCTransport initialized");
    }

    @Override
    public void start(RTCSettings settings, AsyncExecutor executor, String connId, Collection<String> stunServers) {
        this.settings = settings;
        this.executor = executor;

        Collection<URI> stunUris = new ArrayList<>();
        for (String server : stunServers) {
            try {
                stunUris.add(new URI("stun:" + server));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid STUN server URI: " + server, e);
            }
        }

        logger.finer("Using STUN servers: " + stunUris);
        this.config = PeerConnectionConfiguration.DEFAULT.withIceServers(stunUris).withDisableAutoNegotiation(false);
        this.connId = connId;
        this.conn = PeerConnection.createPeer(this.config);
        this.connected = false;

        this.conn.onIceStateChange.register((peer, state) -> {
            logger.finer("ICE state changed: " + state);
            if (state == IceState.RTC_ICE_FAILED) {
                close();
            }
        });

        this.conn.onLocalCandidate.register((peer, candidate, mediaId) -> {
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

        this.executor.runLater(
            () -> {
                if (connected) {
                    return null;
                }
                logger.warning("RTC Connection attempt timed out, closing connection");
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCDisconnected("timeout");
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error sending timeout notification", e);
                    }
                }
                close();
                return null;
            },
            settings.getP2pAttemptTimeout().toMillis(),
            TimeUnit.MILLISECONDS
        );
    }



    private RTCDataChannel wrapChannel(DataChannel nativeChannel) {
        return channelWrappers.computeIfAbsent(nativeChannel, this::createChannelWrapper);
    }

    private AsyncTask<RTCDataChannel> awaitChannelReady(DataChannel nativeChannel) {
        if (nativeChannel.isOpen()) {
            return AsyncTask.completed(wrapChannel(nativeChannel));
        }
        RTCDataChannel channel = channelWrappers.get(nativeChannel);
        if (channel != null) {
            AsyncTask<RTCDataChannel> readyTask = channel.ready();
            if (readyTask != null) {
                return readyTask;
            }
        }
        return AsyncTask.failed(new IllegalStateException("Data channel is not being opened"));
    }

    private RTCDataChannel createChannelWrapper(DataChannel nativeChannel) {
        DataChannelReliability rel = nativeChannel.reliability();
        boolean ordered = !rel.isUnordered();
        boolean reliable = !rel.isUnreliable();
        int maxRetransmits = rel.maxRetransmits();
        Duration maxPacketLifeTime = rel.maxPacketLifeTime();
        if (maxPacketLifeTime != null && maxPacketLifeTime.isZero()) {
            maxPacketLifeTime = null;
        }

        return new RTCDataChannel(
            nativeChannel.label(),
            nativeChannel.protocol(),
            ordered,
            reliable,
            maxRetransmits,
            maxPacketLifeTime
        ) {
            @Override
            public AsyncTask<RTCDataChannel> ready() {
                if (nativeChannel.isOpen()) {
                    return AsyncTask.completed(this);
                }
                AsyncTask<RTCDataChannel> readyTask = channelReadyTasks.get(nativeChannel);
                if (readyTask != null) {
                    return readyTask;
                }
                return AsyncTask.failed(new IllegalStateException("Data channel is not being opened"));
            }

            @Override
            public AsyncTask<Void> write(ByteBuffer message) {
                NGEPlatform platform = NGEUtils.getPlatform();
                return awaitChannelReady(nativeChannel).compose(_ignored -> platform.wrapPromise((res, rej) -> {
                    try {
                        if (!message.isDirect()) {
                            ByteBuffer directBuffer = ByteBuffer.allocateDirect(message.remaining());
                            directBuffer.put(message.duplicate());
                            directBuffer.flip();
                            nativeChannel.sendMessage(directBuffer);
                        } else {
                            nativeChannel.sendMessage(message);
                        }
                        res.accept(null);
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error sending message", e);
                        rej.accept(e);
                    }
                }));
            }

            @Override
            public AsyncTask<Number> getMaxMessageSize() {
                return AsyncTask.completed(nativeChannel.maxMessageSize());
            }

            @Override
            public AsyncTask<Number> getAvailableAmount() {
                return AsyncTask.completed(nativeChannel.availableAmount());
            }

            @Override
            public AsyncTask<Number> getBufferedAmount() {
                return AsyncTask.completed(nativeChannel.bufferedAmount());
            }

            @Override
            public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
                return AsyncTask.create((res, rej) -> {
                    try {
                        nativeChannel.bufferedAmountLowThreshold(threshold);
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
                        removeChannelReferences(nativeChannel);
                        nativeChannel.close();
                        res.accept(null);
                    } catch (Throwable e) {
                        rej.accept(e);
                    }
                });
            }
        };
    }

    private void removeChannelReferences(DataChannel nativeChannel) {
        channelReadyTasks.remove(nativeChannel);
        channelWrappers.remove(nativeChannel);
    }

    private AsyncTask<RTCDataChannel> configureChannel(DataChannel nativeChannel) {
        NGEPlatform platform = NGEUtils.getPlatform();
        RTCDataChannel wrapper = wrapChannel(nativeChannel);

        nativeChannel.onError.register((c, error) -> {
            logger.log(Level.WARNING, "Channel error: " + error);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelError(wrapper, new Exception(error));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel error", e);
                }
            }
        });

        nativeChannel.onClosed.register(c -> {
            logger.fine("Channel closed: " + wrapper.getName());
            removeChannelReferences(nativeChannel);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelClosed(wrapper);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel close", e);
                }
            }
        });

        nativeChannel.onMessage.register(
            Message.handleBinary((c, buffer) -> {
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

        AsyncTask<RTCDataChannel> readyTask = platform.wrapPromise((res, rej) -> {
            if (nativeChannel.isOpen()) {
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCChannelReady(wrapper);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error notifying channel ready", e);
                    }
                }
                res.accept(wrapper);
                return;
            }

            this.executor.runLater(
                () -> {
                    if (!nativeChannel.isOpen()) {
                        logger.warning("Data channel failed to open in time, closing channel");
                        wrapper.close().catchException(e -> logger.log(Level.FINE, "Error closing channel", e));
                        rej.accept(new Exception("Channel open timeout"));
                    }
                    return null;
                },
                Objects.requireNonNull(this.settings).getP2pAttemptTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );

            nativeChannel.onOpen.register(c -> {
                logger.fine("Channel opened: " + wrapper.getName());
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCChannelReady(wrapper);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error notifying channel ready", e);
                    }
                }
                res.accept(wrapper);
            });
        });
        channelReadyTasks.put(nativeChannel, readyTask);
        return readyTask;
    }

    @Override
    public AsyncTask<String> createChannel(
        String name,
        String protocol,
        boolean ordered,
        boolean reliable,
        int maxRetransmits,
        Duration maxPacketLifeTime
    ) {
        this.isInitiator = true;
        NGEPlatform platform = NGEUtils.getPlatform();

        return platform.wrapPromise((res, rej) -> {
            try {
                this.conn.onLocalDescription.register((peer, sdp, type) -> {
                    if (type == SessionDescriptionType.OFFER) {
                        res.accept(sdp);
                    }
                });

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

                String label = (name == null || name.isEmpty()) ? ("nostr4j-" + connId) : name;
                DataChannel nativeChannel = this.conn.createDataChannel(label, init);
                configureChannel(nativeChannel).catchException(rej::accept);
            } catch (Throwable e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<RTCDataChannel> connect(String offerOrAnswer) {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (this.isInitiator) {
            logger.fine("Connect as initiator, use answer");
            String answer = offerOrAnswer;
            return platform.wrapPromise((res, rej) -> {
                try {
                    this.conn.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                    res.accept(null);
                } catch (Throwable e) {
                    rej.accept(e);
                }
            });
        }

        logger.fine("Connect using offer");
        String offer = offerOrAnswer;

        AsyncTask<RTCDataChannel> openChannel = platform.wrapPromise((res, rej) -> {
            this.conn.onDataChannel.register((peer, nativeChannel) -> {
                configureChannel(nativeChannel)
                    .catchException(rej::accept)
                    .then(channel -> {
                        res.accept(channel);
                        return null;
                    });
            });
        });

        return platform.wrapPromise((res, rej) -> {
            this.conn.onLocalDescription.register((peer, sdp, type) -> {
                if (type == SessionDescriptionType.ANSWER) {
                    openChannel
                        .catchException(rej::accept)
                        .then(channel -> {
                            res.accept(channel);
                            return null;
                        });
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
        });
    }

    @Override
    public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {
        for (RTCTransportIceCandidate candidate : candidates) {
            if (!trackedRemoteCandidates.contains(candidate)) {
                this.conn.addRemoteCandidate(candidate.getCandidate(), candidate.getSdpMid());
                logger.fine("Adding remote candidate: " + candidate);
                trackedRemoteCandidates.add(candidate);
            }
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
        if (name == RTCTransport.DEFAULT_CHANNEL) name = "nostr4j-" + connId;
        for (RTCDataChannel channel : channelWrappers.values()) {
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
        for (DataChannel nativeChannel : new ArrayList<>(channelWrappers.keySet())) {
            try {
                nativeChannel.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing data channel", e);
            }
        }
        channelReadyTasks.clear();
        channelWrappers.clear();

        if (this.conn != null) {
            this.conn.close();
            this.conn = null;
        }

        this.connected = false;
    }
}
