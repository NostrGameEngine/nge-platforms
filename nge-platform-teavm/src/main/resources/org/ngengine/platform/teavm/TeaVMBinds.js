import { chacha20 as _chacha20 } from '@noble/ciphers/chacha.js';
import { schnorr as _schnorr, secp256k1 as _secp256k1 } from '@noble/curves/secp256k1';
import { hmac as _hmac } from '@noble/hashes/hmac.js';
import { sha256 as _sha256 } from '@noble/hashes/sha2.js';
import { extract as _hkdf_extract, expand as _hkdf_expand } from '@noble/hashes/hkdf'
import { base64 as _base64 } from '@scure/base';
import { cbc } from '@noble/ciphers/aes';
import {  randomBytes as _randomBytes } from '@noble/hashes/utils.js';
import { scryptAsync as _scryptAsync } from '@noble/hashes/scrypt'
import { xchacha20poly1305 as _xchacha20poly1305 } from '@noble/ciphers/chacha'

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
        console.trace();
        throw new TypeError('Unsupported data type for conversion to Uint8Array '+typeof data);
    }
};


// wrap Uint8Array in an object 
export const _bw = (data)=>{
    return { data };
}

function _s() {
    return ((typeof window !== 'undefined' && window) ||
        (typeof globalThis !== 'undefined' && globalThis) ||
        (typeof global !== 'undefined' && global) ||
        (typeof self !== 'undefined' && self));
}

function _isNodeRuntime() {
    return typeof process !== 'undefined' &&
        process !== null &&
        typeof process.versions !== 'undefined' &&
        process.versions !== null &&
        typeof process.versions.node === 'string';
}

function _getNodeVersion() {
    return _isNodeRuntime() ? process.versions.node : null;
}

function _hasIndexedDB() {
    return !!_s()?.indexedDB;
}

const _dynamicImport = (specifier) => {
    try {
        return Function('s', 'return import(s)')(specifier);
    } catch (error) {
        return Promise.reject(error);
    }
};

function _getNodeVStoreSafeName(name) {
    if (name === null || name === undefined) {
        throw new Error('VStore name is required.');
    }
    const s = String(name);
    if (!s) {
        throw new Error('VStore name is required.');
    }
    return encodeURIComponent(s);
}

function _pathStartsWith(pathModule, candidatePath, basePath) {
    const candidate = pathModule.resolve(candidatePath);
    const base = pathModule.resolve(basePath);

    if (candidate === base) {
        return true;
    }

    if (process.platform === 'win32') {
        const c = candidate.toLowerCase();
        const b = base.toLowerCase();
        return c.startsWith(b + pathModule.sep);
    }

    return candidate.startsWith(base + pathModule.sep);
}

function _resolveNodeVStorePath(pathModule, basePath, userPath) {
    if (userPath === null || userPath === undefined || String(userPath).length === 0) {
        throw new Error('Path required');
    }

    const p = String(userPath);
    if (pathModule.isAbsolute(p)) {
        throw new Error('Absolute paths not allowed');
    }

    const candidate = pathModule.resolve(basePath, p);
    if (!_pathStartsWith(pathModule, candidate, basePath)) {
        throw new Error('Traversal detected');
    }
    return candidate;
}

const _getNodeDataPath = async () => {
    if (!_isNodeRuntime()) {
        return null;
    }

    const s = _s();
    if (s._ngeNodeDataPath) {
        return s._ngeNodeDataPath;
    }

    const osModule = await _dynamicImport('node:os');
    const pathModule = await _dynamicImport('node:path');
    const env = process.env || {};

    let basePath = env.APP_DATA_DIR;
    if (!basePath) {
        if (process.platform === 'win32') {
            basePath = env.APPDATA || pathModule.join(osModule.homedir(), 'AppData', 'Roaming');
        } else if (process.platform === 'darwin') {
            basePath = pathModule.join(osModule.homedir(), 'Library', 'Application Support');
        } else {
            basePath = env.XDG_DATA_HOME || pathModule.join(osModule.homedir(), '.local', 'share');
        }
    }

    s._ngeNodeDataPath = pathModule.resolve(basePath);
    return s._ngeNodeDataPath;
};

