const assert = require('node:assert/strict');
const { createHash, webcrypto } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

async function check(nativeCrypto) {
    let yields = 0;
    const context = vm.createContext({
        TextEncoder, Uint8Array, ArrayBuffer, DataView, Buffer, console,
        crypto: nativeCrypto ? webcrypto : undefined,
        setTimeout(callback, delay) { yields++; return setTimeout(callback, delay); }
    });
    const source = fs.readFileSync(path.resolve(__dirname, '../main/resources/org/ngengine/platform/teavm/TeaVMBinds.bundle.js'), 'utf8');
    const module = new vm.SourceTextModule(source, { context });
    await module.link(() => { throw new Error('Unexpected external import'); });
    await module.evaluate();
    const bind = module.namespace;
    const strings = ['', '日本語 😀 <>&', '\ud800\ud800x\udc00', 'a'.repeat(2 * 1024 * 1024)];
    for (const value of strings) {
        const expected = createHash('sha256').update(value.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '?')).digest('hex');
        assert.equal(await bind.sha256StringAsync(value), expected);
        assert.equal(bind.sha256String(value), expected);
    }
    const payload = [0, 'f'.repeat(64), 1700000000, 3,
        Array.from({ length: 6000 }, (_, i) => ['p', String(i), '', '日本語 😀']), '\ud800\ntext'];
    const expected = createHash('sha256').update(JSON.stringify(payload)).digest('hex');
    assert.equal(await bind.sha256JSONAsync(payload), expected);
    assert.equal(bind.sha256JSON(payload), expected);
    assert.equal(yields > 0, !nativeCrypto, 'The insecure-context fallback must yield between large chunks');
    console.log(`${nativeCrypto ? 'WebCrypto' : 'cooperative fallback'}: UTF-8 and large contact lists match synchronous SHA-256`);
}

check(true).then(() => check(false)).catch(error => { console.error(error); process.exitCode = 1; });
