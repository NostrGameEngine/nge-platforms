package org.ngengine.platform.teavm;

import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSByRef;

public class TeaVMBindsAsync {

    @Async
    public static native String getClipboardContent();

    private static void getClipboardContent(@JSByRef AsyncCallback<String> callback) {
        TeaVMBinds.getClipboardContentAsync(result -> callback.complete(result), error -> callback.error(error));
    }

    @Async

    @JSByRef
    public static native byte[] scrypt(@JSByRef byte[] P, @JSByRef byte[] S, int N, int r, int p2, int dkLen);

    private static void scrypt(@JSByRef byte[] P, @JSByRef byte[] S, int N, int r, int p2, int dkLen,
            @JSByRef AsyncCallback<byte[]> callback) {
        TeaVMBinds
                .scryptAsync(P, S, N, r, p2, dkLen, result -> callback.complete(result),
                        error -> callback.error(error));
    }

    /**
     * Checks if a file exists in the virtual file store
     */
    @Async
    public static native Boolean vfileExists(String name, String path);

    private static void vfileExists(String name, String path, AsyncCallback<Boolean> callback) {
        TeaVMBinds.vfileExistsAsync(name, path, result -> callback.complete(result), error -> callback.error(error));
    }

    /**
     * Reads a file from the virtual file store
     */
    @Async
    public static native byte[] vfileRead(String name, String path);

    private static void vfileRead(String name, String path, @JSByRef AsyncCallback<byte[]> callback) {
        TeaVMBinds.vfileReadAsync(name, path, result -> callback.complete(result), error -> callback.error(error));
    }

    /**
     * Writes a file to the virtual file store
     */
    @Async
    public static native void vfileWrite(String name, String path, @JSByRef byte[] data);

    private static void vfileWrite(String name, String path, @JSByRef byte[] data, AsyncCallback<Void> callback) {
        TeaVMBinds.vfileWriteAsync(name, path, data, r -> callback.complete(null), error -> callback.error(error));
    }

    /**
     * Deletes a file from the virtual file store
     */
    @Async
    public static native void vfileDelete(String name, String path);

    private static void vfileDelete(String name, String path, AsyncCallback<Void> callback) {
        TeaVMBinds.vfileDeleteAsync(name, path, r -> callback.complete(null), error -> callback.error(error));
    }

    /**
     * Lists all files in the virtual file store
     */
    @Async
    public static native String[] vfileListAll(String name);

    private static void vfileListAll(String name, AsyncCallback<String[]> callback) {
        TeaVMBinds.vfileListAllAsync(name, result -> {
            callback.complete(result != null ? result : new String[0]);
        }, error -> callback.error(error));
    }

    @Async
    public static native void rtcSetLocalDescription(RTCPeerConnection conn, String sdp, String type);

    private static void rtcSetLocalDescription(RTCPeerConnection conn, String sdp, String type,
              AsyncCallback<Void> callback) {
        TeaVMBinds
                .rtcSetLocalDescriptionAsync(conn, sdp, type, result -> callback.complete(result),
                        error -> callback.error(error));
    }

    @Async
    public static native void rtcSetRemoteDescription(RTCPeerConnection conn, String sdp, String type);

    private static void rtcSetRemoteDescription(RTCPeerConnection conn, String sdp, String type,
             AsyncCallback<Void> callback) {
        TeaVMBinds
                .rtcSetRemoteDescriptionAsync(conn, sdp, type, result -> callback.complete(result),
                        error -> callback.error(error));
    }

    @Async
    public static native RTCSessionDescription rtcCreateAnswer(RTCPeerConnection conn);

    private static void rtcCreateAnswer(RTCPeerConnection conn,
            AsyncCallback<RTCSessionDescription> callback) {
        TeaVMBinds
                .rtcCreateAnswerAsync(conn, result -> callback.complete(result),
                        error -> callback.error(error));
    }

    @Async
    public static native RTCSessionDescription rtcCreateOffer(RTCPeerConnection conn);

    private static void rtcCreateOffer(RTCPeerConnection conn,
              AsyncCallback<RTCSessionDescription> callback) {
        TeaVMBinds
                .rtcCreateOfferAsync(conn, result -> callback.complete(result),
                        error -> callback.error(error));
    }
}
