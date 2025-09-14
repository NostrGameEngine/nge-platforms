package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;

public interface RTCIceCandidateCallback extends JSObject {
    void handleEvent(RTCIceCandidateEvent event);
}
