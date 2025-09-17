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
import java.util.List;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore.VStoreBackend;

public class IndexedDbVStore implements VStoreBackend {

    private final String name;

    public IndexedDbVStore(String name) {
        this.name = name;
    }

    @Override
    public AsyncTask<InputStream> read(String path) {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    TeaVMBinds.vfileReadAsync(
                        name,
                        path,
                        data -> {
                            ByteArrayInputStream bais = new ByteArrayInputStream(data);
                            res.accept(bais);
                        },
                        error -> {
                            rej.accept(new IOException("Error reading file: " + error));
                        }
                    );
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    OutputStream os = new OutputStream() {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();

                        @Override
                        public void write(int b) {
                            baos.write(b);
                        }

                        @Override
                        public void flush() throws IOException {
                            TeaVMBindsAsync.vfileWrite(path, path, baos.toByteArray());
                        }

                        @Override
                        public void close() {
                            try {
                                flush();
                            } catch (IOException e) {}
                            baos = null;
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
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    TeaVMBinds.vfileExistsAsync(
                        name,
                        path,
                        exists -> {
                            res.accept(exists);
                        },
                        error -> {
                            rej.accept(new IOException("Error checking file existence: " + error));
                        }
                    );
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<Void> delete(String path) {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    TeaVMBinds.vfileDeleteAsync(
                        name,
                        path,
                        r -> {
                            res.accept(null);
                        },
                        error -> {
                            rej.accept(new IOException("Error deleting file: " + error));
                        }
                    );
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public AsyncTask<List<String>> listAll() {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                try {
                    TeaVMBinds.vfileListAllAsync(
                        name,
                        files -> {
                            res.accept(files != null ? List.of(files) : List.of());
                        },
                        error -> {
                            rej.accept(new IOException("Error listing files: " + error));
                        }
                    );
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }
}
