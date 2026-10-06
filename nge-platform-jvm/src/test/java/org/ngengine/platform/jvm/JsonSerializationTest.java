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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.platform.NGEPlatform;

public class JsonSerializationTest {

    @Test
    public void appendableSerializationPreservesConfiguredGsonSemantics() {
        Gson reference = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
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
        assertEquals(reference.toJson(object), platform.toJSON(object));
        assertEquals(reference.toJson(Arrays.asList(object, null)), platform.toJSON(Arrays.asList(object, null)));
        assertEquals("null", platform.toJSON((Map) null));
        assertEquals("null", platform.toJSON((List) null));
        assertThrows(IllegalArgumentException.class, () -> platform.toJSON(List.of(Double.NaN)));
    }
}
