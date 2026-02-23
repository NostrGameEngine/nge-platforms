import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import puppeteer from 'puppeteer-core';
import { WebSocketServer } from 'ws';

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

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
}

function makeHttpServer() {
  return http.createServer(async (req, res) => {
    try {
      const u = new URL(req.url || '/', 'http://127.0.0.1');
      const method = req.method || 'GET';
      if (method === 'POST' && u.pathname.startsWith('/signal/result/')) {
        const peer = u.pathname.split('/').pop();
        if (!['jvm', 'android', 'browser'].includes(peer)) return json(res, 400, { error: 'invalid peer' });
        state.results[peer] = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
      }
      if (method === 'GET' && u.pathname === '/signal/results') return json(res, 200, state.results);
      if (method === 'GET' && u.pathname === '/favicon.ico') {
        res.writeHead(204);
        res.end();
        return;
      }

      const file = resolvePath(u.pathname === '/' ? '/test-harness/websocket-interop-parity.html' : u.pathname);
      if (!file) return json(res, 404, { error: `Not found: ${u.pathname}` });
      res.writeHead(200, { 'Content-Type': mimeType(file) });
      fs.createReadStream(file).pipe(res);
    } catch (e) {
      json(res, 500, { error: String(e?.stack || e) });
    }
  });
}

function attachWsServer(server) {
  const wss = new WebSocketServer({ server, path: '/ws' });
  wss.on('connection', (socket) => {
    socket.send('welcome');
    socket.on('message', (data) => {
      const msg = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
      if (msg.startsWith('echo:')) {
        socket.send(msg);
        return;
      }
      if (msg === 'close-by-server') {
        socket.close(1000, 'server-close');
        return;
      }
      socket.send(`unknown:${msg}`);
    });
  });
  return wss;
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
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
  });
  const page = await browser.newPage();
  const consoleMessages = [];
  const pageErrors = [];
  page.on('console', (m) => {
    const line = `[browser console:${m.type()}] ${m.text()}`;
    consoleMessages.push(line);
    process.stderr.write(`${line}\n`);
  });
  page.on('pageerror', (e) => {
    const line = e.stack || e.message || String(e);
    pageErrors.push(line);
    process.stderr.write(`[browser pageerror] ${line}\n`);
  });
  page.on('requestfailed', (req) => {
    const line = `[browser requestfailed] ${req.url()} ${req.failure()?.errorText || 'unknown'}`;
    consoleMessages.push(line);
    process.stderr.write(`${line}\n`);
  });
  try {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 20000 });
    await page.waitForFunction(() => {
      const el = document.getElementById('result');
      if (!el) return false;
      const t = (el.textContent || '').trim();
      if (!t || t === 'RUNNING') return false;
      try { const p = JSON.parse(t); return typeof p.ok === 'boolean'; } catch { return false; }
    }, { timeout: 60000 });
    const raw = await page.$eval('#result', (el) => el.textContent || '');
    return { result: JSON.parse(raw), consoleMessages, pageErrors };
  } finally {
    await browser.close();
  }
}

function compare(results) {
  const peers = ['jvm', 'android', 'browser'];
  const mismatches = [];
  for (const p of peers) {
    if (!results[p] || !results[p].ok) mismatches.push(`missing/failed result for ${p}`);
  }
  if (mismatches.length) return { ok: false, mismatches };

  const exactPaths = [
    'serverClosePhase.openCount',
    'serverClosePhase.connectedAfterOpen',
    'serverClosePhase.welcome',
    'serverClosePhase.echo',
    'serverClosePhase.serverCloseReason',
    'serverClosePhase.clientCloseCount',
    'serverClosePhase.connectedAfterServerClose',
    'serverClosePhase.error',
    'clientClosePhase.openCount',
    'clientClosePhase.connectedAfterOpen',
    'clientClosePhase.welcome',
    'clientClosePhase.echo',
    'clientClosePhase.clientCloseCount',
    'clientClosePhase.clientCloseReason',
    'clientClosePhase.connectedAfterClientClose',
    'clientClosePhase.error',
  ];
  const get = (obj, pathStr) => pathStr.split('.').reduce((cur, key) => (cur == null ? undefined : cur[key]), obj);
  for (const pathStr of exactPaths) {
    const vals = peers.map((p) => JSON.stringify(get(results[p], pathStr)));
    if (!(vals[0] === vals[1] && vals[1] === vals[2])) mismatches.push(`mismatch ${pathStr}: ${vals.join(' | ')}`);
  }

  // `serverCloseCount` during client-initiated close varies by backend implementation (some emit both client+server close callbacks).
  for (const p of peers) {
    const v = Number(get(results[p], 'clientClosePhase.serverCloseCount'));
    if (!Number.isFinite(v) || v < 0) mismatches.push(`${p}.clientClosePhase.serverCloseCount invalid: ${get(results[p], 'clientClosePhase.serverCloseCount')}`);
  }

  return { ok: mismatches.length === 0, mismatches, checkedExactPaths: exactPaths, excludedPaths: ['clientClosePhase.serverCloseCount'] };
}

