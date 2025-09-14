package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;

public interface RTCDataChannelCallback extends JSObject {
    void handleEvent(RTCDataChannelEvent event);
}