const _getNodeFSVFileStore = async (name) => {
    if (!_isNodeRuntime()) {
        return null;
    }

    const s = _s();
    if (typeof s._ngeNodeFSVFileStores === 'undefined') {
        s._ngeNodeFSVFileStores = {};
    }
    if (s._ngeNodeFSVFileStores[name]) {
        return s._ngeNodeFSVFileStores[name];
    }

    const fsModule = await _dynamicImport('node:fs/promises');
    const pathModule = await _dynamicImport('node:path');
    const baseDataPath = await _getNodeDataPath();
    const safeName = _getNodeVStoreSafeName(name);
    const basePath = pathModule.resolve(baseDataPath, safeName);

    const store = {
        close() { },
        async exists(path) {
            try {
                const fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                await fsModule.access(fullPath);
                return true;
            } catch (_error) {
                return false;
            }
        },
        async read(path) {
            try {
                const fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                const bytes = await fsModule.readFile(fullPath);
                return new Uint8Array(bytes);
            } catch (_error) {
                return null;
            }
        },
        async write(path, data) {
            const fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
            const parent = pathModule.dirname(fullPath);
            await fsModule.mkdir(parent, { recursive: true });
            const tmpPath = pathModule.join(
                parent,
                `.vstore-${Date.now()}-${Math.random().toString(16).slice(2)}.tmp`
            );
            const payload = _u(data);
            await fsModule.writeFile(tmpPath, payload);
            try {
                await fsModule.rename(tmpPath, fullPath);
            } catch (error) {
                try {
                    await fsModule.unlink(tmpPath);
                } catch (_cleanupError) { }
                throw error;
            }
        },
        async delete(path) {
            try {
                const fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                await fsModule.unlink(fullPath);
            } catch (error) {
                if (error && error.code === 'ENOENT') {
                    return;
                }
                throw error;
            }
        },
        async listAll() {
            const out = [];
            try {
                await fsModule.mkdir(basePath, { recursive: true });
            } catch (_mkdirError) { }

            const walk = async (dir) => {
                const entries = await fsModule.readdir(dir, { withFileTypes: true });
                for (const entry of entries) {
                    const full = pathModule.join(dir, entry.name);
                    if (entry.isDirectory()) {
                        await walk(full);
                    } else if (entry.isFile()) {
                        const rel = pathModule.relative(basePath, full);
                        out.push(rel);
                    }
                }
            };

            try {
                await walk(basePath);
            } catch (error) {
                if (error && error.code === 'ENOENT') {
                    return [];
                }
                throw error;
            }
            return out;
        }
    };

    s._ngeNodeFSVFileStores[name] = store;
    return store;
};

function _getMemoryVFileStore(name) {
    const s = _s();
    if (typeof s._ngeMemoryVFileStores === 'undefined') {
        s._ngeMemoryVFileStores = {};
    }
    if (!s._ngeMemoryVFileStores[name]) {
        s._ngeMemoryVFileStores[name] = new Map();
    }
    const store = s._ngeMemoryVFileStores[name];
    return {
        close() { },
        async exists(path) {
            return store.has(path);
        },
        async read(path) {
            if (!store.has(path)) {
                return null;
            }
            const data = store.get(path);
            return data ? new Uint8Array(data) : null;
        },
        async write(path, data) {
            store.set(path, new Uint8Array(_u(data)));
        },
        async delete(path) {
            store.delete(path);
        },
        async listAll() {
            return Array.from(store.keys());
        }
    };
}

function _getRTCPeerConnectionCtor() {
    const ctor = _s()?.RTCPeerConnection;
    if (typeof ctor === 'function') {
        return ctor;
    }
    if (_isNodeRuntime()) {
        throw new Error(
            'RTCPeerConnection is not available in Node.js. Provide a node-webrtc-compatible implementation in your app and assign it to globalThis.RTCPeerConnection before using TeaVM RTC.'
        );
    }
    throw new Error('RTCPeerConnection is not available in this runtime.');
}

