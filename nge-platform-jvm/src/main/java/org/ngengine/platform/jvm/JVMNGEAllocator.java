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

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ngengine.platform.NGEAllocator;
import org.ngengine.saferalloc.SaferAlloc;

public final class JVMNGEAllocator implements NGEAllocator {

    private static final ReferenceQueue<ByteBuffer> refQueue = new ReferenceQueue<>();
    private static final ConcurrentHashMap<Long, AllocationRef> allocations = new ConcurrentHashMap<>();

    private static final Thread reaperThread = new Thread(JVMNGEAllocator::reapLoop, "nge-allocator-reaper");

    static {
        reaperThread.setDaemon(true);
        reaperThread.start();
    }

    private static void reapLoop() {
        for (;;) {
            try {
                AllocationRef ref = (AllocationRef) refQueue.remove();
                JVMNGEAllocatorGuard.notifyGC();
                ref.freeFromQueue();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // Keep the reaper alive even if one cleanup fails.
                t.printStackTrace();
            }
        }
    }

    private static final class AllocationRef extends PhantomReference<ByteBuffer> {

        private final long address;
        private final AtomicBoolean retired = new AtomicBoolean(false);

        private AllocationRef(ByteBuffer referent, long address) {
            super(referent, refQueue);
            this.address = address;
        }

        /**
         * Used when native realloc has already taken care of the old allocation.
         * Removes tracking without freeing again.
         */
        private void retireWithoutFree() {
            if (!retired.compareAndSet(false, true)) {
                return;
            }
            allocations.remove(address, this);
            clear();
        }

        /**
         * Explicit free or queued phantom cleanup.
         */
        private void freeNow() {
            if (!retired.compareAndSet(false, true)) {
                return;
            }

            boolean removed = allocations.remove(address, this);
            clear();

            if (removed) {
                SaferAlloc.free(address);
            }
        }

        private void freeFromQueue() {
            freeNow();
        }
    }

    private static ByteBuffer register(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }

        long address = SaferAlloc.address(buffer);
        if (address == 0L) {
            throw new IllegalStateException("SaferAlloc returned null address for non-null buffer");
        }

        AllocationRef ref = new AllocationRef(buffer, address);
        AllocationRef previous = allocations.put(address, ref);

        if (previous != null) {
            // This should normally not happen unless the old allocation was already
            // logically retired (for example after realloc) or bookkeeping got out of sync.
            // Never free here: the address now belongs to the new allocation.
            previous.retireWithoutFree();
        }

        return buffer;
    }

    @Override
    public ByteBuffer malloc(int size) {
        JVMNGEAllocatorGuard.beforeAlloc(size);
        return register(SaferAlloc.malloc(size));
    }

    @Override
    public ByteBuffer calloc(int count, int size) {
        JVMNGEAllocatorGuard.beforeAlloc((long) count * size);
        return register(SaferAlloc.calloc(count, size));
    }

    @Override
    public ByteBuffer realloc(ByteBuffer buffer, int newSize) {
        if (buffer == null) {
            return malloc(newSize);
        }

        JVMNGEAllocatorGuard.beforeAlloc(newSize);

        long oldAddress = SaferAlloc.address(buffer);
        AllocationRef oldRef = allocations.get(oldAddress);

        ByteBuffer resized = SaferAlloc.realloc(buffer, newSize);
        if (resized == null) {
            // Assume native realloc failed and old allocation is still valid.
            return null;
        }

        // The native realloc call has already handled the old allocation semantics.
        // Do not allow the old phantom ref to free it later.
        if (oldRef != null) {
            oldRef.retireWithoutFree();
        }

        return register(resized);
    }

    @Override
    public ByteBuffer mallocAligned(int size, int alignment) {
        JVMNGEAllocatorGuard.beforeAlloc(size);
        return register(SaferAlloc.mallocAligned(size, alignment));
    }

    @Override
    public long address(ByteBuffer buffer) {
        return buffer == null ? 0L : SaferAlloc.address(buffer);
    }

    @Override
    public void free(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        long address = SaferAlloc.address(buffer);
        AllocationRef ref = allocations.get(address);

        if (ref != null) {
            ref.freeNow();
        }
    }
}
