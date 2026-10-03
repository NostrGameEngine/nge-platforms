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

import java.io.Serializable;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import org.ngengine.platform.JsonObject;
import org.ngengine.platform.MemoryLimits;
import org.ngengine.platform.NGEUtils;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

final class TeaVMJsonObject extends JsonObject {

    private final JSObject object;

    TeaVMJsonObject(JSObject object) {
        this.object = object;
    }

    @Override
    public String getString(String name) {
        String value = stringProperty(object, name);
        if (value != null) return NGEUtils.safeString(value);
        return NGEUtils.safeString(TeaVMJsConverter.convertProperty(object, name));
    }

    @JSBody(
        params = { "object", "name" },
        script = "const value = object[name]; return typeof value === 'string' ? value : null;"
    )
    private static native String stringProperty(JSObject object, String name);

    @Override
    public int getInt(String name) {
        double value = intProperty(object, name);
        if (!Double.isNaN(value)) return (int) value;
        return NGEUtils.safeInt(TeaVMJsConverter.convertProperty(object, name));
    }

    @JSBody(
        params = { "object", "name" },
        script = "const value = object[name]; return typeof value === 'number' && Number.isInteger(value) && " +
        "value >= -2147483648 && value <= 2147483647 ? value : NaN;"
    )
    private static native double intProperty(JSObject object, String name);

    @Override
    public Instant getSecondsInstant(String name) {
        double value = secondsProperty(object, name);
        if (!Double.isNaN(value)) return Instant.ofEpochSecond((long) value);
        return NGEUtils.safeSecondsInstant(TeaVMJsConverter.convertProperty(object, name));
    }

    @JSBody(
        params = { "object", "name" },
        script = "const value = object[name]; return typeof value === 'number' && Number.isSafeInteger(value) " +
        "? value : NaN;"
    )
    private static native double secondsProperty(JSObject object, String name);

    @Override
    public List<List<String>> getStringRows(String name) {
        JSObject rows = property(object, name);
        MemoryLimits limits = NGEUtils.getPlatform().getMemoryLimits();
        // Custom policies must still see every value through safeString.
        if (limits.getClass() == MemoryLimits.class) {
            int maximum = validateAndCompactStringRows(rows);
            if (maximum >= 0) {
                if (!limits.checkForString(maximum)) {
                    throw new IllegalArgumentException("Input string is too large: " + maximum);
                }
                return Collections.unmodifiableList(new StringRows(rows));
            }
        }
        ArrayList<List<String>> result = new ArrayList<>();
        for (String[] row : NGEUtils.safeCollectionOfStringArray(TeaVMJsConverter.convertProperty(object, name))) {
            if (row.length != 0) result.add(Collections.unmodifiableList(java.util.Arrays.asList(row)));
        }
        return Collections.unmodifiableList(result);
    }

    private static final class StringRows extends AbstractList<List<String>> implements RandomAccess, Serializable {

        private static final long serialVersionUID = 1L;
        private final JSObject rows;
        private transient StringRow[] cache;

        StringRows(JSObject rows) {
            this.rows = rows;
        }

        @Override
        public List<String> get(int index) {
            if (index < 0 || index >= length(rows)) throw new IndexOutOfBoundsException(index);
            if (cache == null) cache = new StringRow[length(rows)];
            StringRow row = cache[index];
            if (row == null) cache[index] = row = new StringRow(element(rows, index));
            return row;
        }

        @Override
        public int size() {
            return length(rows);
        }

        private Object writeReplace() {
            return Collections.unmodifiableList(new ArrayList<>(this));
        }
    }

    private static final class StringRow extends AbstractList<String> implements RandomAccess, Serializable {

        private static final long serialVersionUID = 1L;
        private final JSObject row;

        StringRow(JSObject row) {
            this.row = row;
        }

        @Override
        public String get(int index) {
            if (index < 0 || index >= length(row)) throw new IndexOutOfBoundsException(index);
            return stringElement(row, index);
        }

        @Override
        public int size() {
            return length(row);
        }

        private Object writeReplace() {
            return Collections.unmodifiableList(new ArrayList<>(this));
        }
    }

    @JSBody(params = { "object", "name" }, script = "return object[name];")
    private static native JSObject property(JSObject object, String name);

    @JSBody(params = "array", script = "return array.length;")
    private static native int length(JSObject array);

    @JSBody(params = { "array", "index" }, script = "return array[index];")
    private static native JSObject element(JSObject array, int index);

    @JSBody(params = { "array", "index" }, script = "return array[index];")
    private static native String stringElement(JSObject array, int index);

    @JSBody(
        params = "rows",
        script = "if (!Array.isArray(rows)) return -1; let maximum = 0; " +
        "for (let i = 0; i < rows.length; i++) { const row = rows[i]; " +
        "if (!Array.isArray(row)) return -1; " +
        "for (let j = 0; j < row.length; j++) { const value = row[j]; " +
        "if (value === null) { row[j] = ''; continue; } " +
        "if (typeof value !== 'string') return -1; maximum = Math.max(maximum, value.length); } } " +
        "let count = 0; for (let i = 0; i < rows.length; i++) { " +
        "if (rows[i].length !== 0) rows[count++] = rows[i]; } rows.length = count; return maximum;"
    )
    private static native int validateAndCompactStringRows(JSObject rows);
}