function _getRTCIceCandidateCtor() {
    const ctor = _s()?.RTCIceCandidate;
    if (typeof ctor === 'function') {
        return ctor;
    }
    if (_isNodeRuntime()) {
        throw new Error(
            'RTCIceCandidate is not available in Node.js. Provide a node-webrtc-compatible implementation in your app and assign it to globalThis.RTCIceCandidate before using TeaVM RTC.'
        );
    }
    throw new Error('RTCIceCandidate is not available in this runtime.');
}

const _getNodeClipboard = async () => {
    if (!_isNodeRuntime()) {
        return null;
    }

    const s = _s();
    if (s._ngeNodeClipboard) {
        return s._ngeNodeClipboard;
    }

    s._ngeNodeClipboard = {
        async readText() {
            return '';
        },
        async writeText(text) {
            return;
        }
    };
    return s._ngeNodeClipboard;
};

const _getClipboard = async () => {
    const injectedClipboard = _s()?.ngeClipboard;
    if (injectedClipboard) {
        return injectedClipboard;
    }
    if (typeof navigator !== 'undefined' && navigator && navigator.clipboard) {
        return navigator.clipboard;
    }
    if (_isNodeRuntime()) {
        return _getNodeClipboard();
    }
    return null;
};
 
const sanitizeBigInts = (obj) => {
    // Base cases for non-objects
    if (obj === null || obj === undefined) {
        return obj;
    }

    // Convert BigInt to Number
    if (typeof obj === 'bigint') {
        return Number(obj);
    }

    // Handle arrays
    if (Array.isArray(obj)) {
        return obj.map(item => sanitizeBigInts(item));
    }

    // Handle {}
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
    return _u(_randomBytes(length));
};

export const generatePrivateKey = () => { // Uint8Array (byte[])
    return _u(_schnorr.utils.randomPrivateKey());
};

export const genPubKey = (secKey) => {// Uint8Array (byte[])
    return _u(_schnorr.getPublicKey(_u(secKey)));
};

export const sha256 = (data /*byte[]*/) => { // Uint8Array (byte[])
    return _u(_sha256(_u(data)));
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
    return _u(_schnorr.sign(_u(data), _u(privKeyBytes)));
};

export const verify = (data /*byte[]*/, pub /*byte[]*/, sig/*byte[]*/) => { // bool
    return _schnorr.verify(_u(sig), _u(data), _u(pub));
};


export const secp256k1SharedSecret = (privKey /*byte[]*/, pubKey /*byte[]*/) => { // Uint8Array (byte[])
    return _u(_secp256k1.getSharedSecret(_u(privKey), _u(pubKey)));
};

export const hmac = (key /*byte[]*/, data1 /*byte[]*/, data2 /*byte[]*/) => { // Uint8Array (byte[])
    const msg = new Uint8Array([..._u(data1), ..._u(data2)]);
    return _u(_hmac(_sha256, _u(key), msg));
};

export const hkdf_extract = (salt /*byte[]*/, ikm /*byte[]*/) => { // Uint8Array (byte[])
    return _u(_hkdf_extract(_sha256, _u(ikm), _u(salt)));
};

export const hkdf_expand = (prk/*byte[]*/, info/*byte[]*/, length/*int*/) => { // Uint8Array (byte[])
    return _u(_hkdf_expand(_sha256, _u(prk), _u(info), length));
};

export const base64encode = (data /*byte[]*/) => { //str
    return _base64.encode(_u(data));
};

export const base64decode = (data /*str*/) => { // Uint8Array (byte[])
    return _u(_base64.decode(data));
};

export const chacha20 = (key/*byte[]*/, nonce/*byte[]*/, data/*byte[]*/) => { // Uint8Array (byte[])
    return _u(_chacha20(_u(key), _u(nonce), _u(data)));
};

export const setTimeout = (callback, delay) => { //void
    return _s().setTimeout(callback, delay);
}

