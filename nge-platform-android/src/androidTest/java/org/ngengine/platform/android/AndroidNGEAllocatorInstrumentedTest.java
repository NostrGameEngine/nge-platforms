package org.ngengine.platform.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.NGEAllocator;

@RunWith(AndroidJUnit4.class)
public class AndroidNGEAllocatorInstrumentedTest {

    @Test
    public void callocReturnsZeroInitializedDirectBufferWithExpectedCapacity() {
        NGEAllocator allocator = new AndroidNGEAllocator();
        ByteBuffer buffer = allocator.calloc(1, 1024);

        assertNotNull(buffer);
        assertTrue(buffer.isDirect());
        assertEquals(1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(1024, buffer.limit());

        for (int i = 0; i < buffer.capacity(); i++) {
            assertEquals("calloc must zero initialize memory at index " + i, 0, buffer.get(i));
        }
    }

    @Test
    public void liveAllocationsDoNotAliasAddressesOrContents() {
        NGEAllocator allocator = new AndroidNGEAllocator();
        List<ByteBuffer> buffers = new ArrayList<>();
        List<Long> addresses = new ArrayList<>();

        for (int i = 0; i < 64; i++) {
            ByteBuffer b = allocator.calloc(1, 256);
            assertNotNull(b);
            long addr = allocator.address(b);
            assertNotEquals("address must be non-zero", 0L, addr);
            for (long existing : addresses) {
                assertNotEquals("two live allocations must not share the same address", existing, addr);
            }
            addresses.add(addr);
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
                assertEquals(
                    "buffer contents changed unexpectedly (possible overlap/alias)",
                    expected,
                    b.get(j)
                );
            }
        }
    }

    @Test
    public void retainedCopySurvivesAllocatorChurn() {
        NGEAllocator allocator = new AndroidNGEAllocator();
        byte[] payload = new byte[8 * 1024];
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
