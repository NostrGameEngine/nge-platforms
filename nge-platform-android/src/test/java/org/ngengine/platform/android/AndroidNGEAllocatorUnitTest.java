package org.ngengine.platform.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.ngengine.platform.NGEAllocator;

public class AndroidNGEAllocatorUnitTest {

    @Test
    public void desktopNativeResourcesAreAvailableToLocalUnitTests() {
        String[] resources = {
            "/natives/linux/x86_64/libsaferalloc.so",
            "/natives/linux/aarch64/libsaferalloc.so",
            "/natives/windows/x86_64/saferalloc.dll",
            "/natives/macos/x86_64/libsaferalloc.dylib",
            "/natives/macos/aarch64/libsaferalloc.dylib",
        };
        for (String resource : resources) {
            assertNotNull("Missing host test dependency: " + resource, getClass().getResource(resource));
        }
    }

    @Test
    public void allocatorLoadsHostNativeLibraryAndAllocatesMemory() {
        NGEAllocator allocator = new AndroidNGEAllocator();
        ByteBuffer buffer = allocator.calloc(1, 64);
        try {
            assertNotNull(buffer);
            assertTrue(buffer.isDirect());
            assertEquals(64, buffer.capacity());
            assertTrue(allocator.rawAddressesAreNative());
            assertNotEquals(0L, allocator.address(buffer));
            for (int i = 0; i < buffer.capacity(); i++) {
                assertEquals("calloc must zero initialize byte " + i, 0, buffer.get(i));
            }
            buffer.putInt(0, 0x12345678);
            assertEquals(0x12345678, buffer.getInt(0));
        } finally {
            allocator.free(buffer);
        }
    }
}
