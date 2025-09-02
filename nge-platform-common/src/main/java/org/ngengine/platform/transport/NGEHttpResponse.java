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
package org.ngengine.platform.transport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NGEHttpResponse {

    public final int statusCode;
    public final Map<String, List<String>> headers;
    public final byte[] body;
    public final boolean status;

    public NGEHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body, boolean status) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.status = status;
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public boolean status() {
        return status;
    }

    @Override
    public String toString() {
        return (
            "NGEHttpResponse [statusCode=" +
            statusCode +
            ", headers=" +
            headers +
            ", body=" +
            (body != null ? body.length + " bytes" : "null") +
            ", status=" +
            status +
            "]"
        );
    }

    public String bodyAsString() {
        if (body == null) return null;
        return new String(body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(body), headers, statusCode, status);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NGEHttpResponse)) return false;
        NGEHttpResponse other = (NGEHttpResponse) obj;
        return (
            statusCode == other.statusCode &&
            status == other.status &&
            java.util.Arrays.equals(body, other.body) &&
            java.util.Objects.equals(headers, other.headers)
        );
    }
}
