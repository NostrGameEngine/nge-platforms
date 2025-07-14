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
import org.ngengine.platform.VStore.VStoreBackend;

public class IndexedDbVStore implements VStoreBackend {

    private final String name;

    public IndexedDbVStore(String name) {
        this.name = name;
    }

    @Override
    public InputStream read(String path) {
        byte data[] = TeaVMBinds.vfileRead(name, path);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        return bais;
    }

    @Override
    public OutputStream write(String path) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream os = new OutputStream() {
            @Override
            public void write(int b) {
                baos.write(b);
            }

            @Override
            public void flush() throws IOException {
                TeaVMBinds.vfileWrite(name, path, baos.toByteArray());
            }

            @Override
            public void close() {
                TeaVMBinds.vfileWrite(name, path, baos.toByteArray());
            }
        };
        return os;
    }

    @Override
    public boolean exists(String path) {
        boolean exists = TeaVMBinds.vfileExists(name, path);
        return exists;
    }

    @Override
    public void delete(String path) {
        TeaVMBinds.vfileDelete(name, path);
    }

    @Override
    public List<String> listAll() {
        String[] files = TeaVMBinds.vfileListAll(name);
        return List.of(files);
    }
}
