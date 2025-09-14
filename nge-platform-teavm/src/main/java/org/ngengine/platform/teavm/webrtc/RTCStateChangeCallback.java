package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;


public interface RTCStateChangeCallback extends JSObject {
    void handleEvent();
}