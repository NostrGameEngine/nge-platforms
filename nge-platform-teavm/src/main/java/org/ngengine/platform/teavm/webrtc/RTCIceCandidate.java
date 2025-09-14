package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;


public interface RTCIceCandidate extends JSObject {
    @JSProperty
    String getCandidate();

 
}
