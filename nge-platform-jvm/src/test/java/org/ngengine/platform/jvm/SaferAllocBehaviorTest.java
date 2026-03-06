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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.ngengine.platform.NGEAllocator;
import org.ngengine.saferalloc.SaferAlloc;

public class SaferAllocBehaviorTest {

    @Test
    public void callocReturnsZeroInitializedDirectBufferWithExpectedCapacity() {
        ByteBuffer buffer = SaferAlloc.calloc(1, 1024);
        assertNotNull(buffer);
        assertTrue(buffer.isDirect());
        assertEquals(1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(1024, buffer.limit());

        for (int i = 0; i < buffer.capacity(); i++) {
            assertEquals("calloc must zero initialize memory at index " + i, 0, buffer.get(i));
        }

        SaferAlloc.free(buffer);
    }

    @Test
    public void liveAllocationsDoNotAliasAddressesOrContents() {
        List<ByteBuffer> buffers = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                ByteBuffer b = SaferAlloc.calloc(1, 256);
                assertNotNull(b);
                long addr = SaferAlloc.address(b);
                assertNotEquals("address must be non-zero", 0L, addr);
                buffers.add(b);
            }

            for (int i = 0; i < buffers.size(); i++) {
                ByteBuffer b = buffers.get(i);
                byte pattern = (byte) (i + 1);
                for (int j = 0; j < b.capacity(); j++) {
                    b.put(j, pattern);
                }
            }

            for (int i = 0; i < buffers.size(); i++) {
                ByteBuffer b = buffers.get(i);
                byte expected = (byte) (i + 1);
                for (int j = 0; j < b.capacity(); j++) {
                    assertEquals("buffer contents changed unexpectedly (possible overlap/alias)", expected, b.get(j));
                }
            }
        } finally {
            for (ByteBuffer b : buffers) {
                SaferAlloc.free(b);
            }
        }
    }

    @Test
    public void copiedPayloadSurvivesAllocatorChurnWhenReferenceIsRetained() {
        byte[] payload = new byte[8 * 1024];
        ThreadLocalRandom.current().nextBytes(payload);

        ByteBuffer source = ByteBuffer.allocateDirect(payload.length);
        source.put(payload);
        source.flip();

        ByteBuffer copy = SaferAlloc.calloc(1, payload.length);
        assertNotNull(copy);
        copy.put(source.duplicate());
        copy.flip();

        // Churn temporary allocations and trigger GC to stress lifecycle bookkeeping.
        for (int round = 0; round < 200; round++) {
            ByteBuffer temp = SaferAlloc.calloc(1, 512);
            if (temp != null) {
                temp.put(0, (byte) round);
                SaferAlloc.free(temp);
            }
            if ((round % 25) == 0) {
                System.gc();
            }
        }

        byte[] observed = new byte[copy.remaining()];
        copy.duplicate().get(observed);
        assertArrayEquals("retained buffer content must remain stable", payload, observed);

        SaferAlloc.free(copy);
    }

    @Test
    public void jvmNgeAllocatorRetainedCopySurvivesChurn() {
        NGEAllocator allocator = new JVMNGEAllocator();
        byte[] payload = new byte[4096];
        ThreadLocalRandom.current().nextBytes(payload);

        ByteBuffer source = ByteBuffer.allocateDirect(payload.length);
        source.put(payload);
        source.flip();

        ByteBuffer copy = allocator.calloc(1, payload.length);
        assertNotNull(copy);
        copy.put(source.duplicate());
        copy.flip();

        for (int i = 0; i < 200; i++) {
            ByteBuffer tmp = allocator.calloc(1, 256);
            if (tmp != null) {
                tmp.put(0, (byte) i);
            }
            if ((i % 25) == 0) {
                System.gc();
            }
        }

        byte[] observed = new byte[copy.remaining()];
        copy.duplicate().get(observed);
        assertArrayEquals(payload, observed);
    }
}
