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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.ngengine.platform.NGEPlatform;

public class JsonSerializationTest {

    @Test
    public void serializationUsesJavaScriptSemantics() {
        NGEPlatform platform = NGEPlatform.get();
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("null", null);
        object.put("html", "<script>&</script>");
        object.put("unicode", "🦊 café 漢字" + (char) 0x2028 + (char) 0x2029);
        StringBuilder controls = new StringBuilder();
        for (int i = 0; i < 32; i++) controls.append((char) i);
        object.put("controls", controls.toString());
        object.put("numbers", List.of(Long.MIN_VALUE, Long.MAX_VALUE, 1.25, -0.0));
        object.put("large", "abcdef".repeat(20000));
        object.put("nested", Arrays.asList(Arrays.asList(null, true, false), Map.of("key", "value")));
        String json = platform.toJSON(object);
        assertTrue(json.contains("\"numbers\":[-9223372036854776000,9223372036854776000,1.25,0]"));
        assertTrue(json.contains("\"unicode\":\"🦊 café 漢字" + (char) 0x2028 + (char) 0x2029 + "\""));
        assertEquals("[" + json + ",null]", platform.toJSON(Arrays.asList(object, null)));
        assertEquals("null", platform.toJSON((Map) null));
        assertEquals("null", platform.toJSON((List) null));
        assertEquals(
            "[null,null,null,0]",
            platform.toJSON(List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0))
        );
        assertEquals("[\"\\ud800\",\"\\udfff\",\"🦊\"]", platform.toJSON(List.of("\ud800", "\udfff", "🦊")));
    }

    @Test
    public void nestedMapsRetainInsertionOrderAndJavaScriptArrayIndexOrdering() {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        String input =
            "{\"z\":\"z\",\"10\":\"ten\",\"a\":{\"last\":null,\"first\":true},\"2\":\"two\",\"01\":\"one\",\"4294967295\":\"not-index\",\"__proto__\":{\"ok\":true}}";
        String expected =
            "{\"2\":\"two\",\"10\":\"ten\",\"z\":\"z\",\"a\":{\"last\":null,\"first\":true},\"01\":\"one\",\"4294967295\":\"not-index\",\"__proto__\":{\"ok\":true}}";
        Map<?, ?> parsed = platform.fromJSON(input, Map.class);
        assertEquals(LinkedHashMap.class, parsed.getClass());
        assertEquals(LinkedHashMap.class, parsed.get("a").getClass());
        assertEquals(List.of("2", "10", "z", "a", "01", "4294967295", "__proto__"), new ArrayList<>(parsed.keySet()));
        assertEquals(expected, platform.toJSON(parsed));
        assertEquals(expected, platform.toJSON((Map) platform.fromJSON(input, Object.class)));
        assertEquals("[" + expected + "]", platform.toJSON(platform.fromJSON("[" + input + "]", List.class)));
    }

    @Test
    public void repeatedReferencesAreAllowedButCyclesAreRejected() {
        NGEPlatform platform = new JVMAsyncPlatform();
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("value", 1);
        assertEquals("[{\"value\":1},{\"value\":1}]", platform.toJSON(List.of(child, child)));
        child.put("self", child);
        assertThrows(IllegalArgumentException.class, () -> platform.toJSON(child));
    }

    @Test
    public void nativeJavaScriptOracleMatchesRandomBinary64Numbers() throws Exception {
        // Node is also the reference engine used by the browser dependency build.
        // Keep known vectors above runnable on Java-only hosts as well.
        Process version;
        try {
            version = new ProcessBuilder("node", "--version").start();
        } catch (java.io.IOException unavailable) {
            org.junit.Assume.assumeNoException("Node is required for the native JSON.stringify oracle", unavailable);
            return;
        }
        assertTrue(version.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, version.exitValue());
        Random random = new Random(0x4e47454a534f4eL);
        ArrayList<Double> values = new ArrayList<>(
            List.of(
                0.0,
                -0.0,
                1e-7,
                1e-6,
                1e20,
                1e21,
                1e23,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                333333333.33333329,
                1000000000000000128d,
                Double.NaN,
                Double.POSITIVE_INFINITY
            )
        );
        for (int exponent = -324; exponent <= 308; exponent++) {
            double power = Double.parseDouble("1e" + exponent);
            double below = power;
            double above = power;
            for (int neighbor = 0; neighbor < 4; neighbor++) {
                values.add(below);
                values.add(-below);
                values.add(above);
                values.add(-above);
                below = Math.nextDown(below);
                above = Math.nextUp(above);
            }
        }
        for (int exponent = -1074; exponent <= 1023; exponent++) {
            double power = Math.scalb(1.0, exponent);
            values.add(power);
            values.add(Math.nextDown(power));
            values.add(Math.nextUp(power));
        }
        for (int i = 0; i < 100_000; i++) values.add(Double.longBitsToDouble(random.nextLong()));
        StringBuilder bits = new StringBuilder();
        for (double value : values) bits.append(Long.toHexString(Double.doubleToRawLongBits(value))).append('\n');
        Path input = Files.createTempFile("nge-json-numbers-", ".txt");
        Process oracle = null;
        try {
            Files.writeString(input, bits);
            oracle =
                new ProcessBuilder(
                    "node",
                    "-e",
                    "const fs=require('node:fs');const values=fs.readFileSync(process.argv[1],'utf8').trim().split('\\n').map(hex=>{const b=Buffer.alloc(8);b.writeBigUInt64BE(BigInt('0x'+hex));return b.readDoubleBE();});process.stdout.write(JSON.stringify(values));",
                    input.toString()
                )
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String expected = new String(oracle.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(oracle.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, oracle.exitValue());
            assertEquals(expected, new JVMAsyncPlatform().toJSON(values));

            ArrayList<String> strings = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                StringBuilder text = new StringBuilder();
                for (int j = 0; j < 30; j++) text.append((char) random.nextInt(65536));
                strings.add(text.toString());
            }
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("z", strings);
            tree.put("10", new char[] { 'a', '\ud800', '\n' });
            tree.put("2", values);
            tree.put("__proto__", Map.of("safe", true));
            String actual = new JVMAsyncPlatform().toJSON(tree);
            Files.writeString(input, actual);
            oracle =
                new ProcessBuilder(
                    "node",
                    "-e",
                    "process.stdout.write(JSON.stringify(JSON.parse(require('node:fs').readFileSync(process.argv[1],'utf8'))));",
                    input.toString()
                )
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            expected = new String(oracle.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(oracle.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, oracle.exitValue());
            assertEquals(expected, actual);
        } finally {
            if (oracle != null && oracle.isAlive()) oracle.destroyForcibly();
            Files.deleteIfExists(input);
        }
    }
}
