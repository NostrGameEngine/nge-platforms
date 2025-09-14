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

import org.ngengine.platform.teavm.webrtc.RTCIceCandidate;
import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSModule;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSTopLevel;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.jso.core.JSBoolean;
import org.teavm.jso.core.JSString;
import org.teavm.jso.function.JSConsumer;

/**
 * Run ./gradlew build to generate the TeaVMBinds.bundle.js file
 */
@JSClass
public class TeaVMBinds implements JSObject {

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] randomBytes(int length);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] generatePrivateKey();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] genPubKey(@JSByRef byte[] secKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] sha256(@JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String toJSON(Object obj);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native Object fromJSON(String json);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] sign(@JSByRef byte[] data, @JSByRef byte[] privKeyBytes);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean verify(@JSByRef byte[] data, @JSByRef byte[] pub, @JSByRef byte[] sig);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] secp256k1SharedSecret(@JSByRef byte[] privKey, @JSByRef byte[] pubKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] hmac(@JSByRef byte[] key, @JSByRef byte[] data1, @JSByRef byte[] data2);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] hkdf_extract(@JSByRef byte[] salt, @JSByRef byte[] ikm);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] hkdf_expand(@JSByRef byte[] prk, @JSByRef byte[] info, int length);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String base64encode(@JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] base64decode(String data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] chacha20(@JSByRef byte[] key, @JSByRef byte[] nonce, @JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void getClipboardContentAsync(JSConsumer<String> res, JSConsumer<Throwable> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void setClipboardContent(String content);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void setTimeout(TimerHandler fn, int delay);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] getBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native boolean hasBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] aes256cbc(@JSByRef byte[] key, @JSByRef byte[] iv, @JSByRef byte[] data, boolean forEncryption);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileExistsAsync(String name, String path, JSConsumer<Boolean> res, JSConsumer<Throwable> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileReadAsync(String name, String path, JSConsumer<byte[]> res, JSConsumer<Throwable> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileWriteAsync(
        String name,
        String path,
        @JSByRef byte[] data,
        JSConsumer<Void> callback,
        JSConsumer<Throwable> errorCallback
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileDeleteAsync(
        String name,
        String path,
        JSConsumer<Void> callback,
        JSConsumer<Throwable> errorCallback
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileListAllAsync(String name, JSConsumer<String[]> res, JSConsumer<Throwable> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String getPlatformName();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void callFunction(String function, String args, JSConsumer<JSString> res, JSConsumer<Throwable> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void canCallFunction(String function, JSConsumer<JSBoolean> res);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void openURL(String url);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String nfkc(String str);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void scryptAsync(
        @JSByRef byte[] P,
        @JSByRef byte[] S,
        int N,
        int r,
        int p2,
        int dkLen,
        JSConsumer<byte[]> res,
        JSConsumer<Throwable> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native byte[] xchacha20poly1305(
        @JSByRef byte[] key,
        @JSByRef byte[] nonce,
        @JSByRef byte[] data,
        @JSByRef byte[] associatedData,
        boolean forEncryption
    );

    @JSFunctor
    public static interface FinalizerCallback extends JSObject {
        void call();
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef
    public static native FinalizerCallback registerFinalizer(Object obj, FinalizerCallback callback);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcSetLocalDescriptionAsync(
        RTCPeerConnection conn,
        String sdp,
        String type,
        JSConsumer<Void> res,
        JSConsumer<Throwable> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcSetRemoteDescriptionAsync(
        RTCPeerConnection conn,
        String sdp,
        String type,
        JSConsumer<Void> res,
        JSConsumer<Throwable> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcCreateAnswerAsync(
        RTCPeerConnection conn,
        JSConsumer<RTCSessionDescription> res,
        JSConsumer<Throwable> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcCreateOfferAsync(
        RTCPeerConnection conn,
        JSConsumer<RTCSessionDescription> res,
        JSConsumer<Throwable> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native RTCPeerConnection rtcCreatePeerConnection(String urls[]);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native RTCIceCandidate rtcCreateIceCandidate(String candidateJson);
}
