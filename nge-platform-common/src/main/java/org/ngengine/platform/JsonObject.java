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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Typed access to an owned parsed JSON object, without exposing mutable storage. */
public abstract class JsonObject {

    public abstract String getString(String name);

    public abstract int getInt(String name);

    public abstract Instant getSecondsInstant(String name);

    /** Returns an owned immutable string matrix, omitting empty rows. */
    public abstract List<List<String>> getStringRows(String name);

    static JsonObject fromMap(Map<String, Object> values) {
        return new JsonObject() {
            @Override
            public String getString(String name) {
                return NGEUtils.safeString(values.get(name));
            }

            @Override
            public int getInt(String name) {
                return NGEUtils.safeInt(values.get(name));
            }

            @Override
            public Instant getSecondsInstant(String name) {
                return NGEUtils.safeSecondsInstant(values.get(name));
            }

            @Override
            public List<List<String>> getStringRows(String name) {
                ArrayList<List<String>> rows = new ArrayList<>();
                for (String[] row : NGEUtils.safeCollectionOfStringArray(values.get(name))) {
                    if (row.length != 0) rows.add(Collections.unmodifiableList(Arrays.asList(row)));
                }
                return Collections.unmodifiableList(rows);
            }
        };
    }
}
