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