export const getClipboardContentAsync = (res,rej) => { //str
    _getClipboard()
        .then(clipboard => {
            if (!clipboard || typeof clipboard.readText !== 'function') {
                return '';
            }
            return clipboard.readText();
        })
        .then(text => {
            res(text ?? '');
        })
        .catch(err => {
            console.error('Failed to read clipboard contents: ', err);
            res('');
        });
}

export const setClipboardContent = (text) => { //void
    _getClipboard()
        .then(clipboard => {
            if (!clipboard || typeof clipboard.writeText !== 'function') {
                return;
            }
            return clipboard.writeText(text);
        })
        .catch((err) => {
            console.error('Failed to write to clipboard: ', err);
        });
}

export const hasBundledResource = (path) => { // boolean
    if (path.startsWith('/')) {
        path = path.substring(1);
    }
    const bundle = _s()?.NGEBundledResources;
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

    const bundle = _s()?.NGEBundledResources;

    if (!bundle) {
        console.warn('No bundled resources found. Ensure the bundler is configured correctly.');
        return null;
    }

    if (!bundle[path]) {
        console.warn('Resource not found in bundle:', path);
        return null;
    }

    return _u(base64decode(bundle[path]));

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
        return _u(forEncryption ? cipher.seal(data) : cipher.open(data));
    } catch (error) {
        console.error('AES-256-CBC operation failed:', error);
        throw error;
    }
};



async function getVFileStore(name) {
    const globalObj = _s();

    // Check if IndexedDB is available in the current environment
    if (!_hasIndexedDB()) {
        if (_isNodeRuntime()) {
            try {
                return await _getNodeFSVFileStore(name);
            } catch (error) {
                console.error('Node filesystem VStore unavailable, falling back to in-memory VStore:', error);
                return _getMemoryVFileStore(name);
            }
        }
        console.warn('IndexedDB is not supported in this environment. Falling back to an in-memory VStore.');
        return _getMemoryVFileStore(name);
    }
    return new Promise((resolve, reject) => {
        const request = globalObj.indexedDB.open('nge-vstore-'+name, 1);

        request.onupgradeneeded = (event) => {
            const db = event.target.result;
            if (!db.objectStoreNames.contains("files")) {
                db.createObjectStore("files");
            }
        };

        request.onerror = (event) => {
            console.error('Error opening IndexedDB:', event.target.error);
            if (_isNodeRuntime()) {
                _getNodeFSVFileStore(name)
                    .then(resolve)
                    .catch(error => {
                        console.error('Node filesystem VStore unavailable, falling back to in-memory VStore:', error);
                        resolve(_getMemoryVFileStore(name));
                    });
            } else {
                resolve(_getMemoryVFileStore(name));
            }
        };

        request.onsuccess = (event) => {
            const db = event.target.result;

            const vfileStore = {
                close() {
                    db.close();
                },
                async exists(path) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction(["files"], 'readonly');
                        const store = transaction.objectStore("files");
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
                        const transaction = db.transaction(["files"], 'readonly');
                        const store = transaction.objectStore("files");
                        const request = store.get(path);

                        request.onsuccess = () => {
                            resolve(_u(request.result));
                        };

                        request.onerror = (event) => {
                            console.error('Error reading file:', event.target.error);
                            resolve(null);
                        };
                    });
                },

                async write(path, data) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction(["files"], 'readwrite');
                        const store = transaction.objectStore("files");
                        const request = store.put(data, path);

                        request.onsuccess = () => {
                            resolve();
                        };

                        request.onerror = (event) => {
                            console.error('Error writing file:', event.target.error);
                            resolve();
                        };
                    });
                },

                async delete(path) {
                    return new Promise((resolve, reject) => {
                        const transaction = db.transaction(["files"], 'readwrite');
                        const store = transaction.objectStore("files");
                        const request = store.delete(path);

                        request.onsuccess = () => {
                            resolve();
                        };

                        request.onerror = (event) => {
                            console.error('Error deleting file:', event.target.error);
                            resolve();  
                        };
                    });
                },

                async listAll() {
                    return new Promise((resolve, reject) => {
                        try{
                            const transaction = db.transaction(["files"], 'readonly');
                            const store = transaction.objectStore("files");
                            const request = store.getAllKeys();

                            request.onsuccess = (event) => {
                                resolve(event.target.result||[]);
                            };

                            request.onerror = (event) => {
                                console.error('Error listing files:', event.target.error);
                                resolve([]);
                            };

                         
                        } catch (e) {
                            console.error('Error during listAll operation:', e);
                            resolve([]);
                        }
                    });
                }
            };

            resolve(vfileStore);
        };
    });
}

