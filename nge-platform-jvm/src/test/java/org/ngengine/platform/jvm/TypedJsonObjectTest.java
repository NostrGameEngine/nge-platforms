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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.platform.JsonObject;

public class TypedJsonObjectTest {

    @Test
    public void typedJsonRejectsNonObjectRootsIncludingGsonMapArraySyntax() {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        for (String json : new String[] { "null", "[]", "[[\"x\",\"y\"]]", "42", "\"text\"", "true", "false", " \n [] \t" }) {
            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> platform.parseJsonObject(json)
            );
            assertEquals(json, "JSON root must be an object", failure.getMessage());
        }
        // Generic map decoding retains Gson compatibility; typed object decoding is stricter.
        assertEquals(Map.of("x", "y"), platform.fromJSON("[[\"x\",\"y\"]]", Map.class));
    }

    @Test
    public void typedJsonPreservesValidObjectReadsAndImmutableRows() {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        JsonObject empty = platform.parseJsonObject("{}");
        assertEquals("", empty.getString("content"));
        assertEquals(0, empty.getInt("kind"));
        assertTrue(empty.getStringRows("tags").isEmpty());
        JsonObject object = platform.parseJsonObject(
            "{\"content\":\"Unicode 🦊\",\"kind\":7,\"created_at\":1700000000,\"tags\":[[],[\"t\",\"value\"]]}"
        );
        assertEquals("Unicode 🦊", object.getString("content"));
        assertEquals(7, object.getInt("kind"));
        assertEquals(Instant.ofEpochSecond(1700000000), object.getSecondsInstant("created_at"));
        List<List<String>> rows = object.getStringRows("tags");
        assertEquals(List.of(List.of("t", "value")), rows);
        assertThrows(UnsupportedOperationException.class, () -> rows.add(List.of("changed")));
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).set(0, "changed"));
    }
}
