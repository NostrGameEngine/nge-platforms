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
package org.ngengine.platform.transport;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.RTCSettings;

public interface RTCTransport extends Closeable {
    public static final String DEFAULT_CHANNEL = null;
    void close();
    boolean isConnected();

    void start(RTCSettings settings, AsyncExecutor executor, String connId, Collection<String> stunServers);
    AsyncTask<RTCDataChannel> connect(String offerOrAnswer);
    AsyncTask<String> createChannel(String name, String protocol, boolean ordered, boolean reliable, int maxRetransmits, Duration maxPacketLifeTime);

    void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates);

    void addListener(RTCTransportListener listener);

    void removeListener(RTCTransportListener listener);

    RTCDataChannel getDataChannel(String name);

    /**
     * @deprecated use createDefaultChannel() instead 
     * @return
     */
    @Deprecated
    default AsyncTask<String> initiateChannel() {
        return createDefaultChannel();
    }

    /** initialize default channel */
    default AsyncTask<String> createDefaultChannel() {
        return createChannel(DEFAULT_CHANNEL, null, true, true, 0, null);
    }
  
    /**
     * Writes to the default channel
     * @param message
     * @return
     */
    default AsyncTask<Void> write(ByteBuffer message) {
        RTCDataChannel channel = getDataChannel(RTCTransport.DEFAULT_CHANNEL);
        if (channel == null) {
            return AsyncTask.failed(new IllegalStateException("Default RTC data channel not found"));
        }
        return channel.ready().compose(ch -> ch.write(message));
    }

    public static abstract class RTCDataChannel {
        private final String name;
        private final String protocol;
        private final boolean ordered;
        private final boolean reliable;
        private final int maxRetransmits;
        private final Duration maxPacketLifeTime;

        public RTCDataChannel(String name, String protocol, boolean ordered, boolean reliable, int maxRetransmits, Duration maxPacketLifeTime) {
            this.name = name;
            this.protocol = protocol;
            this.ordered = ordered;
            this.reliable = reliable;
            this.maxRetransmits = maxRetransmits;
            this.maxPacketLifeTime = maxPacketLifeTime;
        }

        public abstract AsyncTask<RTCDataChannel> ready();

        public String getProtocol() {
            return protocol;
        }

        public boolean isOrdered() {
            return ordered;
        }

        public boolean isReliable() {
            return reliable;
        }

        public int getMaxRetransmits() {
            return maxRetransmits;
        }
        public Duration getMaxPacketLifeTime() {
            return maxPacketLifeTime;
        }



        public String getName() {
            return name;
        }
        public abstract AsyncTask<Void> write(ByteBuffer message);
 
        public abstract AsyncTask<Number> getMaxMessageSize();
        public abstract AsyncTask<Number> getAvailableAmount();
        public abstract AsyncTask<Number> getBufferedAmount();
        public abstract AsyncTask<Void> setBufferedAmountLowThreshold(int threshold);
        public abstract AsyncTask<Void> close();
    }
}