const vfileExists = async (name, path) => { // boolean
    const vstore = await getVFileStore(name);
    const v = await vstore.exists(path);
    vstore.close();
    return v;
}

const vfileRead = async (name, path) => { // byte[]
    const vstore = await getVFileStore(name);
    const v  = await vstore.read(path);
    if (v === null || v === undefined) {
        console.warn(`File not found: ${path} in store ${name}`);
        vstore.close();
        return null;
    }
    vstore.close();
    return _u(v);
}

const vfileWrite = async (name, path, data) => { // void
    const vstore = await getVFileStore(name);
    await vstore.write(path, _u(data));
    vstore.close();
}

const vfileDelete = async (name, path) => { // void
    const vstore = await getVFileStore(name);
    await vstore.delete(path);
    vstore.close();
}   

const vfileListAll = async (name) => { // str[]
    try{
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
    } catch (e) {
        console.error(`Error listing files in store ${name}:`, e);
        return [];
    }
}

export const vfileExistsAsync = (name, path, res, rej) => { // void
    vfileExists(name, path)
        .then(result => res(result))
        .catch(error => {
            console.error(`Error checking file existence: ${error}`);
            rej(String(error));
        }
    );
}

export const vfileReadAsync = (name, path, res, rej) => { // void
    vfileRead(name, path)
        .then(result => res(_bw(result)))
        .catch(error => {
            console.error(`Error reading file: ${error}`);
            rej(String(error));
        }
    );
}

export const vfileWriteAsync = (name, path, data, res, rej) => { // void
    vfileWrite(name, path, data)
        .then(() => res())
        .catch(error => {
            console.error(`Error writing file: ${error}`);
            rej(String(error));
        }
    );
}

export const vfileDeleteAsync = (name, path, res, rej) => { // void
    vfileDelete(name, path)
        .then(() => res())  
        .catch(error => {
            console.error(`Error deleting file: ${error}`);
            rej(String(error));
        }
    );
}

export const vfileListAllAsync = (name, res, rej) => { // str[]
    vfileListAll(name)

        .then(result => {
            res(result);
        })
        .catch(error => {
            console.error(`Error listing files: ${error}`);
            rej(String(error));
        }
    );
}

export const getPlatformName = () => { // str
    let runtime = "runtime";
    if (typeof Capacitor !== 'undefined' && Capacitor && Capacitor.getPlatform) {
        runtime = "capacitor " + Capacitor.getPlatform();
    } else if(typeof self !== "undefined" && self.origin && self.origin.startsWith("capacitor:")){
        runtime = "capacitor worker";
    } else if (_isNodeRuntime()) {
        runtime = "node " + _getNodeVersion();
    } else if (typeof window !== 'undefined') {
        runtime = "browser";
    }
    const pl =  'JavaScript (' + runtime + ')';
    return pl;
}


