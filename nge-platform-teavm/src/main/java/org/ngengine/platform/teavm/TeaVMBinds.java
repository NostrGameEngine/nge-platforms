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



import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSModule;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSTopLevel;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.jso.core.JSBoolean;
import org.teavm.jso.core.JSString;
import org.teavm.jso.function.JSConsumer;

@JSClass
public class TeaVMBinds implements JSObject {

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] randomBytes(int length);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] generatePrivateKey();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] genPubKey(@JSByRef byte[] secKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] sha256(@JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native String toJSON(Object obj);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native Object fromJSON(String json);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] sign(@JSByRef byte[] data, @JSByRef byte[] privKeyBytes);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native boolean verify(@JSByRef byte[] data, @JSByRef byte[] pub, @JSByRef byte[] sig);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] secp256k1SharedSecret(@JSByRef byte[] privKey, @JSByRef byte[] pubKey);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] hmac(@JSByRef byte[] key, @JSByRef byte[] data1, @JSByRef byte[] data2);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] hkdf_extract(@JSByRef byte[] salt, @JSByRef byte[] ikm);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] hkdf_expand(@JSByRef byte[] prk, @JSByRef byte[] info, int length);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native String base64encode(@JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] base64decode(String data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] chacha20(@JSByRef byte[] key, @JSByRef byte[] nonce, @JSByRef byte[] data);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native String getClipboardContent();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void setClipboardContent(String content);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void setTimeout(TimerHandler fn, int delay);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] getBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native boolean hasBundledResource(String path);

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] aes256cbc(byte[] key, byte[] iv, byte[] data, boolean forEncryption);

    /**
     * Checks if a file exists in the virtual file store
     */
    @Async
    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native Boolean vfileExists(String name, String path);

    private static void vfileExists(String name, String path, AsyncCallback<Boolean> callback) {
        vfileExistsAsync(name, path, result -> callback.complete(result), error -> callback.error(error));
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    private static native void vfileExistsAsync(String name, String path, JSConsumer<Boolean> res, JSConsumer<Throwable> rej);

    /**
     * Reads a file from the virtual file store
     */
    @Async
    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    @JSByRef
    public static native byte[] vfileRead(String name, String path);

    private static void vfileRead(String name, String path, @JSByRef AsyncCallback<byte[]> callback) {
        vfileReadAsync(name, path, result -> callback.complete(result), error -> callback.error(error));
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    private static native void vfileReadAsync(
        String name,
        String path,
        @JSByRef JSConsumer<byte[]> res,
        JSConsumer<Throwable> rej
    );

    /**
     * Writes a file to the virtual file store
     */
    @Async
    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void vfileWrite(String name, String path, @JSByRef byte[] data);

    private static void vfileWrite(String name, String path, @JSByRef byte[] data, AsyncCallback<Void> callback) {
        vfileWriteAsync(name, path, data, r -> callback.complete(null), error -> callback.error(error));
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    private static native void vfileWriteAsync(
        String name,
        String path,
        @JSByRef byte[] data,
        JSConsumer<Void> callback,
        JSConsumer<Throwable> errorCallback
    );

    /**
     * Deletes a file from the virtual file store
     */
    @Async
    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void vfileDelete(String name, String path);

    private static void vfileDelete(String name, String path, AsyncCallback<Void> callback) {
        vfileDeleteAsync(name, path, r -> callback.complete(null), error -> callback.error(error));
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    private static native void vfileDeleteAsync(
        String name,
        String path,
        JSConsumer<Void> callback,
        JSConsumer<Throwable> errorCallback
    );

    /**
     * Lists all files in the virtual file store
     */
    @Async
    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native String[] vfileListAll(String name);

    private static void vfileListAll(String name, AsyncCallback<String[]> callback) {
        vfileListAllAsync(name, result -> callback.complete(result), error -> callback.error(error));
    }

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    private static native void vfileListAllAsync(String name, JSConsumer<String[]> res, JSConsumer<Throwable> rej);


    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native String getPlatformName();

    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void callFunction(String function, String args, JSConsumer<JSString> res, JSConsumer<Throwable> rej);


    @JSTopLevel
    @JSModule("./org/ngengine/platform/teavm/TeaVMBinds.js")
    public static native void canCallFunction(String function, JSConsumer<JSBoolean> res);
      
}