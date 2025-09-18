package org.ngengine.platform.teavm;

import org.teavm.jso.JSByRef;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.Uint8Array;

public interface BytesWrapper extends JSObject{
    @JSProperty("data")
    Uint8Array uint8();

    @JSProperty("data")
    byte[] bytes();

    // WORKAROUND
    public default byte[] getData(){
        try{
            byte[] data = bytes();
            if(data==null||data.length==0){
                throw new Throwable("data is null or empty");
            }
            return data;
        } catch (Throwable e){
            System.out.println("Fallback to slow path");
            Uint8Array arr = uint8();
            byte[] data = new byte[arr.getLength()];
            for(int i=0;i<arr.getLength();i++){
                data[i] = (byte)arr.get(i);
            }
            return data;
        }
    }
    
}
