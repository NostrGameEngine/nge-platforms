import * as B from '/org/ngengine/platform/teavm/TeaVMBinds.bundle.js';

const resultEl = document.getElementById('result');
const url = new URL(window.location.href);
const signalBase = url.searchParams.get('signalBase');
const httpParityUrl = url.searchParams.get('httpParityUrl');

const V = {
  privA: '1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020101',
  privB: '202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f',
  hmacKey: '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
  data1: 'cafebabe00112233deadbeef',
  data2: '0102030405060708090a',
  hkdfSalt: 'f0e0d0c0b0a090807060504030201000112233445566778899aabbccddeeff00',
  hkdfIkm: '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff',
  hkdfInfo: 'nge-platform-parity-info',
  b64Data: '00010203f0f1f2f37f80ff',
  chachaKey: '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
  chachaNonce: '000000000000004a00000000',
  chachaData: 'parity-chacha-message',
  shaString: 'nge-platform parity string',
  shaBytes: '11223344556677889900aabbccddeeff',
  signDataHex: '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff',
};

function setResult(obj) { resultEl.textContent = JSON.stringify(obj, null, 2); }
function enc(s) { return new TextEncoder().encode(s); }
function hexToBytes(h) {
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16);
  return out;
}
function bytesToHex(b) { return Array.from(b).map(x => x.toString(16).padStart(2, '0')).join(''); }
function allZero(b) { return Array.from(b).every(x => x === 0); }
async function post(path, body) {
  const res = await fetch(`${signalBase}${path}`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)});
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${await res.text()}`);
}

async function main() {
  if (!signalBase) throw new Error('Missing signalBase');
  if (!httpParityUrl) throw new Error('Missing httpParityUrl');

  const privA = hexToBytes(V.privA);
  const privB = hexToBytes(V.privB);
  const pubA = B.genPubKey(privA);
  const pubB = B.genPubKey(privB);
  const hmac = B.hmac(hexToBytes(V.hmacKey), hexToBytes(V.data1), hexToBytes(V.data2));
  const prk = B.hkdf_extract(hexToBytes(V.hkdfSalt), hexToBytes(V.hkdfIkm));
  const okm = B.hkdf_expand(prk, enc(V.hkdfInfo), 42);
  const b64 = B.base64encode(hexToBytes(V.b64Data));
  const b64rt = B.base64decode(b64);
  const chachaEnc = B.chacha20(hexToBytes(V.chachaKey), hexToBytes(V.chachaNonce), enc(V.chachaData));
  const chachaDec = B.chacha20(hexToBytes(V.chachaKey), hexToBytes(V.chachaNonce), chachaEnc);
  const shaStr = bytesToHex(B.sha256(enc(V.shaString)));
  const shaBytes = bytesToHex(B.sha256(hexToBytes(V.shaBytes)));

  const jsonMap = B.toJSON({ a: 1, b: true, c: null, d: ['x', 'y'] });
  const jsonList = B.toJSON([1, 'two', null, true]);
  const parsed = B.fromJSON('{"x":5,"y":[1,2],"z":{"k":"v"}}');

  const rnd = B.randomBytes(16);
  const genPriv = B.generatePrivateKey();
  const sig = B.sign(hexToBytes(V.signDataHex), privA);
  const verifyOwn = B.verify(hexToBytes(V.signDataHex), pubA, sig);
  const bad = hexToBytes('ff' + V.signDataHex.slice(2));
  const verifyWrong = B.verify(bad, pubA, sig);
  const httpRes = await fetch(httpParityUrl, {
    method: 'POST',
    headers: { 'X-Parity-Req': 'parity', 'Content-Type': 'text/plain; charset=utf-8' },
    body: 'parity-http-body',
  });
  const httpBody = await httpRes.text();
  const httpReplyHeader = httpRes.headers.get('x-parity-reply') || '';

  const result = {
    ok: true,
    pubA: bytesToHex(pubA),
    pubB: bytesToHex(pubB),
    hmac: bytesToHex(hmac),
    hkdfExtract: bytesToHex(prk),
    hkdfExpand: bytesToHex(okm),
    base64: b64,
    base64Roundtrip: bytesToHex(b64rt),
    chachaEnc: bytesToHex(chachaEnc),
    chachaDec: bytesToHex(chachaDec),
    sha256String: shaStr,
    sha256Bytes: shaBytes,
    jsonMap,
    jsonList,
    fromJson_x: String(parsed.x),
    fromJson_y_len: String(parsed.y.length),
    fromJson_z_k: String(parsed.z.k),
    signatureLen: bytesToHex(sig).length,
    signatureAsyncLen: bytesToHex(sig).length,
    verifyOwn,
    verifyWrong,
    verifyAsync: verifyOwn,
    randomLen: rnd.length,
    randomNonZero: !allZero(rnd),
    generatedPrivateKeyLen: genPriv.length,
    generatedPrivateKeyNonZero: !allZero(genPriv),
    httpRequest_status: httpRes.ok,
    httpRequest_statusCode: httpRes.status,
    httpRequest_body: httpBody,
    httpRequest_replyHeader: httpReplyHeader,
  };

  setResult(result);
  await post('/result/browser', result);
}

main().catch(async (e) => {
  const err = { ok: false, error: String(e?.stack || e) };
  setResult(err);
  try { if (signalBase) await post('/result/browser', err); } catch {}
});
