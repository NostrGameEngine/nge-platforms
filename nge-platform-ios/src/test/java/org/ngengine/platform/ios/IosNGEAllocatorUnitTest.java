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
package org.ngengine.platform.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.ngengine.platform.NGEAllocator;

public class IosNGEAllocatorUnitTest {

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
        NGEAllocator allocator = new IosNGEAllocator();
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
