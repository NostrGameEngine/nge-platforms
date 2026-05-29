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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class JVMAsyncPlatformRedirectTest {

    @Test
    public void redirectedLoopbackUrlsAreRejected() throws Exception {
        JVMAsyncPlatform platform = new JVMAsyncPlatform();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://example.com/start")).GET().build();
        HttpResponse<Void> response = new FixedResponse(307, "http://127.0.0.1/secret", request);

        Method buildRedirectRequest =
            JVMAsyncPlatform.class.getDeclaredMethod(
                    "buildValidatedRedirectRequest",
                    HttpRequest.class,
                    HttpResponse.class,
                    byte[].class,
                    Map.class,
                    Duration.class,
                    int.class
                );
        buildRedirectRequest.setAccessible(true);

        try {
            buildRedirectRequest.invoke(platform, request, response, null, null, Duration.ofSeconds(5), 0);
            fail("Expected loopback redirect to be rejected");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            String message = e.getCause().getMessage();
            assertTrue(message.contains("private or local address") || message.contains("Loopback addresses are not allowed"));
        }
    }

    private static final class FixedResponse implements HttpResponse<Void> {

        private final int statusCode;
        private final HttpHeaders headers;
        private final HttpRequest request;

        private FixedResponse(int statusCode, String location, HttpRequest request) {
            this.statusCode = statusCode;
            this.headers = HttpHeaders.of(Map.of("location", List.of(location)), (name, value) -> true);
            this.request = request;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<Void>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public Void body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