function toFunction(f) { // Function
    const namespace = f.split('.');
    let obj = null;
    let fun = null;

    // Get the root object
    if (namespace[0] === 'window' || namespace[0] === 'globalThis' || namespace[0] === 'self') {
        obj = _s();
    } else {
        const globalObj = _s();
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
        rej(String(error));
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


export const openURL = (url) => { // void
    try {
        const globalObj = _s();

        if (globalObj && globalObj.open) {
            globalObj.open(url, '_blank');
        } else {
            console.warn('Cannot open URL: No suitable global object found.');
        }
    } catch (error) {
        console.error('Error opening URL:', error);
    }
}   


export const nfkc = (str) => { // str
    if (str && str.normalize) {
        return str.normalize('NFKC');
    } else {
        console.warn('String normalization not supported in this environment.');
        return str;
    }
}


export const scryptAsync = (
    p, /*byte[]*/
    s,  /*byte[]*/
    n,  /*int*/
    r, /*int*/ 
    p2,  /*int*/
    dkLen, /*int*/
    res,
    rej
) => { // Uint8Array byte[]
    _scryptAsync(
        _u(p),
        _u(s),
        { N: n, r: r, p: p2, dkLen: dkLen })
        .then(result => {
            res(_bw(result));
        })
        .catch(error => {
            console.error(`Error in scryptAsync: ${error}`);
            rej(String(error));
        });
}


export const xchacha20poly1305 = (
    key, /*byte[]*/
    nonce, /*byte[]*/
    data,  /*byte[]*/
    associatedData, /*byte[]*/
    forEncryption /*bool*/
) => { // Uint8Array byte[]
    // let xc2p1 = xchacha20poly1305(key, nonce, aad)
    key = _u(key);
    nonce = _u(nonce);
    data = _u(data);
    associatedData = _u(associatedData);
    const cipher = _xchacha20poly1305(key, nonce, associatedData);

    if (forEncryption) {
        return _u(cipher.encrypt(data));
    } else {
        return _u(cipher.decrypt(data));
    }
}

export const registerFinalizer = (obj, callback) => { // void

    if (typeof FinalizationRegistry === 'undefined') {
        return ()=>{
            callback();
        }
    }

    const s = _s();

    if(typeof s._ngeTeaVMFinalizerMap=='undefined'){
        s._ngeTeaVMFinalizerMap = {};
    }

    if (typeof s._ngeTeaVMFinalizerMap_counter=='undefined'){
        s._ngeTeaVMFinalizerMap_counter = 1;
    }

    const id = "finalizer_" + (s._ngeTeaVMFinalizerMap_counter++);
    s._ngeTeaVMFinalizerMap[id] = callback;
    
    if(typeof s.ngeTeaVMFinalizationRegistry=='undefined'){
        s.ngeTeaVMFinalizationRegistry = new FinalizationRegistry((id) => {
            if (s._ngeTeaVMFinalizerMap[id]){
                try {
                    s._ngeTeaVMFinalizerMap[id]();
                } catch (e) {
                    console.error('Error in finalizer callback:', e);
                }
                delete s._ngeTeaVMFinalizerMap[id];
            }
        });
    }

    s.ngeTeaVMFinalizationRegistry.register(obj, id, obj);

    return () => {  
        if (s.ngeTeaVMFinalizationRegistry && id) {
            s.ngeTeaVMFinalizationRegistry.unregister(obj);
            if (s._ngeTeaVMFinalizerMap[id]){
                delete s._ngeTeaVMFinalizerMap[id];
            }
            callback();
        }
    }

}

export const rtcSetLocalDescriptionAsync = (conn /*RTCPeerConnection*/, sdp /*str*/, type /*str*/, res, rej) => { // void
    conn.setLocalDescription({ type: type, sdp: sdp })
        .then(() => res())
        .catch(error => {
            console.error('Error setting local description:', error);
            rej(String(error));
        });
}

export const rtcSetRemoteDescriptionAsync = (conn /*RTCPeerConnection*/, sdp /*str*/, type /*str*/, res, rej) => { // void
    conn.setRemoteDescription({ type: type, sdp: sdp })
        .then(() => res())
        .catch(error => {
            console.error('Error setting remote description:', error);
            rej(String(error));
        });
}   


export const rtcCreateAnswerAsync = (conn /*RTCPeerConnection*/, res, rej) => { // str
    conn.createAnswer()
        .then(answer => res(answer))
        .catch(error => {
            console.error('Error creating answer:', error);
            rej(String(error));
        });
}


export const rtcCreateOfferAsync = (conn /*RTCPeerConnection*/, res, rej) => { // str
    conn.createOffer()
        .then(offer => res(offer))
        .catch(error => {
            console.error('Error creating offer:', error);
            rej(String(error));
        });
}   


export const rtcCreatePeerConnection = (
    urls /*str[]*/
) => { // RTCPeerConnection
    const conf = {
        iceServers: urls.map(url => ({ urls: url }))  
    };
    const RTCPeerConnectionCtor = _getRTCPeerConnectionCtor();
    const conn = new RTCPeerConnectionCtor(conf);
    return conn;
}

export const rtcCreateDataChannel = (
    conn /*RTCPeerConnection*/,
    label /*str*/,
    protocol /*str*/,
    ordered /*bool*/,
    reliable /*bool*/,
    maxRetransmits /*int*/,
    maxPacketLifeTimeMs /*int*/
) => { // RTCDataChannel
    const options = {
        ordered: !!ordered
    };

    if (protocol !== null && protocol !== undefined) {
        options.protocol = String(protocol);
    }

    const hasMaxRetransmits = Number(maxRetransmits) >= 0;
    const hasMaxPacketLifeTime = Number(maxPacketLifeTimeMs) >= 0;

    if (hasMaxRetransmits) {
        options.maxRetransmits = Number(maxRetransmits);
    }
    if (hasMaxPacketLifeTime) {
        options.maxPacketLifeTime = Number(maxPacketLifeTimeMs);
    }

    // Browser WebRTC has no standalone "reliable=false" switch.
    if (!reliable && !hasMaxRetransmits && !hasMaxPacketLifeTime) {
        options.maxRetransmits = 0;
    }

    return conn.createDataChannel(label, options);
}

export const rtcCreateIceCandidate = (sdp /*str*/, spdMid /*str*/) => { // RTCIceCandidate
    const RTCIceCandidateCtor = _getRTCIceCandidateCtor();
    return new RTCIceCandidateCtor({
        candidate: sdp,
        sdpMid: spdMid,
        sdpMLineIndex: null
    });
}

export const rtcDataChannelGetProtocol = (channel) => {
    return channel?.protocol ?? '';
}

export const rtcDataChannelIsOrdered = (channel) => {
    return channel?.ordered !== false;
}

export const rtcDataChannelIsReliable = (channel) => {
    return channel?.maxRetransmits == null && channel?.maxPacketLifeTime == null;
}

export const rtcDataChannelGetMaxRetransmits = (channel) => {
    return channel?.maxRetransmits == null ? -1 : Number(channel.maxRetransmits);
}

export const rtcDataChannelGetMaxPacketLifeTime = (channel) => {
    return channel?.maxPacketLifeTime == null ? -1 : Number(channel.maxPacketLifeTime);
}

export const rtcGetMaxMessageSize = (conn) => {
    const v = conn?.sctp?.maxMessageSize;
    return Number.isFinite(v) ? Number(v) : -1;
}

export const rtcDataChannelGetBufferedAmount = (channel) => {
    const v = channel?.bufferedAmount;
    return Number.isFinite(v) ? Number(v) : 0;
}

export const rtcDataChannelGetAvailableAmount = (conn, channel) => {
    const max = rtcGetMaxMessageSize(conn);
    if (!Number.isFinite(max) || max < 0) {
        return -1;
    }
    return Math.max(0, Number(max) - rtcDataChannelGetBufferedAmount(channel));
}

export const rtcDataChannelSetBufferedAmountLowThreshold = (channel, threshold) => {
    channel.bufferedAmountLowThreshold = Number(threshold);
}

export const fetchAsync = (method, url, headers, body, timeoutMs, res, rej) => {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);


    const options = {
        method: method,
        headers: headers ? JSON.parse(headers) : {},
        signal: controller.signal,

    };

     if (body && method !== 'GET' && method !== 'HEAD') {
        options.body = _u(body);
    }

    fetch(url, options).then(async (response) => {
        clearTimeout(timeoutId);

        const respHeaders = {};
        response.headers.forEach((value, key) => {
            respHeaders[key] = value;
        });
        const respBody = new Uint8Array(await response.arrayBuffer());
        const out ={
            status: response.status,
            statusText: response.statusText,
            headers: JSON.stringify(respHeaders),
            body: new Uint8Array(respBody)
        };
        res(out);
    }).catch(error => {
        clearTimeout(timeoutId);

        rej(String(error));
    });
}


export const fetchStreamAsync = (method, url, headers, body, timeoutMs, res,rej) => {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);


    const options = {
        method: method,
        headers: headers ? JSON.parse(headers) : {},
        signal: controller.signal,

    };

    if (body && method !== 'GET' && method !== 'HEAD') {
        options.body = _u(body);
    }

    fetch(url, options).then(async (response) => {
        clearTimeout(timeoutId);

        const respHeaders = {};
        response.headers.forEach((value, key) => {
            respHeaders[key] = value;
        });
        const stream = response.body;
        const out = {
            status: response.status,
            statusText: response.statusText,
            headers: JSON.stringify(respHeaders),
            body: stream
        };
        res(out);
    }).catch(error => {
        clearTimeout(timeoutId);

        rej(String(error));
    });
}

