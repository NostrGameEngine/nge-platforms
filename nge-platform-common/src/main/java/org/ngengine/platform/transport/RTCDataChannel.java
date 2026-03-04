package org.ngengine.platform.transport;

import java.nio.ByteBuffer;
import java.time.Duration;

import org.ngengine.platform.AsyncTask;

public abstract class RTCDataChannel {

    private final String name;
    private final String protocol;
    private final boolean ordered;
    private final boolean reliable;
    private final int maxRetransmits;
    private final Duration maxPacketLifeTime;

    public RTCDataChannel(
        String name,
        String protocol,
        boolean ordered,
        boolean reliable,
        int maxRetransmits,
        Duration maxPacketLifeTime
    ) {
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