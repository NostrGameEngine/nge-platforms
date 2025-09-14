package org.ngengine.platform.teavm.webrtc;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public interface RTCErrorEvent extends JSObject {
    @JSProperty
    String getError();
}