import { chacha20 as _chacha20 } from '@noble/ciphers/chacha.js';
import { schnorr as _schnorr, secp256k1 as _secp256k1 } from '@noble/curves/secp256k1';
import { hmac as _hmac } from '@noble/hashes/hmac.js';
import { sha256 as _sha256 } from '@noble/hashes/sha2.js';
import { extract as _hkdf_extract, expand as _hkdf_expand } from '@noble/hashes/hkdf'
import { base64 as _base64 } from '@scure/base';
import { cbc } from '@noble/ciphers/aes';
import {  randomBytes as _randomBytes } from '@noble/hashes/utils.js';

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
    return _randomBytes(length);
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
    return JSON.stringify(sanitizeBigInts(obj), null, 0);
};

export const fromJSON = (json/*str*/) => {
    try{
        return JSON.parse(json); // obj
    } catch (e) {
        console.error('Error parsing JSON:', json, e);
        throw e;
    }
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
    return _hmac(_sha256, _u(key), msg);
};

export const hkdf_extract = (salt /*byte[]*/, ikm /*byte[]*/) => { // Uint8Array (byte[])
    return _hkdf_extract(_sha256, _u(ikm), _u(salt));
};

export const hkdf_expand = (prk/*byte[]*/, info/*byte[]*/, length/*int*/) => { // Uint8Array (byte[])
    return _hkdf_expand(_sha256, _u(prk), _u(info), length);
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


// use indexed db as a vfile store

async function getVFileStore(name) {
    // Get the global object (works in browser, workers, and other JS environments)
    const globalObj = (typeof window !== 'undefined' && window) ||
        (typeof self !== 'undefined' && self) ||
        (typeof globalThis !== 'undefined' && globalThis) ||
        (typeof global !== 'undefined' && global);

    // Check if IndexedDB is available in the current environment
    if (!globalObj.indexedDB) {
        console.warn('IndexedDB is not supported in this environment.');
        return {
            exists: async (path) => false,
            read: async (path) => null,
            write: async (path, data) => { },
            delete: async (path) => { },
            listAll: async () => [],
        };
    }
    const dbName = 'nge-vstore';
    return new Promise((resolve, reject) => {
        const request = globalObj.indexedDB.open(dbName, 1);

        request.onupgradeneeded = (event) => {
            const db = event.target.result;
            if (!db.objectStoreNames.contains(name)) {
                db.createObjectStore(name);
            }
        };

        request.onerror = (event) => {
            console.error('Error opening IndexedDB:', event.target.error);
            // Provide fallback implementation when DB can't be opened
            resolve({
                exists: async (path) => false,
                read: async (path) => null,
                write: async (path, data) => { },
                delete: async (path) => { },
                listAll: async () => [],
            });
        };

        request.onsuccess = (event) => {
            const db = event.target.result;

            // Create API object
            const vfileStore = {
                close() {
                    db.close();
                },
                async exists(path) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction([name], 'readonly');
                        const store = transaction.objectStore(name);
                        const request = store.count(path);

                        request.onsuccess = () => {
                            resolve(request.result > 0);
                        };

                        request.onerror = (event) => {
                            console.error('Error checking file existence:', event.target.error);
                            resolve(false);
                        };
                    });
                },

                async read(path) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction([name], 'readonly');
                        const store = transaction.objectStore(name);
                        const request = store.get(path);

                        request.onsuccess = () => {
                            resolve(request.result);
                        };

                        request.onerror = (event) => {
                            console.error('Error reading file:', event.target.error);
                            resolve(null);
                        };
                    });
                },

                async write(path, data) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction([name], 'readwrite');
                        const store = transaction.objectStore(name);
                        const request = store.put(data, path);

                        request.onsuccess = () => {
                            resolve();
                        };

                        request.onerror = (event) => {
                            console.error('Error writing file:', event.target.error);
                            resolve();  // Still resolve to avoid breaking the app
                        };
                    });
                },

                async delete(path) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction([name], 'readwrite');
                        const store = transaction.objectStore(name);
                        const request = store.delete(path);

                        request.onsuccess = () => {
                            resolve();
                        };

                        request.onerror = (event) => {
                            console.error('Error deleting file:', event.target.error);
                            resolve();  // Still resolve to avoid breaking the app
                        };
                    });
                },

                async listAll() {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction([name], 'readonly');
                        const store = transaction.objectStore(name);
                        const request = store.getAllKeys();

                        request.onsuccess = () => {
                            resolve(Array.from(request.result || []));
                        };

                        request.onerror = (event) => {
                            console.error('Error listing files:', event.target.error);
                            resolve([]);
                        };
                    });
                }
            };

            resolve(vfileStore);
        };
    });
}

