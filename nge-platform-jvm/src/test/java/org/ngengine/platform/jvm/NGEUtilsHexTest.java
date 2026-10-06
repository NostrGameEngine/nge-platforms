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

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.util.Locale;
import org.junit.Test;
import org.ngengine.platform.NGEUtils;

public class NGEUtilsHexTest {

    @Test
    public void roundTripsEveryByteAndBothLetterCases() {
        byte[] expected = new byte[256];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        String encoded = NGEUtils.bytesToHex(expected);
        assertArrayEquals(expected, NGEUtils.hexToByteArray(encoded));
        assertArrayEquals(expected, NGEUtils.hexToByteArray(encoded.toUpperCase(Locale.ROOT)));
        assertArrayEquals(new byte[0], NGEUtils.hexToByteArray(""));
    }

    @Test
    public void buffersStartAtZeroAndHaveIndependentStorage() {
        ByteBuffer first = NGEUtils.hexToBytes("00abff");
        ByteBuffer second = NGEUtils.hexToBytes("00abff");
        assertEquals(0, first.position());
        assertEquals(3, first.limit());
        assertEquals(3, first.capacity());
        assertEquals("00abff", NGEUtils.bytesToHex(first));
        first.put(0, (byte) 42);
        assertEquals(0, second.get(0));
        assertEquals(0, NGEUtils.hexToBytes("").remaining());
    }

    @Test
    public void rejectsMalformedInputInsteadOfAliasingValidBytes() {
        for (String malformed : new String[] { "0", "abc", "GF", "FG", "0g", "-1", " ff", "ff ", "ＦＦ", "١٢" }) {
            assertThrows(IllegalArgumentException.class, () -> NGEUtils.hexToByteArray(malformed));
            assertThrows(IllegalArgumentException.class, () -> NGEUtils.hexToBytes(malformed));
        }
        assertThrows(NullPointerException.class, () -> NGEUtils.hexToByteArray(null));
        assertThrows(NullPointerException.class, () -> NGEUtils.hexToBytes(null));
    }
}
