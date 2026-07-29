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

import java.nio.ByteBuffer;
import org.ngengine.platform.teavm.webrtc.RTCDataChannel;
import org.ngengine.platform.teavm.webrtc.RTCIceCandidate;
import org.ngengine.platform.teavm.webrtc.RTCMessageCallback;
import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.teavm.jso.JSBuffer;
import org.teavm.jso.JSBufferType;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSModule;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSTopLevel;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.jso.core.JSArray;
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
    @JSByRef(optional = true)
    public static native byte[] randomBytes(int length);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int randomBytesBuffer(@JSBuffer(JSBufferType.UINT8) ByteBuffer output);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] generatePrivateKey();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int generatePrivateKeyBuffer(@JSBuffer(JSBufferType.UINT8) ByteBuffer output);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] genPubKey(@JSByRef(optional = true) byte[] secKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int genPubKeyBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer secKey,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] sha256(@JSByRef(optional = true) byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int sha256Buffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String toJSON(Object obj);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native Object fromJSON(String json);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] sign(@JSByRef(optional = true) byte[] data, @JSByRef(optional = true) byte[] privKeyBytes);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int signBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer privKeyBytes,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean verify(
        @JSByRef(optional = true) byte[] data,
        @JSByRef(optional = true) byte[] pub,
        @JSByRef(optional = true) byte[] sig
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean verifyBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer pub,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer sig
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] secp256k1SharedSecret(
        @JSByRef(optional = true) byte[] privKey,
        @JSByRef(optional = true) byte[] pubKey
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int secp256k1SharedSecretBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer privKey,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer pubKey,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean secp256k1PrivateKeyVerify(@JSByRef(optional = true) byte[] privateKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean secp256k1PrivateKeyVerifyBuffer(@JSBuffer(JSBufferType.UINT8) ByteBuffer privateKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean secp256k1PublicKeyVerify(@JSByRef(optional = true) byte[] publicKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean secp256k1PublicKeyVerifyBuffer(@JSBuffer(JSBufferType.UINT8) ByteBuffer publicKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] secp256k1PublicKeyCreate(@JSByRef(optional = true) byte[] privateKey, boolean compressed);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int secp256k1PublicKeyCreateBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer privateKey,
        boolean compressed,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] secp256k1SignRecoverable(
        @JSByRef(optional = true) byte[] hash32,
        @JSByRef(optional = true) byte[] privateKey
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int secp256k1SignRecoverableBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer hash32,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer privateKey,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] secp256k1RecoverPublicKey(
        @JSByRef(optional = true) byte[] hash32,
        @JSByRef(optional = true) byte[] signature64,
        int recoveryId,
        boolean compressed
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int secp256k1RecoverPublicKeyBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer hash32,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer signature64,
        int recoveryId,
        boolean compressed,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] hmac(
        @JSByRef(optional = true) byte[] key,
        @JSByRef(optional = true) byte[] data1,
        @JSByRef(optional = true) byte[] data2
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int hmacBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer key,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data1,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data2,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] hkdf_extract(@JSByRef(optional = true) byte[] salt, @JSByRef(optional = true) byte[] ikm);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int hkdfExtractBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer salt,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer ikm,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] hkdf_expand(
        @JSByRef(optional = true) byte[] prk,
        @JSByRef(optional = true) byte[] info,
        int length
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int hkdfExpandBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer prk,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer info,
        int length,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String base64encode(@JSByRef(optional = true) byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String base64encodeBuffer(@JSBuffer(JSBufferType.UINT8) ByteBuffer data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] base64decode(String data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int base64decodeBuffer(String data, @JSBuffer(JSBufferType.UINT8) ByteBuffer output);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] chacha20(
        @JSByRef(optional = true) byte[] key,
        @JSByRef(optional = true) byte[] nonce,
        @JSByRef(optional = true) byte[] data
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int chacha20Buffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer key,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer nonce,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void getClipboardContentAsync(JSConsumer<String> res, JSConsumer<String> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void setClipboardContent(String content);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void setTimeout(TimerHandler fn, int delay);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] getBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean hasBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] aes256cbc(
        @JSByRef(optional = true) byte[] key,
        @JSByRef(optional = true) byte[] iv,
        @JSByRef(optional = true) byte[] data,
        boolean forEncryption
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int aes256cbcBuffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer key,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer iv,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        boolean forEncryption,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileExistsAsync(String name, String path, JSConsumer<JSBoolean> res, JSConsumer<String> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileReadAsync(String name, String path, JSConsumer<BytesWrapper> res, JSConsumer<String> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileWriteAsync(
        String name,
        String path,
        @JSByRef(optional = true) byte[] data,
        JSConsumer<Void> callback,
        JSConsumer<String> errorCallback
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileDeleteAsync(
        String name,
        String path,
        JSConsumer<Void> callback,
        JSConsumer<String> errorCallback
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void vfileListAllAsync(String name, JSConsumer<JSArray<JSString>> res, JSConsumer<String> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String getPlatformName();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void callFunction(String function, String args, JSConsumer<JSString> res, JSConsumer<String> rej);

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
        @JSByRef(optional = true) byte[] P,
        @JSByRef(optional = true) byte[] S,
        int N,
        int r,
        int p2,
        int dkLen,
        JSConsumer<BytesWrapper> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    @JSByRef(optional = true)
    public static native byte[] xchacha20poly1305(
        @JSByRef(optional = true) byte[] key,
        @JSByRef(optional = true) byte[] nonce,
        @JSByRef(optional = true) byte[] data,
        @JSByRef(optional = true) byte[] associatedData,
        boolean forEncryption
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int xchacha20poly1305Buffer(
        @JSBuffer(JSBufferType.UINT8) ByteBuffer key,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer nonce,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer data,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer associatedData,
        boolean forEncryption,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer output
    );

    @JSFunctor
    public interface FinalizerCallback extends JSObject {
        void call();
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native FinalizerCallback registerFinalizer(Object obj, FinalizerCallback callback);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcSetLocalDescriptionAsync(
        RTCPeerConnection conn,
        String sdp,
        String type,
        JSConsumer<Void> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcSetRemoteDescriptionAsync(
        RTCPeerConnection conn,
        String sdp,
        String type,
        JSConsumer<Void> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcAddIceCandidateAsync(
        RTCPeerConnection conn,
        RTCIceCandidate candidate,
        JSConsumer<Void> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcCreateAnswerAsync(
        RTCPeerConnection conn,
        JSConsumer<RTCSessionDescription> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcCreateOfferAsync(
        RTCPeerConnection conn,
        JSConsumer<RTCSessionDescription> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native RTCPeerConnection rtcCreatePeerConnection(String urls[]);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native RTCDataChannel rtcCreateDataChannel(
        RTCPeerConnection conn,
        String label,
        String protocol,
        boolean ordered,
        boolean reliable,
        int maxRetransmits,
        int maxPacketLifeTimeMs
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native RTCIceCandidate rtcCreateIceCandidate(String candidateJson, String mediaId);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native String rtcDataChannelGetProtocol(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean rtcDataChannelIsOrdered(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native boolean rtcDataChannelIsReliable(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int rtcDataChannelGetMaxRetransmits(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int rtcDataChannelGetMaxPacketLifeTime(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native double rtcGetMaxMessageSize(RTCPeerConnection conn);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native double rtcDataChannelGetBufferedAmount(RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native double rtcDataChannelGetAvailableAmount(RTCPeerConnection conn, RTCDataChannel channel);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcDataChannelSetBufferedAmountLowThreshold(RTCDataChannel channel, double threshold);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void fetchAsync(
        String method,
        String url,
        String headersJson,
        @JSByRef(optional = true) byte[] body,
        int timeoutMs,
        JSConsumer<TeaVMHttpResponse> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void fetchBufferAsync(
        String method,
        String url,
        String headersJson,
        @JSBuffer(JSBufferType.UINT8) ByteBuffer body,
        int timeoutMs,
        JSConsumer<TeaVMHttpResponse> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void fetchStreamAsync(
        String method,
        String url,
        String headersJson,
        @JSByRef(optional = true) byte[] body,
        int timeoutMs,
        JSConsumer<TeaVMHttpStreamResponse> res,
        JSConsumer<String> rej
    );

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native int newPromise();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void resolvePromise(int id);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rejectPromise(int id);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void waitPromiseAsync(int id, JSConsumer<Void> res, JSConsumer<String> rej);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void freePromise(int id);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void rtcSetOnMessageHandler(RTCDataChannel channel, RTCMessageCallback callback);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public static native void panic(String err);
}
