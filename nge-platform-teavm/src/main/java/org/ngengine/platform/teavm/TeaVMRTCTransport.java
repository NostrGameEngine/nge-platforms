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

import static org.ngengine.platform.NGEUtils.dbg;

import java.nio.ByteBuffer;
import java.time.Duration;
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
import org.ngengine.platform.teavm.webrtc.RTCDataChannelEvent;
import org.ngengine.platform.teavm.webrtc.RTCIceCandidate;
import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

public class TeaVMRTCTransport implements RTCTransport {

    private static final Logger logger = Logger.getLogger(TeaVMRTCTransport.class.getName());

    private final List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final List<RTCTransportIceCandidate> trackedRemoteCandidates = new CopyOnWriteArrayList<>();
    private final List<Consumer<RTCDataChannel>> pendingIncomingChannelResolvers = new CopyOnWriteArrayList<>();
    private final Map<org.ngengine.platform.teavm.webrtc.RTCDataChannel, RTCDataChannel> channelWrappers =
        new ConcurrentHashMap<>();
    private final Map<org.ngengine.platform.teavm.webrtc.RTCDataChannel, AsyncTask<RTCDataChannel>> channelReadyTasks =
        new ConcurrentHashMap<>();

    private String connId;
    private volatile boolean isInitiator;
    private volatile boolean connected;
    private RTCPeerConnection peerConnection;
    private AsyncExecutor asyncExecutor;
    private RTCSettings settings;

    public TeaVMRTCTransport() {}

