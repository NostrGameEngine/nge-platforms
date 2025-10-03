/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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

    private AsyncTask<ReadableStreamReadResult> fetch() {
        return NGEPlatform
            .get()
            .wrapPromise((Consumer<ReadableStreamReadResult> res, Consumer<Throwable> rej) -> {
                JSPromise<ReadableStreamReadResult> p = getReader().read();
                p.catchError(e -> {
                    rej.accept(new Exception(e.toString()));
                    return null;
                });
                p.then(rx -> {
                    res.accept((ReadableStreamReadResult) rx);
                    return null;
                });
            });
    }

    @Override
    public void close() {
        try {
            getReader()
                .cancel(JSString.valueOf("Closed"))
                .catchError(e -> {
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
            while (buffer == null || bufferPos >= bufferLength) {
                ReadableStreamReadResult r = fetch().await();
                if (r.isDone()) {
                    return -1;
                }
                Int8Array val = r.getValue();
                if (val != null) {
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
