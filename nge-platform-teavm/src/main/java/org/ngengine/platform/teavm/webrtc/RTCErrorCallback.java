package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;


public interface RTCErrorCallback extends JSObject {
    void handleEvent(RTCErrorEvent event);
}
