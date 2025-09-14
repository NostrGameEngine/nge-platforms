package org.ngengine.platform.teavm.webrtc;
import org.ngengine.platform.teavm.TeaVMBinds;
import org.ngengine.platform.teavm.TeaVMBindsAsync;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public class RTCPeerConnection implements JSObject {


    public native RTCDataChannel createDataChannel(String label);

    
    public RTCSessionDescription createOffer() {
        return TeaVMBindsAsync.rtcCreateOffer(this);
    }
    
    public RTCSessionDescription createAnswer() {
        return TeaVMBindsAsync.rtcCreateAnswer(this);
    }

    public void setLocalDescription(String sdp, String type){
        TeaVMBindsAsync.rtcSetLocalDescription(this, sdp, type);
    }
    
    public void setRemoteDescription(String sdp, String type){
        TeaVMBindsAsync.rtcSetRemoteDescription(this, sdp, type);
    }

    public native void addIceCandidate(RTCIceCandidate candidate);

    public native void close();

    @JSProperty
    public native String getConnectionState();

    @JSProperty
    public native String getIceConnectionState();

    @JSProperty("onicecandidate")
    public native void setOnIceCandidateHandler(RTCIceCandidateCallback callback);

    @JSProperty("oniceconnectionstatechange")
    public native void setOnIceConnectionStateChangeHandler(RTCStateChangeCallback callback);

    @JSProperty("onconnectionstatechange")
    public native void setOnConnectionStateChangeHandler(RTCStateChangeCallback callback);

    @JSProperty("ondatachannel")
    public native void setOnDataChannelHandler(RTCDataChannelCallback callback);
}