    @Override
    public void start(RTCSettings settings, AsyncExecutor executor, String connId, Collection<String> stunServers) {
        this.settings = settings;
        this.connId = connId;
        this.asyncExecutor = executor;
        this.connected = false;

        String[] iceUrls = stunServers.stream().map(server -> "stun:" + server).toArray(String[]::new);

        logger.finer("Using STUN servers: " + stunServers);

        this.peerConnection = TeaVMBinds.rtcCreatePeerConnection(iceUrls);
        logger.finer("RTCPeerConnection created with ID: " + connId);

        this.peerConnection.setOnIceCandidateHandler(event -> {
            RTCIceCandidate candidate = event.getCandidate();
            if (candidate == null) {
                logger.fine("ICE candidate gathering complete");
                return;
            }
            logger.fine("Local ICE candidate: " + candidate.getCandidate());
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onLocalRTCIceCandidate(new RTCTransportIceCandidate(candidate.getCandidate(), candidate.getSdpMid()));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error sending local candidate", e);
                }
            }
        });

        this.peerConnection.setOnIceConnectionStateChangeHandler(() -> {
            String state = peerConnection.getIceConnectionState();
            logger.finer("ICE connection state changed: " + state);
            if ("failed".equals(state)) {
                close();
            }
        });

        this.peerConnection.setOnConnectionStateChangeHandler(() -> {
            String state = peerConnection.getConnectionState();
            logger.fine("Connection state changed: " + state);

            if ("connected".equals(state)) {
                this.connected = true;
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCConnected();
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error notifying connection", e);
                    }
                }
            } else if ("disconnected".equals(state) || "failed".equals(state) || "closed".equals(state)) {
                this.connected = false;
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCDisconnected(state);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error notifying disconnect", e);
                    }
                }
            }
        });

        this.peerConnection.setOnDataChannelHandler(event -> {
            org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel = event.getChannel();
            configureChannel(nativeChannel)
                .catchException(e -> logger.log(Level.WARNING, "Error configuring channel", e))
                .then(channel -> {
                    if (!pendingIncomingChannelResolvers.isEmpty()) {
                        Consumer<RTCDataChannel> waiter = pendingIncomingChannelResolvers.remove(0);
                        waiter.accept(channel);
                    }
                    return null;
                });
        });

        this.asyncExecutor.runLater(
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


    private RTCDataChannel wrapChannel(org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel) {
        String protocol = TeaVMBinds.rtcDataChannelGetProtocol(nativeChannel);
        boolean ordered = TeaVMBinds.rtcDataChannelIsOrdered(nativeChannel);
        boolean reliable = TeaVMBinds.rtcDataChannelIsReliable(nativeChannel);
        int maxRetransmits = TeaVMBinds.rtcDataChannelGetMaxRetransmits(nativeChannel);
        int maxPacketLifeTimeMs = TeaVMBinds.rtcDataChannelGetMaxPacketLifeTime(nativeChannel);
        Duration maxPacketLifeTime = maxPacketLifeTimeMs >= 0 ? Duration.ofMillis(maxPacketLifeTimeMs) : null;

        return channelWrappers.computeIfAbsent(nativeChannel, channel -> new RTCDataChannel(
            channel.getLabel(),
            protocol,
            ordered,
            reliable,
            maxRetransmits,
            maxPacketLifeTime
        ) {
            @Override
            public AsyncTask<RTCDataChannel> ready() {
                if ("open".equals(nativeChannel.getReadyState())) {
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
                return awaitChannelReady(nativeChannel).compose(_ignored -> platform.promisify((res, rej) -> {
                    try {
                        channel.send(message);
                        res.accept(null);
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error sending message", e);
                        rej.accept(e);
                    }
                }, asyncExecutor));
            }

            @Override
            public AsyncTask<Number> getMaxMessageSize() {
                return AsyncTask.completed(TeaVMBinds.rtcGetMaxMessageSize(peerConnection));
            }

            @Override
            public AsyncTask<Number> getAvailableAmount() {
                return AsyncTask.completed(TeaVMBinds.rtcDataChannelGetAvailableAmount(peerConnection, channel));
            }

            @Override
            public AsyncTask<Number> getBufferedAmount() {
                return AsyncTask.completed(TeaVMBinds.rtcDataChannelGetBufferedAmount(channel));
            }

            @Override
            public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
                return AsyncTask.create((res, rej) -> {
                    try {
                        TeaVMBinds.rtcDataChannelSetBufferedAmountLowThreshold(channel, threshold);
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
                        removeChannelReferences(channel);
                        channel.close();
                        res.accept(null);
                    } catch (Throwable e) {
                        rej.accept(e);
                    }
                });
            }
        });
    }

    private void removeChannelReferences(org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel) {
        channelReadyTasks.remove(nativeChannel);
        channelWrappers.remove(nativeChannel);
    }

    private AsyncTask<RTCDataChannel> awaitChannelReady(org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel) {
        if ("open".equals(nativeChannel.getReadyState())) {
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

    private AsyncTask<RTCDataChannel> configureChannel(org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel) {
        NGEPlatform platform = NGEUtils.getPlatform();
        RTCDataChannel wrapper = wrapChannel(nativeChannel);

        nativeChannel.setBinaryType("arraybuffer");

        nativeChannel.setOnCloseHandler(() -> {
            logger.fine("Data channel closed: " + wrapper.getName());
            removeChannelReferences(nativeChannel);
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelClosed(wrapper);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel close", e);
                }
            }
        });

        nativeChannel.setOnErrorHandler(error -> {
            logger.log(Level.WARNING, "Data channel error: " + error.getError());
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelError(wrapper, new Exception(error.getError()));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel error", e);
                }
            }
        });

        TeaVMBinds.rtcSetOnMessageHandler(
            nativeChannel,
            buffer -> {
                assert dbg(() -> logger.finest("Received message on channel " + wrapper.getName()));
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCBinaryMessage(wrapper, byteBuffer);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error handling message", e);
                    }
                }
            }
        );

        AsyncTask<RTCDataChannel> readyTask = platform.promisify((res, rej) -> {
            try {
                if ("open".equals(nativeChannel.getReadyState())) {
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

                this.asyncExecutor.runLater(
                    () -> {
                        if (!"open".equals(nativeChannel.getReadyState())) {
                            logger.warning("Data channel failed to open in time, closing channel");
                            wrapper.close().catchException(e -> logger.log(Level.FINE, "Error closing channel", e));
                            rej.accept(new Exception("Channel open timeout"));
                        }
                        return null;
                    },
                    Objects.requireNonNull(this.settings).getP2pAttemptTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
                );

                nativeChannel.setOnOpenHandler(() -> {
                    logger.fine("Data channel opened: " + wrapper.getName());
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onRTCChannelReady(wrapper);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error notifying channel ready", e);
                        }
                    }
                    res.accept(wrapper);
                });
            } catch (Throwable e) {
                rej.accept(e);
            }
        }, this.asyncExecutor);
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

        return platform.promisify((res, rej) -> {
            try {
                int maxPacketLifeTimeMs = maxPacketLifeTime == null ? -1 : (int) maxPacketLifeTime.toMillis();
                org.ngengine.platform.teavm.webrtc.RTCDataChannel nativeChannel = TeaVMBinds.rtcCreateDataChannel(
                    this.peerConnection,
                    (name == null || name.isEmpty()) ? ("nostr4j-" + this.connId) : name,
                    protocol,
                    ordered,
                    reliable,
                    maxRetransmits,
                    maxPacketLifeTimeMs
                );
                configureChannel(nativeChannel).catchException(e -> logger.log(Level.WARNING, "Error configuring channel", e));

                RTCSessionDescription offer = TeaVMBindsAsync.rtcCreateOffer(this.peerConnection);
                TeaVMBindsAsync.rtcSetLocalDescription(this.peerConnection, offer.getSdp(), "offer");
                res.accept(offer.getSdp());
            } catch (Throwable e) {
                rej.accept(e);
            }
        }, this.asyncExecutor);
    }

    @Override
    public AsyncTask<RTCDataChannel> connect(String offerOrAnswer) {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (this.isInitiator) {
            logger.fine("Connect as initiator, use answer");
            return platform.promisify((res, rej) -> {
                try {
                    TeaVMBindsAsync.rtcSetRemoteDescription(this.peerConnection, offerOrAnswer, "answer");
                    res.accept(null);
                } catch (Throwable e) {
                    rej.accept(e);
                }
            }, this.asyncExecutor);
        }

        logger.fine("Connect using offer");
        return platform.promisify((res, rej) -> {
            try {
                TeaVMBindsAsync.rtcSetRemoteDescription(this.peerConnection, offerOrAnswer, "offer");
                RTCSessionDescription answer = TeaVMBindsAsync.rtcCreateAnswer(this.peerConnection);
                TeaVMBindsAsync.rtcSetLocalDescription(this.peerConnection, answer.getSdp(), "answer");
                logger.warning("RTCTransport.connect(offer) generated an SDP answer that cannot be returned with current RTCTransport API");

                if (!channelWrappers.isEmpty()) {
                    res.accept(channelWrappers.values().iterator().next());
                    return;
                }

                pendingIncomingChannelResolvers.add(channel -> res.accept(channel));
            } catch (Throwable e) {
                rej.accept(e);
            }
        }, this.asyncExecutor);
    }

    @Override
    public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {
        for (RTCTransportIceCandidate candidate : candidates) {
            if (!trackedRemoteCandidates.contains(candidate)) {
                logger.fine("Adding remote candidate: " + candidate);
                try {
                    RTCIceCandidate iceCandidate = TeaVMBinds.rtcCreateIceCandidate(candidate.getCandidate(), candidate.getSdpMid());
                    this.peerConnection.addIceCandidate(iceCandidate);
                    trackedRemoteCandidates.add(candidate);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error adding ICE candidate", e);
                }
            }
        }
    }

    @Override
    public void addListener(RTCTransportListener listener) {
        if (listener != null && !listeners.contains(listener)) {
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
        return this.connected;
    }

    @Override
    public void close() {
        for (org.ngengine.platform.teavm.webrtc.RTCDataChannel channel : channelWrappers.keySet()) {
            try {
                channel.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing data channel", e);
            }
        }
        channelReadyTasks.clear();
        channelWrappers.clear();

        if (this.peerConnection != null) {
            this.peerConnection.close();
            this.peerConnection = null;
        }

        this.connected = false;
        if (this.asyncExecutor != null) {
            this.asyncExecutor.close();
        }
    }
}