export const vfileExists = async (name, path) => { // boolean
    const vstore = await getVFileStore(name);
    const v = await vstore.exists(path);
    vstore.close();
    return v;
}

export const vfileRead = async (name, path) => { // byte[]
    const vstore = await getVFileStore(name);
    const v  = await vstore.read(path);
    if (v === null || v === undefined) {
        console.warn(`File not found: ${path} in store ${name}`);
        vstore.close();
        return null;
    }
    console.log(`File read: ${path} in store ${name}`);
    vstore.close();
    return _u(v);
}

export const vfileWrite = async (name, path, data) => { // void
    const vstore = await getVFileStore(name);
    await vstore.write(path, _u(data));
    vstore.close();
    console.log(`File written: ${path} in store ${name}`);
}

export const vfileDelete = async (name, path) => { // void
    const vstore = await getVFileStore(name);
    await vstore.delete(path);
    vstore.close();
    console.log(`File deleted: ${path} in store ${name}`);    
}   

export const vfileListAll = async (name) => { // str[]
    const vstore = await getVFileStore(name);
    const files = await vstore.listAll();
    if (files === undefined || files === null) {
        console.warn(`No files found in store ${name}`);
        vstore.close();
        return [];
    }
    const v = files.map(file => file.toString());
    vstore.close();
    return v;
}

export const vfileExistsAsync = (name, path, res, rej) => { // void
    vfileExists(name, path)
        .then(result => res(result))
        .catch(error => {
            console.error(`Error checking file existence: ${error}`);
            rej(error);
        }
    );
}

const vfileReadAsync = (name, path, res, rej) => { // void
    vfileRead(name, path)
        .then(result => res(result))
        .catch(error => {
            console.error(`Error reading file: ${error}`);
            rej(error);
        }
    );
}

export const vfileWriteAsync = (name, path, data, res, rej) => { // void
    vfileWrite(name, path, data)
        .then(() => res())
        .catch(error => {
            console.error(`Error writing file: ${error}`);
            rej(error);
        }
    );
}

export const vfileDeleteAsync = (name, path, res, rej) => { // void
    vfileDelete(name, path)
        .then(() => res())  
        .catch(error => {
            console.error(`Error deleting file: ${error}`);
            rej(error);
        }
    );
}

export const vfileListAllAsync = (name, res, rej) => { // str[]
    vfileListAll(name)

        .then(result => res(result))
        .catch(error => {
            console.error(`Error listing files: ${error}`);
            rej(error);
        }
    );
}

export const getPlatformName = () => { // str
    const pl =  'JavaScript (' + (typeof window !== 'undefined' ? 'browser' : 'runtime') + ')';
    return pl;
}


function toFunction(f) { // Function
    const namespace = f.split('.');
    let obj = null;
    let fun = null;

    // Get the root object
    if (namespace[0] === 'window' || namespace[0] === 'globalThis' || namespace[0] === 'self') {
        obj = (typeof window !== 'undefined' && window) ||
            (typeof globalThis !== 'undefined' && globalThis) ||
            (typeof self !== 'undefined' && self);
    } else {
        const globalObj = (typeof window !== 'undefined' && window) ||
            (typeof globalThis !== 'undefined' && globalThis) ||
            (typeof self !== 'undefined' && self);
        obj = globalObj[namespace[0]];
    }

    if (!obj) {
        throw new Error(`Root object ${namespace[0]} is not defined`);
    }

    // Navigate to the parent object and function
    for (let i = 1; i < namespace.length - 1; i++) {
        if (!obj) {
            throw new Error(`Object ${namespace.slice(0, i + 1).join('.')} is not defined`);
        }
        obj = obj[namespace[i]];
    }

    // Get the final function
    const functionName = namespace[namespace.length - 1];
    fun = obj[functionName];

    if (!fun) {
        throw new Error(`Function ${functionName} is not defined`);
    }

    if (typeof fun !== 'function') {
        throw new Error(`${functionName} is not a function`);
    }


    // Return a bound function to preserve the 'this' context
    return fun.bind(obj);
}
export const callFunction = async (functionName, data, res, rej) => { // void
    try {
        const result = await toFunction(functionName)(...(JSON.parse(data).args));
        res(JSON.stringify({ result: result }));
    } catch (error) {
        console.error(`Error executing function ${functionName}:`, error);
        rej(error);
    }
};

export const canCallFunction = async (functionName, res) => { // void
    try {
        const canCall = !!toFunction(functionName);
        if (canCall){
            console.log(`Function ${functionName} can be called:`, canCall);
            res(true);
        } else {
            console.warn(`Function ${functionName} cannot be called.`);
            res(false);
        }
    } catch (error) {
        console.error(`Error checking function ${functionName}:`, error);
        res(false);
    }
};
