package org.ngengine.platform.jvm;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

import tel.schich.libdatachannel.LibDataChannel;
import tel.schich.libdatachannel.LibDataChannelArchDetect;

public class TestLibDatachannelInitialization {
    @Test
    public void libDataChannelLoadsWithoutError() {
        LibDataChannelArchDetect.initialize();
    }

    @Test
    public void libDataAllocator() throws NoSuchMethodException, SecurityException, IllegalAccessException, InvocationTargetException {
        LibDataChannelArchDetect.initialize();
        Method m = LibDataChannel.class.getDeclaredMethod("getInnerAllocatorNative");
        m.setAccessible(true);
        String alloc = (String) m.invoke(null);
        System.out.println("LibDataChannel inner allocator: " + alloc);
        assertTrue("unexpected allocator: " + alloc, alloc != null && alloc.toLowerCase().contains("mimalloc"));
        
    }

    
}
