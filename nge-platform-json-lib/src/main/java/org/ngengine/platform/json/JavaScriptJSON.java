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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** NGE's JavaScript compatibility layer over Gson, scoped to platform JSON trees. */
public final class JavaScriptJSON {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private JavaScriptJSON() {}

    /**
     * Serializes using Gson, after adapting Java values to the platform's JavaScript JSON model.
     * @param value a Java map/list tree, scalar, array, or null
     * @return its JSON.stringify-compatible representation
     */
    public static String stringify(Object value) {
        JsonElement tree = toTree(value, new IdentityHashMap<>(), 0);
        JavaScriptEscapes output = new JavaScriptEscapes();
        try (JsonWriter writer = GSON.newJsonWriter(output)) {
            GSON.toJson(tree, writer);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize JSON tree", error);
        }
        return output.toString();
    }

    /**
     * Copies parsed JSON objects to owned insertion-ordered maps, recursively.
     * @param value a parsed JSON tree
     * @return a tree with LinkedHashMap objects in JavaScript property order
     */
    public static Object orderedCopy(Object value) {
        return orderedCopy(value, 0);
    }

    private static Object orderedCopy(Object value, int depth) {
        if (depth > 128) throw new IllegalArgumentException("JSON tree is too deep");
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : orderedProperties((Map<?, ?>) value).entrySet()) {
                result.put(entry.getKey(), orderedCopy(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof Collection) {
            ArrayList<Object> result = new ArrayList<>(((Collection<?>) value).size());
            for (Object item : (Collection<?>) value) result.add(orderedCopy(item, depth + 1));
            return result;
        }
        return value;
    }

    private static Map<String, Object> orderedProperties(Map<?, ?> source) {
        Map<String, Object> strings = new LinkedHashMap<>();
        TreeMap<Long, Object> indices = new TreeMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            long index = arrayIndex(key);
            if (index >= 0) indices.put(index, entry.getValue()); else strings.put(key, entry.getValue());
        }
        if (indices.isEmpty()) return strings;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Object> entry : indices.entrySet()) result.put(entry.getKey().toString(), entry.getValue());
        result.putAll(strings);
        return result;
    }

    private static long arrayIndex(String key) {
        if (key.isEmpty() || key.length() > 10 || (key.length() > 1 && key.charAt(0) == '0')) return -1;
        long value = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + c - '0';
        }
        return value < 4294967295L ? value : -1;
    }

    private static JsonElement toTree(Object value, IdentityHashMap<Object, Boolean> active, int depth) {
        if (depth > 128) throw new IllegalArgumentException("JSON tree is too deep");
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String || value instanceof Character) return new JsonPrimitive(String.valueOf(value));
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Number || value instanceof Date) {
            double number = value instanceof Date ? ((Date) value).getTime() : ((Number) value).doubleValue();
            return Double.isFinite(number) ? new JsonPrimitive(new JavaScriptNumber(number)) : JsonNull.INSTANCE;
        }
        if (value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
            if (active.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic JSON tree");
            try {
                if (value instanceof Map) {
                    JsonObject result = new JsonObject();
                    for (Map.Entry<String, Object> entry : orderedProperties((Map<?, ?>) value).entrySet()) {
                        result.add(entry.getKey(), toTree(entry.getValue(), active, depth + 1));
                    }
                    return result;
                }
                JsonArray result = new JsonArray();
                if (value instanceof Collection) {
                    for (Object item : (Collection<?>) value) result.add(toTree(item, active, depth + 1));
                } else {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        result.add(toTree(Array.get(value, i), active, depth + 1));
                    }
                }
                return result;
            } finally {
                active.remove(value);
            }
        }
        return new JsonPrimitive(value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value));
    }

    /** Gson's numeric adapter uses this Number's already validated decimal representation. */
    private static final class JavaScriptNumber extends Number {

        private static final long serialVersionUID = 1L;
        private final double value;
        private final String decimal;

        JavaScriptNumber(double value) {
            this.value = value;
            this.decimal = JavaScriptNumbers.format(value);
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return (long) value;
        }

        @Override
        public float floatValue() {
            return (float) value;
        }

        @Override
        public double doubleValue() {
            return value;
        }

        @Override
        public String toString() {
            return decimal;
        }
    }

    /**
     * Gson owns the JSON syntax and escaping. This sink adjusts only its extra
     * U+2028/U+2029 escapes and lone UTF-16 surrogates to match JSON.stringify.
     * Escape state survives writes, so a literal escaped backslash is never
     * mistaken for a Unicode escape, even when Gson splits a token across calls.
     */
    static final class JavaScriptEscapes extends Writer {

        private final StringBuilder output = new StringBuilder();
        private final char[] unicode = new char[4];
        private int unicodeLength = -1;
        private boolean escaped;
        private char highSurrogate;

        @Override
        public void write(char[] chars, int offset, int length) {
            for (int i = offset; i < offset + length; i++) accept(chars[i]);
        }

        @Override
        public void write(String chars, int offset, int length) {
            for (int i = offset; i < offset + length; i++) accept(chars.charAt(i));
        }

        @Override
        public void write(int c) {
            accept((char) c);
        }

        private void accept(char c) {
            if (highSurrogate != 0) {
                char pending = highSurrogate;
                highSurrogate = 0;
                if (Character.isLowSurrogate(c)) {
                    output.append(pending).append(c);
                    return;
                }
                appendSurrogate(pending);
            }
            if (unicodeLength >= 0) {
                unicode[unicodeLength++] = c;
                if (unicodeLength == 4) {
                    if (
                        unicode[0] == '2' && unicode[1] == '0' && unicode[2] == '2' && (unicode[3] == '8' || unicode[3] == '9')
                    ) {
                        output.append((char) (unicode[3] == '8' ? 0x2028 : 0x2029));
                    } else {
                        output.append('\\').append('u').append(unicode);
                    }
                    unicodeLength = -1;
                }
                return;
            }
            if (escaped) {
                escaped = false;
                if (c == 'u') unicodeLength = 0; else output.append('\\').append(c);
                return;
            }
            if (c == '\\') escaped = true; else if (Character.isHighSurrogate(c)) highSurrogate = c; else if (
                Character.isLowSurrogate(c)
            ) appendSurrogate(c); else output.append(c);
        }

        private void appendSurrogate(char c) {
            output.append('\\').append('u');
            for (int shift = 12; shift >= 0; shift -= 4) output.append("0123456789abcdef".charAt((c >> shift) & 15));
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws IOException {
            if (escaped || unicodeLength >= 0 || highSurrogate != 0) throw new IOException("Incomplete JSON string escape");
        }

        @Override
        public String toString() {
            return output.toString();
        }
    }
}
