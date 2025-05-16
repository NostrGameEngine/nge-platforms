package org.ngengine.platform.teavm;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportListener;

public class TeaVMRTCTransport implements RTCTransport{
    // TODO

    @Override
    public void close() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'close'");
    }

    @Override
    public boolean isConnected() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isConnected'");
    }

    @Override
    public AsyncTask<Void> start(RTCSettings settings, AsyncExecutor executor, String connId,
            Collection<String> stunServers) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'start'");
    }

    @Override
    public AsyncTask<String> connectToChannel(String offerOrAnswer) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'connectToChannel'");
    }

    @Override
    public AsyncTask<String> initiateChannel() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'initiateChannel'");
    }

    @Override
    public void addRemoteIceCandidates(Collection<String> candidates) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addRemoteIceCandidates'");
    }

    @Override
    public void addListener(RTCTransportListener listener) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addListener'");
    }

    @Override
    public void removeListener(RTCTransportListener listener) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'removeListener'");
    }

    @Override
    public AsyncTask<Void> write(ByteBuffer message) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'write'");
    }
    
}
