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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
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

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.webrtc.RTCIceCandidate;
import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.ngengine.platform.transport.NGEHttpResponse;
import org.teavm.jso.JSBody;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMBindsAsyncTest {

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void pendingRtcOperationsCompleteInOrder() {
        RTCPeerConnection conn = rtcStub();
        RTCSessionDescription offer = TeaVMBindsAsync.rtcCreateOffer(conn);
        assertEquals("offer-sdp", offer.getSdp());
        TeaVMBindsAsync.rtcSetLocalDescription(conn, offer.getSdp(), "offer");
        TeaVMBindsAsync.rtcSetRemoteDescription(conn, "remote-sdp", "offer");
        RTCSessionDescription answer = TeaVMBindsAsync.rtcCreateAnswer(conn);
        assertEquals("answer-sdp", answer.getSdp());
        TeaVMBindsAsync.rtcAddIceCandidate(conn, candidateStub());
        assertEquals("offer,local:offer-sdp,remote:remote-sdp,answer,ice", rtcCalls(conn));
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void rtcOfferFailureReachesCaller() {
        try {
            TeaVMBindsAsync.rtcCreateOffer(failingRtcStub());
            fail("Expected RTC offer failure");
        } catch (RuntimeException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains("offer failed"));
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void pendingFetchesPreserveResponseBody() {
        NGEPlatform.set(new TeaVMPlatform());
        installFetchStub();
        try {
            NGEHttpResponse bytes = TeaVMBindsAsync.fetch("POST", "https://example.invalid/bytes", "{}", new byte[] { 1 }, 1000);
            assertEquals(201, bytes.statusCode());
            assertArrayEquals(new byte[] { 0, 127, -1 }, bytes.body());

            ByteBuffer requestBody = ByteBuffer.allocateDirect(1);
            requestBody.put((byte) 2).flip();
            NGEHttpResponse buffer = TeaVMBindsAsync.fetchBuffer("POST", "https://example.invalid/buffer", "{}", requestBody, 1000);
            assertEquals(201, buffer.statusCode());
            assertArrayEquals(new byte[] { 0, 127, -1 }, buffer.body());
        } finally {
            clearFetchStub();
        }
    }

    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void fetchFailureReachesCaller() {
        installFailingFetchStub();
        try {
            TeaVMBindsAsync.fetch("GET", "https://example.invalid/failure", "{}", new byte[0], 1000);
            fail("Expected fetch failure");
        } catch (RuntimeException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains("fetch failed"));
        } finally {
            clearFetchStub();
        }
    }

    @JSBody(script = "const later = value => new Promise(resolve => setTimeout(() => resolve(value), 1));"
        + "return { calls: [], createOffer() { this.calls.push('offer'); return later({type:'offer',sdp:'offer-sdp'}); },"
        + "setLocalDescription(d) { this.calls.push('local:' + d.sdp); return later(); },"
        + "setRemoteDescription(d) { this.calls.push('remote:' + d.sdp); return later(); },"
        + "createAnswer() { this.calls.push('answer'); return later({type:'answer',sdp:'answer-sdp'}); },"
        + "addIceCandidate() { this.calls.push('ice'); return later(); } };"
    )
    private static native RTCPeerConnection rtcStub();

    @JSBody(script = "return { createOffer() { return new Promise((resolve, reject) => setTimeout(() => reject(new Error('offer failed')), 1)); } };")
    private static native RTCPeerConnection failingRtcStub();

    @JSBody(script = "return { candidate: 'candidate:1', sdpMid: '0' };")
    private static native RTCIceCandidate candidateStub();

    @JSBody(params = { "conn" }, script = "return conn.calls.join(',');")
    private static native String rtcCalls(RTCPeerConnection conn);

    @JSBody(script = "globalThis.ngeFetch = () => new Promise(resolve => setTimeout(() => resolve({"
        + "status: 201, headers: { forEach(fn) { fn('test', 'x-test'); } },"
        + "arrayBuffer() { return Promise.resolve(new Uint8Array([0,127,255]).buffer); }"
        + "}), 1));")
    private static native void installFetchStub();

    @JSBody(script = "globalThis.ngeFetch = () => new Promise((resolve, reject) => setTimeout(() => reject(new Error('fetch failed')), 1));")
    private static native void installFailingFetchStub();

    @JSBody(script = "delete globalThis.ngeFetch;")
    private static native void clearFetchStub();
}
