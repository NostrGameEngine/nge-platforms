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

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

/**
 * Tracks live TeaVM allocations without relying on System.gc(), which is a no-op
 * in browsers. Set {@code nge.allocator.maxBytes} for an optional hard limit.
 */
public final class TeaVMNGEAllocatorGuard {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final long INITIAL_SOFT_BUDGET = 64L * 1024L * 1024L;

    private final long hardBudget = readHardBudget();
    private long allocatedBytes;
    private long softBudget = INITIAL_SOFT_BUDGET;
    private long highWaterBytes;

    public synchronized void beforeAlloc(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0: " + size);
        }
        if (size > Long.MAX_VALUE - allocatedBytes) {
            throw new OutOfMemoryError("TeaVM allocation accounting overflow");
        }
        long projected = allocatedBytes + size;
        if (projected > hardBudget) {
            throw new OutOfMemoryError("TeaVM allocator budget exceeded: " + projected + " > " + hardBudget);
        }
        if (projected > softBudget) {
            // The TeaVM heap grows on demand; a soft budget is observational, not a GC request.
            softBudget = Math.min(hardBudget, Math.max(projected, softBudget + (softBudget >>> 2)));
        }
    }

    public void trackManaged(ByteBuffer buffer, long size) {
        track(size);
        CLEANER.register(buffer, () -> release(size));
    }

    public void trackRaw(long size) {
        track(size);
    }

    public void releaseRaw(long size) {
        release(size);
    }

    public synchronized void notifyGC() {
        // TeaVM finalization callbacks update allocatedBytes; there is no synchronous browser GC.
        if (allocatedBytes < (softBudget >>> 2) && softBudget > INITIAL_SOFT_BUDGET) {
            softBudget = Math.max(INITIAL_SOFT_BUDGET, softBudget >>> 1);
        }
    }

    public synchronized long getAllocatedBytes() {
        return allocatedBytes;
    }

    public synchronized long getHighWaterBytes() {
        return highWaterBytes;
    }

    private synchronized void track(long size) {
        allocatedBytes += size;
        highWaterBytes = Math.max(highWaterBytes, allocatedBytes);
    }

    private synchronized void release(long size) {
        allocatedBytes -= size;
    }

    private static long readHardBudget() {
        String value = System.getProperty("nge.allocator.maxBytes");
        if (value == null || value.isEmpty()) {
            return Long.MAX_VALUE;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : Long.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
