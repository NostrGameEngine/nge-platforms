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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.teavm.webrtc.*;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

public class TeaVMRTCTransport implements RTCTransport {

    private static final Logger logger = Logger.getLogger(TeaVMRTCTransport.class.getName());

    private List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private String connId;
    private volatile boolean isInitiator;
    private List<RTCTransportIceCandidate> trackedRemoteCandidates = new CopyOnWriteArrayList<>();

    private volatile boolean connected = false;
    private RTCPeerConnection peerConnection;
    private RTCDataChannel dataChannel;
    private AsyncExecutor asyncExecutor;

    public TeaVMRTCTransport() {}

    @Override
    public void start(RTCSettings settings, AsyncExecutor executor, String connId, Collection<String> stunServers) {
        this.connId = connId;
        this.asyncExecutor = executor;

        String[] iceUrls = stunServers.stream().map(server -> "stun:" + server).toArray(String[]::new);

        logger.finer("Using STUN servers: " + stunServers);

        this.peerConnection = TeaVMBinds.rtcCreatePeerConnection(iceUrls);
        logger.finer("RTCPeerConnection created with ID: " + connId);

        this.peerConnection.setOnIceCandidateHandler(event -> {
                RTCIceCandidate candidate = event.getCandidate();
                if (candidate != null) {
                    logger.fine("Local ICE candidate: " + candidate.getCandidate());
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onLocalRTCIceCandidate(
                                new RTCTransportIceCandidate(candidate.getCandidate(), candidate.getSdpMid())
                            );
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error sending local candidate", e);
                        }
                    }
                } else {
                    logger.fine("ICE candidate gathering complete");
                }
            });

        this.peerConnection.setOnIceConnectionStateChangeHandler(() -> {
                String state = peerConnection.getIceConnectionState();
                logger.finer("ICE connection state changed: " + state);

                if ("failed".equals(state)) {
                    this.close();
                }
            });

        this.peerConnection.setOnConnectionStateChangeHandler(() -> {
                String state = peerConnection.getConnectionState();
                logger.fine("Connection state changed: " + state);

                if ("connected".equals(state)) {
                    this.connected = true;
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
                this.dataChannel = event.getChannel();
                this.setupDataChannel(this.dataChannel);
            });

        this.asyncExecutor.runLater(
                () -> {
                    if (!connected) {
                        logger.warning("RTC Connection attempt timed out, closing connection");
                        for (RTCTransportListener listener : listeners) {
                            try {
                                listener.onRTCDisconnected("timeout");
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending timeout notification", e);
                            }
                        }
                        this.close();
                    }
                    return null;
                },
                settings.getP2pAttemptTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    private void setupDataChannel(RTCDataChannel channel) {
        channel.setBinaryType("arraybuffer");
        channel.setOnOpenHandler(() -> {
            logger.fine("Data channel opened");
            this.connected = true;
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCConnected();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying connection", e);
                }
            }
        });

        channel.setOnCloseHandler(() -> {
            logger.fine("Data channel closed");
            this.connected = false;
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCDisconnected("channel_closed");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying disconnect", e);
                }
            }
        });

        channel.setOnErrorHandler(error -> {
            logger.log(Level.WARNING, "Data channel error: " + error.getError());
            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCChannelError(new Exception(error.getError()));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying channel error", e);
                }
            }
        });

        TeaVMBinds.rtcSetOnMessageHandler(channel, buffer -> {
            assert dbg(() -> {
                logger.finest("Received message");
            });

            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

            for (RTCTransportListener listener : listeners) {
                try {
                    listener.onRTCBinaryMessage(byteBuffer);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling message", e);
                }
            }
        });
    }

    @Override
    public AsyncTask<String> initiateChannel() {
        this.isInitiator = true;
        NGEPlatform platform = NGEUtils.getPlatform();

        return platform.promisify(
            (res, rej) -> {
                try {
                    this.dataChannel = this.peerConnection.createDataChannel("nostr4j-" + this.connId);
                    setupDataChannel(this.dataChannel);
                    RTCSessionDescription offer = TeaVMBindsAsync.rtcCreateOffer(this.peerConnection);
                    TeaVMBindsAsync.rtcSetLocalDescription(this.peerConnection, offer.getSdp(), "offer");
                    res.accept(offer.getSdp());
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            this.asyncExecutor
        );
    }

    @Override
    public AsyncTask<String> connectToChannel(String offerOrAnswer) {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (this.isInitiator) {
            // We're the initiator, so this is an answer
            logger.fine("Connect as initiator, use answer");
            String answer = offerOrAnswer;

            return platform.promisify(
                (res, rej) -> {
                    try {
                        TeaVMBindsAsync.rtcSetRemoteDescription(this.peerConnection, answer, "answer");
                        res.accept(null);
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                this.asyncExecutor
            );
        } else {
            // We're not the initiator, so this is an offer
            logger.fine("Connect using offer");
            String offer = offerOrAnswer;

            return platform.promisify(
                (res, rej) -> {
                    try {
                        TeaVMBindsAsync.rtcSetRemoteDescription(this.peerConnection, offer, "offer");
                        RTCSessionDescription answer = TeaVMBindsAsync.rtcCreateAnswer(this.peerConnection);
                        TeaVMBindsAsync.rtcSetLocalDescription(this.peerConnection, answer.getSdp(), "answer");
                        res.accept(answer.getSdp());
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                this.asyncExecutor
            );
        }
    }

    @Override
    public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {
        for (RTCTransportIceCandidate candidate : candidates) {
            if (!trackedRemoteCandidates.contains(candidate)) {
                logger.fine("Adding remote candidate: " + candidate);
                try {
                    RTCIceCandidate iceCandidate = TeaVMBinds.rtcCreateIceCandidate(
                        candidate.getCandidate(),
                        candidate.getSdpMid()
                    );
                    this.peerConnection.addIceCandidate(iceCandidate);
                    trackedRemoteCandidates.add(candidate);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error adding ICE candidate", e);
                }
            }
        }
    }

    @Override
    public AsyncTask<Void> write(ByteBuffer message) {
        NGEPlatform platform = NGEUtils.getPlatform();

        return platform.promisify(
            (res, rej) -> {
                try {
                    if (this.dataChannel == null || !this.connected) {
                        rej.accept(new Exception("Data channel not open"));
                        return;
                    }

                    this.dataChannel.send(message);

                    res.accept(null);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error sending message", e);
                    rej.accept(e);
                }
            },
            this.asyncExecutor
        );
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
    public boolean isConnected() {
        return this.connected;
    }

    @Override
    public void close() {
        if (this.dataChannel != null) {
            this.dataChannel.close();
            this.dataChannel = null;
        }

        if (this.peerConnection != null) {
            this.peerConnection.close();
            this.peerConnection = null;
        }

        this.connected = false;
        this.asyncExecutor.close();
    }
}
