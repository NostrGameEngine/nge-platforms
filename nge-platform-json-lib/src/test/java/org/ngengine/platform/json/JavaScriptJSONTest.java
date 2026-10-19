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
package org.ngengine.platform.json;

import static org.junit.Assert.*;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JavaScriptJSONTest {

    @Test
    public void numberFormattingCoversRoundingAndNotationBoundaries() {
        double[] values = {
            -0.0,
            Double.MIN_VALUE,
            -Double.MIN_VALUE,
            Double.MAX_VALUE,
            1e-7,
            1e-6,
            1e20,
            1e21,
            1e23,
            9007199254740991d,
            9007199254740992d,
            1000000000000000128d,
            333333333.33333329,
            Double.longBitsToDouble(0x44b52d02c7e14af5L),
            Double.longBitsToDouble(0x44b52d02c7e14af7L),
        };
        String[] expected = {
            "0",
            "5e-324",
            "-5e-324",
            "1.7976931348623157e+308",
            "1e-7",
            "0.000001",
            "100000000000000000000",
            "1e+21",
            "1e+23",
            "9007199254740991",
            "9007199254740992",
            "1000000000000000100",
            "333333333.3333333",
            "9.999999999999997e+22",
            "1.0000000000000001e+23",
        };
        for (int i = 0; i < values.length; i++) {
            assertEquals(
                Long.toHexString(Double.doubleToRawLongBits(values[i])),
                expected[i],
                JavaScriptNumbers.format(values[i])
            );
        }
        assertThrows(IllegalArgumentException.class, () -> JavaScriptNumbers.format(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JavaScriptNumbers.format(Double.POSITIVE_INFINITY));
    }

    @Test
    public void unicodeAdaptationSurvivesEveryWriteBoundary() throws Exception {
        String original =
            "\"\\u2028\\u2029 \\\\u2028 \\\\\\u2029 \\\"\\n " +
            '\ud800' +
            "x" +
            '\udfff' +
            " " +
            '\ud800' +
            '\ud800' +
            '\udfff' +
            "\"";
        String expected =
            "\"" +
            (char) 0x2028 +
            (char) 0x2029 +
            " \\\\u2028 \\\\" +
            (char) 0x2029 +
            " \\\"\\n \\ud800x\\udfff \\ud800" +
            '\ud800' +
            '\udfff' +
            "\"";
        for (int boundary = 0; boundary <= original.length(); boundary++) {
            JavaScriptJSON.JavaScriptEscapes output = new JavaScriptJSON.JavaScriptEscapes();
            output.write(original, 0, boundary);
            output.write(original.toCharArray(), boundary, original.length() - boundary);
            output.close();
            assertEquals("Boundary " + boundary, expected, output.toString());
        }
        JavaScriptJSON.JavaScriptEscapes singles = new JavaScriptJSON.JavaScriptEscapes();
        for (int i = 0; i < original.length(); i++) singles.write(original.charAt(i));
        singles.close();
        assertEquals(expected, singles.toString());
        for (String incomplete : List.of("\\", "\\u20", "\ud800")) {
            JavaScriptJSON.JavaScriptEscapes output = new JavaScriptJSON.JavaScriptEscapes();
            output.write(incomplete);
            assertThrows(IOException.class, output::close);
        }
    }

    @Test
    public void gsonCompatibilityIsScopedToPlatformSerialization() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("line" + (char) 0x2028, "\\u2028" + (char) 0x2029 + '\ud800');
        tree.put("number", 1.0);
        String expected = "{\"line" + (char) 0x2028 + "\":\"\\\\u2028" + (char) 0x2029 + "\\ud800\",\"number\":1}";
        assertEquals(expected, JavaScriptJSON.stringify(tree));
        String gson = new GsonBuilder().disableHtmlEscaping().create().toJson(List.of(1.0, "" + (char) 0x2028));
        assertEquals("[1.0,\"\\u2028\"]", gson);
    }
}
