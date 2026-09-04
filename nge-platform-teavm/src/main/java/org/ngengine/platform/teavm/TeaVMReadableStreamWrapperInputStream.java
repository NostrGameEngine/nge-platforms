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
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
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
    private boolean done = false;
    private boolean closed = false;

    public TeaVMReadableStreamWrapperInputStream(ReadableStream stream) {
        this.stream = stream;
    }

    private ReadableStreamDefaultReader getReader() {
        if (reader == null) {
            reader = stream.getReader();
        }
        return reader;
    }

    private ReadableStreamReadResult fetch() {
        return readStream(getReader());
    }

    @Async
    private static native ReadableStreamReadResult readStream(ReadableStreamDefaultReader reader);

    private static void readStream(ReadableStreamDefaultReader reader, AsyncCallback<ReadableStreamReadResult> callback) {
        TeaVMBinds.readStreamAsync(reader, callback::complete, error -> callback.error(new IOException(error.stringValue())));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        done = true;
        buffer = null;
        bufferPos = 0;
        bufferLength = 0;
        if (reader == null) return;
        try {
            reader.cancel(JSString.valueOf("Closed"));
            reader.releaseLock();
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public int read() throws IOException {
        if (!ensureBuffer()) return -1;
        int v = buffer.get(bufferPos) & 0xFF;
        bufferPos++;
        return v;
    }

    @Override
    public int read(byte[] output, int offset, int length) throws IOException {
        if (output == null) throw new NullPointerException("output");
        if (offset < 0 || length < 0 || offset > output.length - length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length + ", output.length=" + output.length);
        }
        if (length == 0) return 0;
        if (!ensureBuffer()) return -1;

        int count = Math.min(length, bufferLength - bufferPos);
        int end = bufferPos + count;
        int outputPos = offset;
        while (bufferPos < end) {
            output[outputPos++] = buffer.get(bufferPos++);
        }
        return count;
    }

    @Override
    public int available() {
        return buffer == null ? 0 : Math.max(0, bufferLength - bufferPos);
    }

    private boolean ensureBuffer() throws IOException {
        if (closed) throw new IOException("Stream is closed");
        if (done) return false;
        try {
            while (buffer == null || bufferPos >= bufferLength) {
                ReadableStreamReadResult r = fetch();
                if (r.isDone()) {
                    done = true;
                    buffer = null;
                    bufferPos = 0;
                    bufferLength = 0;
                    return false;
                }
                Int8Array val = r.getValue();
                if (val != null) {
                    bufferLength = val.getLength();
                    buffer = val;
                }

                bufferPos = 0;
            }
            return true;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