async function main() {
  const server = makeHttpServer();
  const wss = attachWsServer(server);
  await new Promise((r) => server.listen(0, '0.0.0.0', r));
  const port = server.address().port;

  const signalBaseJvm = `http://127.0.0.1:${port}/signal`;
  const signalBaseAndroid = `http://10.0.2.2:${port}/signal`;
  const signalBaseBrowser = `http://127.0.0.1:${port}/signal`;

  const wsUrlJvm = `ws://127.0.0.1:${port}/ws`;
  const wsUrlBrowser = `ws://127.0.0.1:${port}/ws`;
  const wsUrlAndroid = `ws://10.0.2.2:${port}/ws`;

  const pageUrl = `http://127.0.0.1:${port}/test-harness/websocket-interop-parity.html?signalBase=${encodeURIComponent(signalBaseBrowser)}&wsUrl=${encodeURIComponent(wsUrlBrowser)}`;

  const jvmPromise = spawnCapture(
    './gradlew',
    [':nge-platform-interop-test:jvm:jvmWebsocketParityJvmSide', `-PinteropSignalBase=${signalBaseJvm}`, `-PinteropWsUrl=${wsUrlJvm}`, '--console=plain'],
    { cwd: repoRoot, label: 'jvm' }
  );

  const androidPromise = spawnCapture(
    './gradlew',
    [':nge-platform-interop-test:android:androidRtcEmulatorHarness', '--console=plain'],
    {
      cwd: repoRoot,
      label: 'android',
      env: {
        ANDROID_RTC_TEST_FILTER: 'org.ngengine.platform.android.AndroidWebsocketParityInstrumentedTest',
        ANDROID_RTC_SIGNAL_BASE: signalBaseAndroid,
        ANDROID_RTC_WS_URL: wsUrlAndroid,
        ANDROID_AVD_NAME: process.env.ANDROID_AVD_NAME || 'Generic_AOSP',
      },
    }
  );

  let browserOut = null;
  let browserErr = null;
  try {
    browserOut = await runBrowser(pageUrl);
  } catch (e) {
    browserErr = e;
  }

  const [jvmOut, androidOut] = await Promise.all([jvmPromise, androidPromise]);
  wss.close();
  server.close();

  if (browserErr) throw browserErr;

  const cmp = compare(state.results);
  const out = {
    ok: cmp.ok && jvmOut.code === 0 && androidOut.code === 0 && Boolean(browserOut?.result?.ok),
    compare: cmp,
    results: state.results,
    browser: browserOut?.result ?? null,
    jvmExitCode: jvmOut.code,
    androidExitCode: androidOut.code,
    exclusions: [
      'browser side uses native WebSocket baseline (not direct TeaVMWebsocketTransport Java wrapper)',
      'clientClosePhase.serverCloseCount varies by backend close-callback semantics and is not compared exactly',
    ],
  };
  if ((!state.results.jvm || jvmOut.code !== 0) && jvmOut.stdout.trim()) {
    process.stderr.write(`[jvm stdout buffered]\n${jvmOut.stdout}\n`);
  }
  if ((!state.results.jvm || jvmOut.code !== 0) && jvmOut.stderr.trim()) {
    process.stderr.write(`[jvm stderr buffered]\n${jvmOut.stderr}\n`);
  }
  if ((!state.results.android || androidOut.code !== 0) && androidOut.stdout.trim()) {
    process.stderr.write(`[android stdout buffered]\n${androidOut.stdout}\n`);
  }
  if ((!state.results.android || androidOut.code !== 0) && androidOut.stderr.trim()) {
    process.stderr.write(`[android stderr buffered]\n${androidOut.stderr}\n`);
  }
  process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  if (!out.ok) process.exit(1);
}

main().catch((e) => {
  process.stderr.write(`${e.stack || e.message || String(e)}\n`);
  process.exit(1);
});
