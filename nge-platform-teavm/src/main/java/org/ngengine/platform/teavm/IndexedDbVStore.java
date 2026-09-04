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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore.VStoreBackend;

public class IndexedDbVStore implements VStoreBackend {

    private static final Logger logger = Logger.getLogger(IndexedDbVStore.class.getName());

    private final String name;

    public IndexedDbVStore(String name) {
        this.name = name;
    }

    @Override
    public AsyncTask<InputStream> read(String path) {
        return platform()
            .wrapPromise((resolve, reject) ->
                TeaVMBinds.vfileReadAsync(
                    name,
                    path,
                    result -> resolve.accept(new ByteArrayInputStream(result.getData())),
                    error -> reject.accept(new IOException(error.stringValue()))
                )
            );
    }

    @Override
    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    OutputStream os = new OutputStream() {
                        private ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        private boolean dirty = true;
                        private boolean closed;

                        @Override
                        public void write(int b) throws IOException {
                            ensureOpen();
                            baos.write(b);
                            dirty = true;
                        }

                        @Override
                        public void write(byte[] data, int offset, int length) throws IOException {
                            ensureOpen();
                            baos.write(data, offset, length);
                            dirty = true;
                        }

                        @Override
                        public void flush() throws IOException {
                            ensureOpen();
                        }

                        @Override
                        public void close() throws IOException {
                            if (closed) {
                                return;
                            }
                            if (dirty) {
                                // An open stream is only an in-memory buffer. Closing it starts the
                                // asynchronous IndexedDB commit; the JavaScript backend records that
                                // pending close so later accesses can wait for transaction completion.
                                TeaVMBinds.vfileWriteAsync(
                                    name,
                                    path,
                                    baos.toByteArray(),
                                    () -> {},
                                    error ->
                                        logger.log(Level.WARNING, "Error closing file " + path + ": " + error.stringValue())
                                );
                                dirty = false;
                            }
                            closed = true;
                            baos = null;
                        }

                        private void ensureOpen() throws IOException {
                            if (closed) {
                                throw new IOException("Output stream is closed: " + path);
                            }
                        }
                    };
                    res.accept(os);
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<Boolean> exists(String path) {
        return platform()
            .wrapPromise((resolve, reject) ->
                TeaVMBinds.vfileExistsAsync(
                    name,
                    path,
                    result -> resolve.accept(result.booleanValue()),
                    error -> reject.accept(new IOException(error.stringValue()))
                )
            );
    }

    @Override
    public AsyncTask<Void> delete(String path) {
        return platform()
            .wrapPromise((resolve, reject) ->
                TeaVMBinds.vfileDeleteAsync(
                    name,
                    path,
                    () -> resolve.accept(null),
                    error -> reject.accept(new IOException(error.stringValue()))
                )
            );
    }

    @Override
    public AsyncTask<List<String>> listAll() {
        return platform()
            .wrapPromise((resolve, reject) ->
                TeaVMBinds.vfileListAllAsync(
                    name,
                    files -> {
                        ArrayList<String> list = new ArrayList<>();
                        if (files != null) {
                            for (int i = 0; i < files.getLength(); i++) {
                                list.add(files.get(i).stringValue());
                            }
                        }
                        resolve.accept(list);
                    },
                    error -> reject.accept(new IOException(error.stringValue()))
                )
            );
    }

    private static TeaVMPlatform platform() {
        return (TeaVMPlatform) NGEPlatform.get();
    }
}
