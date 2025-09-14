package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;

public interface RTCMessageCallback extends JSObject {
    void handleEvent(RTCMessageEvent event);
}
