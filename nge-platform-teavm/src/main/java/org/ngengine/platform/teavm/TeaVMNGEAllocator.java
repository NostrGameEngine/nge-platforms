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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.ngengine.platform.NGEAllocator;
import org.teavm.classlib.PlatformDetector;
import org.teavm.interop.Address;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSBuffer;
import org.teavm.jso.JSBufferType;
import org.teavm.runtime.heap.Heap;

/**
 * TeaVM JS uses opaque handles; Wasm GC uses the heap behind direct buffers.
 * A JS handle obtained with {@link #address(ByteBuffer)} pins that buffer until
 * {@link #freeRaw(long)} or {@link #free(ByteBuffer)} releases the handle.
 */
public final class TeaVMNGEAllocator implements NGEAllocator {
    private final TeaVMNGEAllocatorGuard guard = new TeaVMNGEAllocatorGuard();
    private final Map<Long, Allocation> raw = new HashMap<>();
    private final IdentityHashMap<ByteBuffer, Long> jsBufferHandles = new IdentityHashMap<>();
    private long nextHandle = 1L;

    @Override
    public boolean rawAddressesAreNative() { return PlatformDetector.isWebAssemblyGC(); }

    @Override
    public ByteBuffer malloc(int size) {
        checkSize(size);
        guard.beforeAlloc(size);
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        guard.trackManaged(buffer, size);
        return buffer;
    }

    @Override
    public ByteBuffer calloc(int count, int size) {
        checkSize(count);
        checkSize(size);
        int bytes = Math.multiplyExact(count, size);
        ByteBuffer buffer = malloc(bytes);
        if (PlatformDetector.isWebAssemblyGC()) {
            Address.fillZero(Address.fromLong(wasmAddress(buffer)), bytes);
        }
        return buffer;
    }

    @Override
    public ByteBuffer realloc(ByteBuffer buffer, int newSize) {
        checkSize(newSize);
        if (buffer == null) {
            return malloc(newSize);
        }
        ByteBuffer resized = malloc(newSize);
        int copy = Math.min(buffer.capacity(), newSize);
        ByteBuffer src = buffer.duplicate();
        src.clear();
        src.limit(copy);
        resized.put(src);
        resized.clear();
        free(buffer);
        return resized;
    }

    @Override
    public ByteBuffer mallocAligned(int size, int alignment) {
        checkSize(size);
        checkAlignment(alignment);
        if (!PlatformDetector.isWebAssemblyGC()) {
            return malloc(size);
        }
        int allocationSize = Math.toIntExact(Math.addExact((long) size, alignment - 1L));
        guard.beforeAlloc(allocationSize);
        ByteBuffer owner = ByteBuffer.allocateDirect(allocationSize);
        long base = wasmAddress(owner);
        int offset = (int) ((alignment - (base & (alignment - 1L))) & (alignment - 1L));
        owner.position(offset);
        owner.limit(offset + size);
        ByteBuffer aligned = owner.slice();
        guard.trackManaged(aligned, allocationSize);
        return aligned;
    }

    @Override
    public long address(ByteBuffer buffer) {
        if (buffer == null) {
            return 0L;
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("A direct buffer is required");
        }
        if (PlatformDetector.isWebAssemblyGC()) {
            return wasmAddress(buffer);
        }
        Long existing = jsBufferHandles.get(buffer);
        if (existing != null) {
            return existing;
        }
        long handle = newHandle(1);
        jsBufferHandles.put(buffer, handle);
        raw.put(handle, new Allocation(buffer, 0, 0, 0, 0, true));
        return handle;
    }

    @Override
    public void free(ByteBuffer buffer) {
        if (buffer != null && !PlatformDetector.isWebAssemblyGC()) {
            Long handle = jsBufferHandles.remove(buffer);
            if (handle != null) {
                raw.remove(handle);
            }
        }
        // TeaVM owns direct buffers and releases their memory when collected.
    }

