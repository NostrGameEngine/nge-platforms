import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import puppeteer from 'puppeteer-core';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const teavmDir = path.resolve(repoRoot, 'nge-platform-teavm');
const rootDirs = [path.join(projectDir, 'test-harness'), path.join(teavmDir, 'src', 'main', 'resources')];

const state = { results: { jvm: null, android: null, browser: null } };

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}
function mimeType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js') || file.endsWith('.mjs')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.json')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}
function resolvePath(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]).replace(/^\/+/, '');
  for (const base of rootDirs) {
    const normalized = clean.startsWith('test-harness/') ? clean.replace(/^test-harness\//, '') : clean;
    const candidate = path.resolve(base, normalized);
    if (candidate.startsWith(base) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  }
  return null;
}
async function readBody(req) { const chunks = []; for await (const c of req) chunks.push(c); return Buffer.concat(chunks).toString('utf8'); }

function makeServer() {
  return http.createServer(async (req, res) => {
    try {
      const method = req.method || 'GET';
      const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');
      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (!['jvm', 'android', 'browser'].includes(peer)) return json(res, 400, { error: 'invalid peer' });
        state.results[peer] = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
      }
      if (reqUrl.pathname === '/parity-http') {
        if (method !== 'POST') return json(res, 405, { error: 'method not allowed' });
        const body = await readBody(req);
        const reqHdr = req.headers['x-parity-req'] || '';
        res.writeHead(201, {
          'Content-Type': 'text/plain; charset=utf-8',
          'X-Parity-Reply': 'ok',
          'Cache-Control': 'no-store'
        });
        res.end(`echo:${body}|req:${reqHdr}`);
        return;
      }
      if (method === 'GET' && reqUrl.pathname === '/signal/results') return json(res, 200, state.results);
      const file = resolvePath(reqUrl.pathname === '/' ? '/test-harness/platform-parity.html' : reqUrl.pathname);
      if (!file) return json(res, 404, { error: `Not found: ${reqUrl.pathname}` });
      res.writeHead(200, { 'Content-Type': mimeType(file) });
      fs.createReadStream(file).pipe(res);
    } catch (err) {
      json(res, 500, { error: String(err?.stack || err) });
    }
  });
}

function createLinePrefixWriter(target, prefix) {
  let buffer = '';
  return {
    push(chunk) {
      buffer += chunk;
      while (true) {
        const nl = buffer.indexOf('\n');
        if (nl < 0) break;
        target.write(`${prefix}${buffer.slice(0, nl + 1)}`);
        buffer = buffer.slice(nl + 1);
      }
    },
    flush() {
      if (buffer.length) target.write(`${prefix}${buffer}\n`);
      buffer = '';
    },
  };
}

function spawnCapture(cmd, args, { cwd, env, label } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, env: { ...process.env, ...(env || {}) }, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    const outWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stdout] `) : null;
    const errWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stderr] `) : null;
    child.stdout.on('data', (d) => { const s = d.toString(); stdout += s; outWriter?.push(s); });
    child.stderr.on('data', (d) => { const s = d.toString(); stderr += s; errWriter?.push(s); });
    child.on('error', reject);
    child.on('exit', (code) => { outWriter?.flush(); errWriter?.flush(); resolve({ code: code ?? -1, stdout, stderr }); });
  });
}

async function runBrowser(url) {
  const chrome = process.env.CHROME_BIN || '/usr/bin/google-chrome';
  const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  const page = await browser.newPage();
  const consoleMessages = [];
  const pageErrors = [];
  page.on('console', (msg) => consoleMessages.push(`[${msg.type()}] ${msg.text()}`));
  page.on('pageerror', (err) => pageErrors.push(err.stack || err.message || String(err)));
  page.on('requestfailed', (req) => consoleMessages.push(`[requestfailed] ${req.url()} ${req.failure()?.errorText || 'unknown'}`));
  try {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 20000 });
    await page.waitForFunction(() => {
      const el = document.getElementById('result');
      if (!el) return false;
      const t = (el.textContent || '').trim();
      if (!t || t === 'RUNNING') return false;
      try { const p = JSON.parse(t); return typeof p.ok === 'boolean'; } catch { return false; }
    }, { timeout: 30000 });
    const raw = await page.$eval('#result', (el) => el.textContent || '');
    return { result: JSON.parse(raw), consoleMessages, pageErrors };
  } finally {
    await browser.close();
  }
}