const pendingPromises = {};
let promiseCounter = 1;

// setInterval(() => {
//     const pn = Object.entries(pendingPromises).filter(([id, p]) => !p.done).map(([id, p]) => id);
//     console.log(`Pending promises: ${pn.length}`);
//     for (const id of pn) {
//         const p = pendingPromises[id];
//         console.log(`  Promise (pending) ${id}: ${p.debug}`);
//     }
// }, 60000);

export const newPromise = ()=>{
    const debug = new Error().stack.split('\n').slice(1).join('\n');
    promiseCounter++;
    const id =   promiseCounter;
    let res, rej;
    const p = new Promise((resolve, reject) => {
        res = resolve;
        rej = reject;
    }).then(()=>{
    }).catch((e)=>{
    });
    pendingPromises[id] = { promise: p, resolve: res, reject: rej, debug, done: false };
    return id;
}

export const freePromise = (id) => {
    if (pendingPromises[id]) {
        delete pendingPromises[id];
    }
}

export const resolvePromise = (id) => {
    if (pendingPromises[id]) {
        const  p =pendingPromises[id];
        p.done = true;
        p.resolve();
    } else {
        console.warn(`Promise with id ${id} not found for resolution.`);
    }
}

export const rejectPromise = (id) => {
    if (pendingPromises[id]) {
        const p = pendingPromises[id];
        p.done = true;
        p.reject(new Error('Promise rejected'));
    } else {
        console.warn(`Promise with id ${id} not found for rejection.`);
    }
}

