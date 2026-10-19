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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.NGEPlatform;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMJsConverterTest {

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void parsedObjectsRetainNativeJavaScriptPropertyOrderRecursively() {
        NGEPlatform platform = new TeaVMPlatform();
        String json =
            "{\"z\":\"last\",\"a\":\"first\",\"10\":true,\"2\":false,\"nested\":{\"last\":null,\"first\":true},\"__proto__\":{\"safe\":true}}";
        JSObject original = (JSObject) TeaVMBinds.fromJSON(json);
        Map<?, ?> parsed = platform.fromJSON(json, Map.class);
        assertEquals(LinkedHashMap.class, parsed.getClass());
        assertEquals(LinkedHashMap.class, parsed.get("nested").getClass());
        assertEquals(List.of("2", "10", "z", "a", "nested", "__proto__"), new ArrayList<>(parsed.keySet()));
        assertEquals(stringify(original), platform.toJSON(parsed));
        assertEquals(stringify(original), stringify(TeaVMJsConverter.toJSObject(TeaVMJsConverter.toJavaMap(original))));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void orderedJavaObjectsPreservePropertiesAndNestedPrimitiveArrays() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", "id");
        object.put("pubkey", "pubkey");
        object.put("__proto__", "owned");
        object.put("bytes", new byte[] { 1, 2 });
        object.put("characters", new char[] { 'a', 'b' });
        assertEquals(
            "{\"id\":\"id\",\"pubkey\":\"pubkey\",\"__proto__\":\"owned\",\"bytes\":[1,2],\"characters\":[\"a\",\"b\"]}",
            new TeaVMPlatform().toJSON(object)
        );
    }

    @JSBody(script = "return Array.from({length: 6000}, (_, i) => ['p', String(i), '', 'name']);")
    private static native JSObject largeOwnedContacts();

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void numericBoundariesMatchNativePlatformSerialization() {
        assertEquals(
            "[0,5e-324,1.7976931348623157e+308,1e-7,0.000001,100000000000000000000,1e+21,1e+23,1000000000000000100,9223372036854776000,null,null]",
            new TeaVMPlatform()
                .toJSON(
                    Arrays.asList(
                        -0.0,
                        Double.MIN_VALUE,
                        Double.MAX_VALUE,
                        1e-7,
                        1e-6,
                        1e20,
                        1e21,
                        1e23,
                        1000000000000000128d,
                        Long.MAX_VALUE,
                        Double.NaN,
                        Double.POSITIVE_INFINITY
                    )
                )
        );
    }

    @JSBody(params = "value", script = "return JSON.stringify(value);")
    private static native String stringify(JSObject value);

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void largeOwnedContactArraysRoundTripWithoutDroppingOrReorderingValues() {
        JSObject original = largeOwnedContacts();
        List<Object> converted = TeaVMJsConverter.toJavaObject(original, List.class, true);
        assertEquals(6000, converted.size());
        assertEquals(List.of("p", "5999", "", "name"), converted.get(5999));
        assertEquals(stringify(original), stringify(TeaVMJsConverter.toJSObject(converted)));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void unnamedViewThreadsCanDecodeMessagesAcrossTheYieldBoundary() throws Exception {
        Throwable[] failure = { null };
        Thread view = new Thread(() -> {
            try {
                NGEPlatform platform = new TeaVMPlatform();
                for (int i = 0; i < 200; i++) {
                    Map<?, ?> message = platform.fromJSON("{\"type\":\"event\",\"node\":\"notes\"}", Map.class);
                    assertEquals("event", message.get("type"));
                    assertEquals("notes", message.get("node"));
                }
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        view.start();
        view.join();
        assertNull(failure[0]);
    }

    @JSBody(
        script = "return Array.from({length:80}, function(_,i) { return {id:String(i), " +
        "pages:Array.from({length:60}, function(_,j) { return [String(i),String(j)]; })}; });"
    )
    private static native JSObject nestedOwnedTree();

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void smallNestedArraysPreserveAllValuesInOneLargeOwnedTree() {
        List<Object> converted = TeaVMJsConverter.toJavaObject(nestedOwnedTree(), List.class, true);
        assertEquals(80, converted.size());
        for (int i = 0; i < converted.size(); i++) {
            Map<?, ?> row = (Map<?, ?>) converted.get(i);
            assertEquals(String.valueOf(i), row.get("id"));
            List<?> pages = (List<?>) row.get("pages");
            assertEquals(60, pages.size());
            for (int j = 0; j < pages.size(); j++) {
                assertEquals(List.of(String.valueOf(i), String.valueOf(j)), pages.get(j));
            }
        }
    }

    @JSBody(
        params = "array",
        script = "const values = ['text', 42, 1.25, true, null, undefined, ['inner'], {x: 'nested'}]; " +
        "const keys = ['text', 'integer', 'fraction', 'boolean', 'null', 'undefined', 'array', 'object']; " +
        "const result = array ? [] : {}; " +
        "values.forEach((value, index) => { let reads = 0; " +
        "Object.defineProperty(result, array ? index : keys[index], {enumerable: true, get() { " +
        "if (++reads !== 1) throw new Error('Accessor evaluated twice'); return value; }}); }); return result;"
    )
    private static native JSObject singleReadContainer(boolean array);

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void publicMapConversionReadsEveryAccessorOnce() {
        Map<String, Object> result = TeaVMJsConverter.toJavaMap(singleReadContainer(false));
        assertEquals("text", result.get("text"));
        assertEquals(Integer.valueOf(42), result.get("integer"));
        assertEquals(Double.valueOf(1.25), result.get("fraction"));
        assertEquals(Boolean.TRUE, result.get("boolean"));
        assertNull(result.get("null"));
        assertNull(result.get("undefined"));
        assertEquals(List.of("inner"), result.get("array"));
        assertEquals(Map.of("x", "nested"), result.get("object"));
        assertEquals(result, TeaVMJsConverter.toJavaObject(singleReadContainer(false), Map.class));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void publicMixedListConversionDoesNotReadAheadOrRepeatAccessors() {
        List<Object> expected = Arrays.asList("text", 42, 1.25, true, null, null, List.of("inner"), Map.of("x", "nested"));
        assertEquals(expected, TeaVMJsConverter.toJavaList(singleReadContainer(true)));
        assertEquals(expected, TeaVMJsConverter.toJavaObject(singleReadContainer(true), List.class));
    }

    @JSBody(
        script = "let reads = 0; const array = []; Object.defineProperty(array, '0', " +
        "{enumerable: true, get() { return ++reads === 1 ? 'first' : 'second'; }}); return array;"
    )
    private static native JSObject changingStringArray();

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void publicStringListConversionKeepsTheFirstAccessorValue() {
        assertEquals(List.of("first"), TeaVMJsConverter.toJavaList(changingStringArray()));
        assertEquals(List.of("first"), TeaVMJsConverter.toJavaObject(changingStringArray(), List.class));
    }

    @JSBody(
        script = "const trace = []; const child = {}; Object.defineProperty(child, 'value', " +
        "{enumerable: true, get() { trace.push('child'); return 42; }}); const array = []; " +
        "Object.defineProperty(array, '0', {enumerable: true, get() { trace.push('parent'); return child; }}); " +
        "Object.defineProperty(array, '1', {enumerable: true, get() { trace.push('next'); return 'last'; }}); " +
        "Object.defineProperty(array, 'trace', {value: trace}); return array;"
    )
    private static native JSObject nestedAccessors();

    @JSBody(params = "array", script = "return array.trace.join(',');")
    private static native String accessOrder(JSObject array);

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void publicConversionPreservesRecursiveAccessorOrder() {
        JSObject array = nestedAccessors();
        assertEquals(List.of(Map.of("value", 42), "last"), TeaVMJsConverter.toJavaList(array));
        assertEquals("parent,child,next", accessOrder(array));
    }

    @JSBody(
        script = "const reads = Object.create(null); return new Proxy({text: 'first', number: 42}, " +
        "{get(target, key) { if ((reads[key] = (reads[key] || 0) + 1) !== 1) " +
        "throw new Error('Proxy property evaluated twice'); return target[key]; }});"
    )
    private static native JSObject singleReadProxy();

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void publicMapConversionReadsProxyPropertiesOnce() {
        assertEquals(Map.of("text", "first", "number", 42), TeaVMJsConverter.toJavaMap(singleReadProxy()));
    }
}
