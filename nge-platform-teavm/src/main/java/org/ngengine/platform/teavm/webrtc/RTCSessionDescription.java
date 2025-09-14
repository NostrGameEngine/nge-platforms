package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;


public interface RTCSessionDescription extends JSObject {
    @JSProperty
    String getType();
    
    @JSProperty
    String getSdp();
}