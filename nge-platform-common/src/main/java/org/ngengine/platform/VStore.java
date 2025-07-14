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
package org.ngengine.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VStore {

    private static final Logger logger = Logger.getLogger(VStore.class.getName());

    public interface VStoreBackend {
        InputStream read(String path) throws IOException;

        OutputStream write(String path) throws IOException;

        boolean exists(String path) throws IOException;

        void delete(String path) throws IOException;
        List<String> listAll() throws IOException;
    }

    private final VStoreBackend backend;

    public VStore(VStoreBackend backend) {
        this.backend = backend;
    }

    public AsyncTask<InputStream> read(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                try {
                    res.accept(backend.read(path));
                } catch (IOException e) {
                    rej.accept(e);
                }
            });
    }

    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                try {
                    res.accept(backend.write(path));
                } catch (IOException e) {
                    rej.accept(e);
                }
            });
    }

    public AsyncTask<Boolean> exists(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                try {
                    boolean exists = backend.exists(path);
                    res.accept(exists);
                } catch (IOException e) {
                    rej.accept(e);
                }
            });
    }

    public AsyncTask<Void> delete(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                try {
                    backend.delete(path);
                    res.accept(null);
                } catch (IOException e) {
                    rej.accept(e);
                }
            });
    }

    public AsyncTask<List<String>> listAll() {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                try {
                    List<String> files = backend.listAll();
                    res.accept(files);
                } catch (IOException e) {
                    rej.accept(e);
                }
            });
    }

    public AsyncTask<Void> writeFully(String path, byte data[]) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                write(path)
                    .then(out -> {
                        try {
                            out.write(data);
                            res.accept(null);
                        } catch (Exception e) {
                            rej.accept(e);
                        } finally {
                            try {
                                out.close();
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Error closing output stream", e);
                            }
                        }
                        return null;
                    })
                    .catchException(rej);
            });
    }

    public AsyncTask<byte[]> readFully(String path) {
        return NGEPlatform
            .get()
            .getVStoreQueue()
            .enqueue((res, rej) -> {
                read(path)
                    .then(in -> {
                        try {
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            while ((bytesRead = in.read(buffer)) != -1) {
                                bos.write(buffer, 0, bytesRead);
                            }
                            res.accept(bos.toByteArray());
                        } catch (Exception e) {
                            rej.accept(e);
                        } finally {
                            try {
                                in.close();
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Error closing input stream", e);
                            }
                        }
                        return null;
                    })
                    .catchException(rej);
            });
    }
}
