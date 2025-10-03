package org.ngengine.platform.teavm;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;
import org.teavm.jso.streams.ReadableStream;
import org.teavm.jso.streams.ReadableStreamDefaultReader;
import org.teavm.jso.streams.ReadableStreamReadResult;
import org.teavm.jso.typedarrays.Int8Array;

public class TeaVMReadableStreamWrapperInputStream extends InputStream {
    private final ReadableStream stream;
    private ReadableStreamDefaultReader reader;
    private Int8Array buffer = null;
    private int bufferPos = 0;
    private int bufferLength = 0;
    // private AsyncTask<ReadableStreamReadResult> fetching;
    private boolean done = false;

    public TeaVMReadableStreamWrapperInputStream(ReadableStream stream) {
        this.stream = stream;
    }

    private ReadableStreamDefaultReader getReader() {
        if (reader == null) {
            reader = stream.getReader();
        }
        return reader;
    }

    private AsyncTask<ReadableStreamReadResult> fetch(){
        return NGEPlatform.get().wrapPromise((Consumer<ReadableStreamReadResult> res, Consumer<Throwable> rej) -> {
            JSPromise<ReadableStreamReadResult> p = getReader().read();
            p.catchError(e -> {
                rej.accept(new Exception(e.toString()));
                return null;
            });
            p.then(rx -> {
                res.accept((ReadableStreamReadResult)rx);
                return null;
            });
        });
    }

    @Override
    public void close(){
        try {            
            getReader().cancel(JSString.valueOf("Closed")).catchError(e -> {
                // Ignore
                return null;
            });
            getReader().releaseLock();
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public synchronized int read() throws IOException {
        try {
            while(buffer==null||bufferPos>=bufferLength) {
                ReadableStreamReadResult r = fetch().await();
                if (r.isDone()) {
                    return -1;
                }
                Int8Array val = r.getValue();
                if(val!=null){
                    bufferLength = val.getLength();
                    buffer = val;
                } 
           
                bufferPos = 0;
            }
            int v = buffer.get(bufferPos) & 0xFF;
            bufferPos++;
            return v;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
 
    
}
