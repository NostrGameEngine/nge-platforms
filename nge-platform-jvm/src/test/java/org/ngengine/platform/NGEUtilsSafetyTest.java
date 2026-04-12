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
package org.ngengine.platform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

public class NGEUtilsSafetyTest {

    @Test
    public void safeLongBigIntegerOutOfRangeThrows() {
        BigInteger tooLarge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        try {
            NGEUtils.safeLong(tooLarge);
            fail("Expected IllegalArgumentException for out-of-range BigInteger");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("out of range"));
        }
    }

    @Test
    public void safeFloatPreservesNaNAndInfinityMappings() {
        assertTrue(Float.isNaN(NGEUtils.safeFloat(Double.NaN)));
        assertEquals(Float.POSITIVE_INFINITY, NGEUtils.safeFloat(Double.POSITIVE_INFINITY), 0.0f);
        assertEquals(Float.NEGATIVE_INFINITY, NGEUtils.safeFloat(Double.NEGATIVE_INFINITY), 0.0f);
    }

    @Test
    public void safeDoublePreservesNaNAndInfinityMappings() {
        assertTrue(Double.isNaN(NGEUtils.safeDouble(Float.NaN)));
        assertEquals(Double.POSITIVE_INFINITY, NGEUtils.safeDouble(Float.POSITIVE_INFINITY), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, NGEUtils.safeDouble(Float.NEGATIVE_INFINITY), 0.0);
    }

    @Test
    public void safeFloatRejectsFiniteDoubleOverflowToInfinity() {
        try {
            NGEUtils.safeFloat(Double.MAX_VALUE);
            fail("Expected IllegalArgumentException for finite double overflowing float");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("out of range"));
        }
    }

    @Test
    public void safeByteArraySupportsDirectByteBufferWithoutArray() {
        ByteBuffer direct = ByteBuffer.allocateDirect(3);
        direct.put((byte) 1).put((byte) 2).put((byte) 3);
        direct.flip();

        byte[] out = NGEUtils.safeByteArray(direct);
        assertArrayEquals(new byte[] { 1, 2, 3 }, out);
    }

    @Test
    public void safeCollectionOfStringArraySanitizesMixedList() {
        List<Object> mixed = new ArrayList<>();
        mixed.add(new String[] { "a", null });
        mixed.add(List.of("b", 123));

        Collection<String[]> out = NGEUtils.safeCollectionOfStringArray(mixed);
        assertEquals(2, out.size());

        List<String[]> asList = new ArrayList<>(out);
        assertArrayEquals(new String[] { "a", "" }, asList.get(0));
        assertArrayEquals(new String[] { "b", "123" }, asList.get(1));
    }

    @Test
    public void safeStringArrayHandlesNullEntries() {
        String[] in = new String[] { "x", null, "y" };
        String[] out = NGEUtils.safeStringArray(in);
        assertArrayEquals(new String[] { "x", "", "y" }, out);
    }
}
