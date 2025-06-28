import { chacha20 as _chacha20 } from '@noble/ciphers/chacha.js';
import { schnorr as _schnorr, secp256k1 as _secp256k1 } from '@noble/curves/secp256k1';
import { hmac as _hmac } from '@noble/hashes/hmac.js';
import { sha256 as _sha256 } from '@noble/hashes/sha2.js';
import { extract as _hkdf_extract, expand as _hkdf_expand } from '@noble/hashes/hkdf'
import { base64 as _base64 } from '@scure/base';
import { cbc } from '@noble/ciphers/aes';

// convert various buffer types to Uint8Array
const _u = (data) => {
    if (data instanceof Uint8Array) {
        return data;
    } else if (data instanceof Int8Array) {
        return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    } else if (Array.isArray(data)) {
        return new Uint8Array(data);
    } else if (data instanceof ArrayBuffer) {
        return new Uint8Array(data);
    } else if (data instanceof Uint8ClampedArray) {
        return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    } else if (data instanceof DataView) {
        return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    } else if (data instanceof Buffer) {
        return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    } else {
        throw new TypeError('Unsupported data type for conversion to Uint8Array');
    }
};

const sanitizeBigInts = (obj) => {
    // Base cases for non-objects
    if (obj === null || obj === undefined) {
        return obj;
    }

    // Convert BigInt to Number
    if (typeof obj === 'bigint') {
        // Warning: this may lose precision for very large values
        return Number(obj);
    }

    // Handle arrays
    if (Array.isArray(obj)) {
        return obj.map(item => sanitizeBigInts(item));
    }

    // Handle regular objects (but not special types like Date, RegExp, etc.)
    if (typeof obj === 'object' && Object.getPrototypeOf(obj) === Object.prototype) {
        const result = {};
        for (const key in obj) {
            if (Object.hasOwnProperty.call(obj, key)) {
                result[key] = sanitizeBigInts(obj[key]);
            }
        }
        return result;
    }

    // Return all other types unchanged
    return obj;
  };

export const randomBytes = (length /*int*/) => { // Uint8Array (byte[])
    return _schnorr.utils.randomBytes(length);
};

export const generatePrivateKey = () => { // Uint8Array (byte[])
    return _schnorr.utils.randomPrivateKey();
};

export const genPubKey = (secKey) => {// Uint8Array (byte[])
    return _schnorr.getPublicKey(_u(secKey));
};

export const sha256 = (data /*byte[]*/) => { // Uint8Array (byte[])
    return _sha256(_u(data));
};

export const toJSON = (obj /*obj*/) => { // str
    return JSON.stringify(sanitizeBigInts(obj));
};

export const fromJSON = (json/*str*/) => {
    return JSON.parse(json); // obj
};

export const sign = (data /*byte[]*/, privKeyBytes  /*byte[]*/) => {  // Uint8Array (byte[])
    return _schnorr.sign(_u(data), _u(privKeyBytes));
};

export const verify = (data /*byte[]*/, pub /*byte[]*/, sig/*byte[]*/) => { // Uint8Array (byte[])
    return _schnorr.verify(_u(sig), _u(data), _u(pub));
};


export const secp256k1SharedSecret = (privKey /*byte[]*/, pubKey /*byte[]*/) => { // Uint8Array (byte[])
    return _secp256k1.getSharedSecret(_u(privKey), _u(pubKey));
};

export const hmac = (key /*byte[]*/, data1 /*byte[]*/, data2 /*byte[]*/) => { // Uint8Array (byte[])
    const msg = new Uint8Array([..._u(data1), ..._u(data2)]);
    return _hmac(sha256, key, msg);
};

export const hkdf_extract = (salt /*byte[]*/, ikm /*byte[]*/) => { // Uint8Array (byte[])
    return _hkdf_extract(sha256, _u(ikm), _u(salt));
};

export const hkdf_expand = (prk/*byte[]*/, info/*byte[]*/, length/*int*/) => { // Uint8Array (byte[])
    return _hkdf_expand(sha256, _u(prk), _u(info), length);
};

