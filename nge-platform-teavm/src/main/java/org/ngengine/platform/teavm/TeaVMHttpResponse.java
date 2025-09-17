package org.ngengine.platform.teavm;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public interface TeaVMHttpResponse extends JSObject {
    @JSProperty("headers")
    String getHeaders();

    @JSProperty("status")
    int getStatus();

    @JSProperty("statusText")
    String getStatusText();


    @JSProperty("body")
    byte[] getBody();

    
}
