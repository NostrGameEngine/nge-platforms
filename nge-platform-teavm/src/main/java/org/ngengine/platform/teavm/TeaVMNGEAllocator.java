package org.ngengine.platform.teavm;

import org.ngengine.platform.NGEAllocator;

import java.nio.ByteBuffer;

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