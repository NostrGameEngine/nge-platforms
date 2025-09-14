package org.ngengine.platform.teavm.webrtc;
import java.nio.Buffer;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public interface RTCDataChannel extends JSObject {
    @JSProperty
    String getLabel();

    @JSProperty
    String getReadyState();

    void send(Buffer data);

    void close();

    @JSProperty("onopen")
    void setOnOpenHandler(RTCStateChangeCallback callback);

    @JSProperty("onclose")
    void setOnCloseHandler(RTCStateChangeCallback callback);

    @JSProperty("onerror")
    void setOnErrorHandler(RTCErrorCallback callback);

    @JSProperty("onmessage")
    void setOnMessageHandler(RTCMessageCallback callback);
}