export const waitPromiseAsync = (id, res, rej) => {
    if (pendingPromises[id]) {
        pendingPromises[id].promise
            .then(() => res())
            .catch((error) => rej(String(error)));
    } else {
        console.warn(`Promise with id ${id} not found for waiting.`);
        rej(String(`Promise with id ${id} not found.`));
    }
}

export const rtcSetOnMessageHandler = (channel, callback) => { // void
    channel.onmessage = (event) => {
        const data = event.data;
        callback(_u(data));
    };
}

export const panic = (err) => { // void
    const message = 'PANIC: ' + err;
    if (_isNodeRuntime()) {
        try {
            if (process && process.stderr && typeof process.stderr.write === 'function') {
                process.stderr.write(message + '\n');
            } else {
                console.error(message);
            }
        } catch (stderrError) {
            console.error('Failed to write panic message to stderr:', stderrError);
        }
        if (process && typeof process.exit === 'function') {
            process.exit(1);
            return;
        }
        throw new Error(message);
    }
    console.error(message);
    if (typeof alert === 'function') {
        alert(message);
    }
    // try to forcefully kill the script
    if (typeof window !== 'undefined' && window.location) {
        window.location.reload();
    } else if (typeof self !== 'undefined' && self.close) {
        self.close();
    } else {
        throw new Error('PANIC: ' + err);
    }
}
