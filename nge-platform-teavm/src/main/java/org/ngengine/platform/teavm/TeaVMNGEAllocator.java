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

import java.nio.ByteBuffer;
import org.ngengine.platform.NGEAllocator;

public final class TeaVMNGEAllocator implements NGEAllocator {

    @Override
    public ByteBuffer malloc(int size) {
        checkSize(size);
        return ByteBuffer.allocate(size);
    }

    @Override
    public ByteBuffer calloc(int count, int size) {
        checkSize(count);
        checkSize(size);
        return ByteBuffer.allocate(Math.multiplyExact(count, size));
    }

    @Override
    public ByteBuffer realloc(ByteBuffer buffer, int newSize) {
        checkSize(newSize);
        if (buffer == null) {
            return malloc(newSize);
        }

        ByteBuffer resized = ByteBuffer.allocate(newSize);

        int copy = Math.min(buffer.capacity(), newSize);
        ByteBuffer src = buffer.duplicate();
        src.clear();
        src.limit(copy);
        resized.put(src);
        resized.clear();

        return resized;
    }

    @Override
    public ByteBuffer mallocAligned(int size, int alignment) {
        checkSize(size);
        if (alignment <= 0) {
            throw new IllegalArgumentException("alignment must be > 0");
        }
        // Heap ByteBuffer has no real alignment guarantee here.
        return ByteBuffer.allocate(size);
    }

    @Override
    public long address(ByteBuffer buffer) {
        if (buffer == null) {
            return 0L;
        }
        int hash = System.identityHashCode(buffer);
        return hash; // HACK, should probably use an identity map
    }

    @Override
    public void free(ByteBuffer buffer) {
        // no-op
    }

    private static void checkSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0: " + size);
        }
    }
}