    @Override
    public void freeBuffer(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            free((ByteBuffer) buffer);
        }
    }

    @Override
    public long mallocRaw(long size) {
        return allocateRaw(size, 1);
    }

    @Override
    public long callocRaw(long count, long size) {
        checkRawSize(count);
        checkRawSize(size);
        long bytes = Math.multiplyExact(count, size);
        long address = allocateRaw(bytes, 1);
        if (PlatformDetector.isWebAssemblyGC()) {
            Address.fillZero(Address.fromLong(address), Math.toIntExact(bytes));
        }
        return address;
    }

    @Override
    public long reallocRaw(long address, long size) {
        checkRawSize(size);
        if (address == 0L) {
            return mallocRaw(size);
        }
        Allocation previous = raw.get(address);
        if (previous == null || previous.managedAddress) {
            throw new IllegalArgumentException("Unknown raw allocation: " + address);
        }
        long replacement = allocateRaw(size, previous.alignment);
        if (PlatformDetector.isWebAssemblyGC()) {
            Address.moveMemoryBlock(Address.fromLong(address), Address.fromLong(replacement),
                (int) Math.min(previous.size, size));
        } else {
            ByteBuffer source = previous.buffer.duplicate();
            source.clear();
            source.limit((int) Math.min(previous.size, size));
            raw.get(replacement).buffer.put(source);
        }
        freeRaw(address);
        return replacement;
    }

    @Override
    public void freeRaw(long address) {
        if (address == 0L) {
            return;
        }
        Allocation allocation = raw.remove(address);
        if (allocation == null) {
            throw new IllegalArgumentException("Unknown raw allocation: " + address);
        }
        if (allocation.managedAddress) {
            jsBufferHandles.remove(allocation.buffer);
            return;
        }
        if (PlatformDetector.isWebAssemblyGC()) {
            Heap.release(Address.fromLong(allocation.base));
        }
        guard.releaseRaw(allocation.charge);
    }

    @Override
    public long mallocAlignedRaw(long alignment, long size) {
        checkAlignment(alignment);
        return allocateRaw(size, (int) alignment);
    }

    @Override
    public void freeAlignedRaw(long address) {
        freeRaw(address);
    }

    @Override public long mallocFunctionPointer() { return 0L; }
    @Override public long callocFunctionPointer() { return 0L; }
    @Override public long reallocFunctionPointer() { return 0L; }
    @Override public long freeFunctionPointer() { return 0L; }
    @Override public long alignedAllocFunctionPointer() { return 0L; }
    @Override public long alignedFreeFunctionPointer() { return 0L; }

    @Override
    public void beforeAlloc(long size) {
        guard.beforeAlloc(size);
    }

    @Override
    public void notifyGC() {
        guard.notifyGC();
    }

    private long allocateRaw(long size, int alignment) {
        checkRawSize(size);
        long capacity = Math.max(1L, size);
        long charge = PlatformDetector.isWebAssemblyGC() ? Math.addExact(capacity, alignment - 1L) : capacity;
        if (charge > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("TeaVM allocation exceeds linear-memory region: " + charge);
        }
        guard.beforeAlloc(charge);
        if (PlatformDetector.isWebAssemblyGC()) {
            int allocatedSize = Math.toIntExact(charge);
            Address base = Heap.alloc(allocatedSize);
            if (base == null || base.toInt() == 0) {
                throw new OutOfMemoryError("TeaVM linear-memory allocation failed: " + allocatedSize);
            }
            long baseValue = Integer.toUnsignedLong(base.toInt());
            long aligned = (baseValue + alignment - 1L) & -((long) alignment);
            raw.put(aligned, new Allocation(null, size, charge, baseValue, alignment, false));
            guard.trackRaw(charge);
            return aligned;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) capacity);
        long handle = newHandle(alignment);
        raw.put(handle, new Allocation(buffer, size, capacity, 0, alignment, false));
        guard.trackRaw(capacity);
        return handle;
    }

    private long newHandle(int alignment) {
        long remainder = nextHandle % alignment;
        long handle = remainder == 0L ? nextHandle : nextHandle + alignment - remainder;
        if (handle <= 0 || handle == Long.MAX_VALUE) {
            throw new OutOfMemoryError("TeaVM allocator handle space exhausted");
        }
        nextHandle = handle + 1L;
        return handle;
    }

    private static long wasmAddress(ByteBuffer buffer) {
        ByteBuffer wholeBuffer = buffer.duplicate();
        wholeBuffer.clear();
        return Integer.toUnsignedLong(wasmBufferOffset(wholeBuffer));
    }

    @JSBody(params = "buffer", script = "return buffer.byteOffset;")
    private static native int wasmBufferOffset(@JSBuffer(JSBufferType.UINT8) ByteBuffer buffer);

    private static void checkSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0: " + size);
        }
    }

    private static void checkRawSize(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("TeaVM allocation exceeds a buffer-sized region: " + size);
        }
    }

    private static void checkAlignment(long alignment) {
        if (alignment <= 0 || alignment > (1L << 30) || (alignment & (alignment - 1L)) != 0) {
            throw new IllegalArgumentException("alignment must be a positive power of two <= 2^30");
        }
    }

    private static final class Allocation {
        final ByteBuffer buffer;
        final long size;
        final long charge;
        final long base;
        final int alignment;
        final boolean managedAddress;

        Allocation(ByteBuffer buffer, long size, long charge, long base, int alignment, boolean managedAddress) {
            this.buffer = buffer;
            this.size = size;
            this.charge = charge;
            this.base = base;
            this.alignment = alignment;
            this.managedAddress = managedAddress;
        }
    }
}