function compareSnapshots(results) {
  const exactKeys = [
    'pubA','pubB','hmac','hkdfExtract','hkdfExpand','base64','base64Roundtrip',
    'chachaEnc','chachaDec','sha256String','sha256Bytes','jsonMap','jsonList','fromJson_x','fromJson_y_len','fromJson_z_k',
    'httpRequest_status','httpRequest_statusCode','httpRequest_body','httpRequest_replyHeader'
  ];
  const invariantKeys = [
    'signatureLen','signatureAsyncLen','verifyOwn','verifyWrong','verifyAsync','randomLen','randomNonZero','generatedPrivateKeyLen','generatedPrivateKeyNonZero'
  ];
  const peers = ['jvm', 'android', 'browser'];
  const mismatches = [];
  for (const peer of peers) {
    if (!results[peer] || !results[peer].ok) mismatches.push(`missing/failed result for ${peer}`);
  }
  if (mismatches.length) return { ok: false, mismatches };
  for (const key of exactKeys) {
    const vals = peers.map((p) => JSON.stringify(results[p][key]));
    if (!(vals[0] === vals[1] && vals[1] === vals[2])) mismatches.push(`exact mismatch ${key}: ${vals.join(' | ')}`);
  }
  for (const key of ['signatureLen', 'signatureAsyncLen']) {
    for (const p of peers) if (Number(results[p][key]) !== 128) mismatches.push(`${p}.${key} != 128`);
  }
  for (const key of ['verifyOwn', 'verifyAsync']) {
    for (const p of peers) if (Boolean(results[p][key]) !== true) mismatches.push(`${p}.${key} != true`);
  }
  for (const key of ['verifyWrong']) {
    for (const p of peers) if (Boolean(results[p][key]) !== false) mismatches.push(`${p}.${key} != false`);
  }
  for (const p of peers) {
    if (Number(results[p].randomLen) !== 16) mismatches.push(`${p}.randomLen != 16`);
    if (Boolean(results[p].randomNonZero) !== true) mismatches.push(`${p}.randomNonZero != true`);
    if (Number(results[p].generatedPrivateKeyLen) !== 32) mismatches.push(`${p}.generatedPrivateKeyLen != 32`);
    if (Boolean(results[p].generatedPrivateKeyNonZero) !== true) mismatches.push(`${p}.generatedPrivateKeyNonZero != true`);
  }
  return { ok: mismatches.length === 0, mismatches, checkedExactKeys: exactKeys, checkedInvariantKeys: invariantKeys };
}

async function main() {
  const bundlePath = path.join(teavmDir, 'src', 'main', 'resources', 'org', 'ngengine', 'platform', 'teavm', 'TeaVMBinds.bundle.js');
  if (!fs.existsSync(bundlePath)) throw new Error(`Missing TeaVM bundle at ${bundlePath}`);
  const server = makeServer();
  await new Promise((resolve) => server.listen(0, '0.0.0.0', resolve));
  const addr = server.address();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const browserSignalBase = `http://127.0.0.1:${port}/signal`;
  const androidSignalBase = `http://10.0.2.2:${port}/signal`;
  const jvmSignalBase = `http://127.0.0.1:${port}/signal`;
  const browserHttpParityUrl = `http://127.0.0.1:${port}/parity-http`;
  const androidHttpParityUrl = `http://10.0.2.2:${port}/parity-http`;
  const jvmHttpParityUrl = `http://127.0.0.1:${port}/parity-http`;
  const pageUrl = `http://127.0.0.1:${port}/test-harness/platform-parity.html?signalBase=${encodeURIComponent(browserSignalBase)}&httpParityUrl=${encodeURIComponent(browserHttpParityUrl)}`;

  const jvmPromise = spawnCapture('./gradlew', [':nge-platform-interop-test:jvm:jvmPlatformParityJvmSide', `-PinteropSignalBase=${jvmSignalBase}`, `-PinteropHttpParityUrl=${jvmHttpParityUrl}`, '--console=plain'], { cwd: repoRoot, label: 'jvm' });
  const androidPromise = spawnCapture('./gradlew', [':nge-platform-interop-test:android:androidRtcEmulatorHarness', '--console=plain'], {
    cwd: repoRoot,
    label: 'android',
    env: {
      ANDROID_RTC_TEST_FILTER: 'org.ngengine.platform.android.AndroidPlatformParityInstrumentedTest',
      ANDROID_RTC_SIGNAL_BASE: androidSignalBase,
      ANDROID_RTC_HTTP_PARITY_URL: androidHttpParityUrl,
      ANDROID_AVD_NAME: process.env.ANDROID_AVD_NAME || 'Generic_AOSP',
    },
  });

  let browserOut = null;
  let browserErr = null;
  try { browserOut = await runBrowser(pageUrl); } catch (e) { browserErr = e; }
  const [jvmOut, androidOut] = await Promise.all([jvmPromise, androidPromise]);
  server.close();

  if (browserOut?.consoleMessages?.length) process.stderr.write(`${browserOut.consoleMessages.join('\n')}\n`);
  if (browserOut?.pageErrors?.length) process.stderr.write(`${browserOut.pageErrors.join('\n')}\n`);
  if (browserErr) throw browserErr;

  const compare = compareSnapshots(state.results);
  const combined = {
    ok: compare.ok && jvmOut.code === 0 && androidOut.code === 0 && Boolean(browserOut?.result?.ok),
    compare,
    results: state.results,
    browser: browserOut?.result ?? null,
    jvmExitCode: jvmOut.code,
    androidExitCode: androidOut.code,
    exclusions: [
      'Transport constructors/RTC/Websocket methods',
      'Clipboard/browser/store methods',
      'Executor/promise scheduling semantics (only signAsync/verifyAsync parity is sampled)',
      'Timestamp methods',
      'Exact random outputs and exact signature bytes (invariants/semantics checked instead)'
    ]
  };
  process.stdout.write(`${JSON.stringify(combined, null, 2)}\n`);
  if (!combined.ok) process.exit(1);
}

main().catch((err) => {
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
