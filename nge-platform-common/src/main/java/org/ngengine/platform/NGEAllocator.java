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

import java.nio.ByteBuffer;

/**
 * A native memory allocator interface.
 * ByteBuffers returned by this allocator are managed and the underlying memory
 * will be reclaimed as soon as the ByteBuffer is garbage collected.
 * They are also hardened against common memory safety issues.
 */
public interface NGEAllocator {
    ByteBuffer malloc(int size);

    ByteBuffer calloc(int count, int size);

    ByteBuffer realloc(ByteBuffer buffer, int newSize);

    ByteBuffer mallocAligned(int size, int alignment);

    long address(ByteBuffer buffer);

    /**
     * Attempt an eager cleanup if supported by the underlying implementation.
     *
     * <p>This method is best-effort only. It may do nothing, depending on the
     * runtime and allocator implementation. In normal usage, calling this method
     * is usually unnecessary and not recommended, because the buffer's memory is
     * reclaimed automatically when the {@link ByteBuffer} becomes unreachable and
     * is garbage collected.</p>
     *
     * <p>Use this only in situations with significant memory pressure where an
     * implementation may be able to reduce retained memory sooner.</p>
     *
     * <p>After calling this method, the provided buffer should not be used anymore</p>
     *
     * @param buffer the buffer to release; implementations may ignore {@code null}
     */
    void free(ByteBuffer buffer);
}
