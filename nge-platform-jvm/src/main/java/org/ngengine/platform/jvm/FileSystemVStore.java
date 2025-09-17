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
package org.ngengine.platform.jvm;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore.VStoreBackend;

public class FileSystemVStore implements VStoreBackend {

    private final AsyncExecutor executor;
    private final Path basePath;

    public FileSystemVStore(Path basePath) {
        this.basePath = basePath;
        NGEPlatform platform = NGEPlatform.get();
        this.executor = platform.newAsyncExecutor(VStoreBackend.class);
    }

    @Override
    public AsyncTask<InputStream> read(String path)  {
        return NGEPlatform.get().promisify((res,rej)->{
            try{
                Path fullPath = Util.safePath(basePath, path, false);
                FileInputStream is = new FileInputStream(fullPath.toFile());
                res.accept(is);
            } catch (IOException e) {
                rej.accept(e);
            }
        }, executor);
        
    }

    @Override
    public AsyncTask<OutputStream> write(String path) {
        return NGEPlatform.get().promisify((res,rej)->{
            try{
                Path fullPath = Util.safePath(basePath, path, true);
                SafeFileOutputStream os = new SafeFileOutputStream(fullPath);
                res.accept(os);
            } catch (IOException e) {
                rej.accept(e);
            }
        }, executor);
    }

    @Override
    public AsyncTask<Boolean> exists(String path) {
        return NGEPlatform.get().promisify((res,rej)->{
            try{
                Path fullPath = Util.safePath(basePath, path, false);
                res.accept(fullPath!=null && Files.exists(fullPath));
            } catch (IOException e) {
                res.accept(false);
            }
        }, executor);
    }

    @Override
    public AsyncTask<Void> delete(String path)  {
        return NGEPlatform.get().promisify((res,rej)->{
            try{
                Path fullPath = Util.safePath(basePath, path, false);
                if(fullPath!=null){
                    Files.deleteIfExists(fullPath);
                }
                res.accept(null);
            } catch (IOException e) {
                rej.accept(e);
            }
        }, executor);
    }

    @Override
    public AsyncTask<List<String>> listAll() {
        return NGEPlatform.get().promisify((res,rej)->{
            try{
                List<String> files = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .map(basePath::relativize)
                    .map(Path::toString)
                    .toList();
                res.accept(files);
            } catch (IOException e) {
                rej.accept(e);
            }
        }, executor);
    }

    /**
     * An OutputStream that writes to a temporary file and atomically moves it
     * to the target location on close, preventing corruption if the process is
     * interrupted or crashes during write.
     */
    private static class SafeFileOutputStream extends OutputStream {

        private final Path targetPath;
        private final Path tempPath;
        private final FileOutputStream tempStream;
        private boolean closed = false;

        public SafeFileOutputStream(Path targetPath) throws IOException {
            this.targetPath = targetPath;
            Files.createDirectories(targetPath.getParent());
            this.tempPath = Files.createTempFile(targetPath.getParent(), "vstore", ".tmp");
            this.tempStream = new FileOutputStream(tempPath.toFile());
        }

        @Override
        public void write(int b) throws IOException {
            tempStream.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            tempStream.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            tempStream.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            tempStream.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            // Ensure all data is written to disk
            tempStream.flush();
            tempStream.getFD().sync(); // Force sync to disk
            tempStream.close();

            // Atomic move
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            closed = true;
        }
    }
}