export const base64encode = (data /*byte[]*/) => { //str
    return _base64.encode(_u(data));
};

export const base64decode = (data /*str*/) => { // Uint8Array (byte[])
    return _base64.decode(data);
};

export const chacha20 = (key/*byte[]*/, nonce/*byte[]*/, data/*byte[]*/) => { // Uint8Array (byte[])
    return _chacha20(_u(key), _u(nonce), _u(data));
};

export const setTimeout = (callback, delay) => { //void
    return ((typeof window !== 'undefined' && window) ||
        (typeof globalThis !== 'undefined' && globalThis) ||
        (typeof global !== 'undefined' && global) ||
        (typeof self !== 'undefined' && self)).setTimeout(callback, delay);
}

export const getClipboardContent = async () => { //str
    try {
        const text = await navigator.clipboard.readText();
        return text;
    } catch (err) {
        console.error('Failed to read clipboard contents: ', err);
        return null;
    }
}

export const setClipboardContent = async (text) => { //void
    try {
        await navigator.clipboard.writeText(text);
    } catch (err) {
        console.error('Failed to write to clipboard: ', err);
    }
}

export const hasBundledResource = (path) => { // boolean
    if (path.startsWith('/')) {
        path = path.substring(1);
    }
    const bundle = ((typeof window !== 'undefined' && window) ||
        (typeof globalThis !== 'undefined' && globalThis) ||
        (typeof global !== 'undefined' && global) ||
        (typeof self !== 'undefined' && self))?.NGEBundledResources;
    if (!bundle) {
        console.warn('No bundled resources found. Ensure the bundler is configured correctly.');
        return false;
    }
    if (!bundle[path]) {
        console.warn('Resource not found in bundle:', path);
        return false;
    }
    return true;
}

export const getBundledResource = (path) => { // byte[]

    if (path.startsWith('/')) {
        path = path.substring(1);
    }

    const bundle = ((typeof window !== 'undefined' && window) ||
        (typeof globalThis !== 'undefined' && globalThis) ||
        (typeof global !== 'undefined' && global) ||
        (typeof self !== 'undefined' && self))?.NGEBundledResources;

    if (!bundle) {
        console.warn('No bundled resources found. Ensure the bundler is configured correctly.');
        return null;
    }

    if (!bundle[path]) {
        console.warn('Resource not found in bundle:', path);
        return null;
    }

    return base64decode(bundle[path]);

}



export const aes256cbc = (key/*byte[]*/, iv/*byte[]*/, data/*byte[]*/, forEncryption/*bool*/) => { // Uint8Array (byte[])
    key = _u(key);
    iv = _u(iv);
    data = _u(data);

    if (key.length !== 32) {
        throw new Error('AES-256 requires a 32-byte key');
    }

    if (iv.length !== 16) {
        throw new Error('AES-CBC requires a 16-byte IV');
    }

    try {
        const cipher = cbc(key, iv);
        return forEncryption ? cipher.seal(data) : cipher.open(data);
    } catch (error) {
        console.error('AES-256-CBC operation failed:', error);
        throw error;
    }
};

// Expose functions on the global object so TeaVM can call them.
// const nostr4j_jsBinds = {
//     randomBytes,
//     generatePrivateKey,
//     genPubKey,
//     sha256,
//     toJSON,
//     fromJSON,
//     sign,
//     verify,
//     secp256k1SharedSecret,
//     hmac,
//     hkdf_extract,
//     hkdf_expand,
//     base64encode,
//     base64decode,
//     chacha20,
//     setTimeout,
//     getClipboardContent,
//     setClipboardContent
// };

// if (typeof window !== 'undefined') {
//     window.nostr4j_jsBinds = nostr4j_jsBinds;
// } else if (typeof globalThis !== 'undefined') {
//     globalThis.nostr4j_jsBinds = nostr4j_jsBinds;
// } else if (typeof global !== 'undefined') {
//     global.nostr4j_jsBinds = nostr4j_jsBinds;
// } else if (typeof self !== 'undefined') {
//     self.nostr4j_jsBinds = nostr4j_jsBinds;
// }
// export default nostr4j_jsBinds; 