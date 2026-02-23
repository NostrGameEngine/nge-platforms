import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import puppeteer from 'puppeteer-core';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const teavmDir = path.resolve(repoRoot, 'nge-platform-teavm');
const rootDirs = [
  path.join(projectDir, 'test-harness'),
  path.join(teavmDir, 'src', 'main', 'resources'),
];

const state = {
  nextId: 1,
  queues: { browser: [], android: [] },
  results: { browser: null, android: null },
};

function mimeType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js') || file.endsWith('.mjs')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.json')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

function resolvePath(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]).replace(/^\/+/, '');
  for (const base of rootDirs) {
    const normalized = clean.startsWith('test-harness/') ? clean.replace(/^test-harness\//, '') : clean;
    const candidate = path.resolve(base, normalized);
    if (candidate.startsWith(base) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return candidate;
    }
  }
  return null;
}

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
}

function makeServer() {
  return http.createServer(async (req, res) => {
    try {
      const method = req.method || 'GET';
      const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');

      if (method === 'POST' && reqUrl.pathname === '/signal/send') {
        const body = JSON.parse(await readBody(req));
        const to = body.to;
        if (to !== 'browser' && to !== 'android') return json(res, 400, { error: 'invalid target' });
        const msg = { ...body, id: state.nextId++ };
        state.queues[to].push(msg);
        return json(res, 200, { ok: true, id: msg.id });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/poll') {
        const to = reqUrl.searchParams.get('to');
        const after = Number(reqUrl.searchParams.get('after') || '0');
        if (to !== 'browser' && to !== 'android') return json(res, 400, { error: 'invalid target' });
        const messages = state.queues[to].filter((m) => (m.id || 0) > after);
        return json(res, 200, { cursor: state.nextId - 1, messages });
      }

      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (peer !== 'browser' && peer !== 'android') return json(res, 400, { error: 'invalid peer' });
        state.results[peer] = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/results') {
        return json(res, 200, state.results);
      }

      const file = resolvePath(reqUrl.pathname === '/' ? '/test-harness/rtc-android-interop.html' : reqUrl.pathname);
      if (!file) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`Not found: ${reqUrl.pathname}`);
        return;
      }
      res.writeHead(200, { 'Content-Type': mimeType(file) });
      fs.createReadStream(file).pipe(res);
    } catch (err) {
      json(res, 500, { error: String(err?.stack || err) });
    }
  });
}

function spawnCapture(cmd, args, { cwd, env } = {}) {
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
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, env: { ...process.env, ...(env || {}) }, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    const outWriter = createLinePrefixWriter(process.stderr, '[android stdout] ');
    const errWriter = createLinePrefixWriter(process.stderr, '[android stderr] ');
    child.stdout.on('data', (d) => { const s = d.toString(); stdout += s; outWriter.push(s); });
    child.stderr.on('data', (d) => { const s = d.toString(); stderr += s; errWriter.push(s); });
    child.on('error', reject);
    child.on('exit', (code) => { outWriter.flush(); errWriter.flush(); resolve({ code: code ?? -1, stdout, stderr }); });
  });
}

async function runBrowser(url) {
  const chrome = process.env.CHROME_BIN || '/usr/bin/google-chrome';
  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--disable-features=WebRtcHideLocalIpsWithMdns',
    ],
  });
  const page = await browser.newPage();
  const consoleMessages = [];
  const pageErrors = [];
  page.on('console', (msg) => consoleMessages.push(`[${msg.type()}] ${msg.text()}`));
  page.on('pageerror', (err) => pageErrors.push(err.stack || err.message || String(err)));
  page.on('requestfailed', (req) => {
    consoleMessages.push(`[requestfailed] ${req.url()} ${req.failure()?.errorText || 'unknown'}`);
  });

  try {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 20000 });
    await page.waitForFunction(() => {
      const el = document.getElementById('result');
      if (!el) return false;
      const t = (el.textContent || '').trim();
      if (t === '' || t === 'RUNNING') return false;
      try {
        const parsed = JSON.parse(t);
        return parsed && typeof parsed.ok === 'boolean';
      } catch {
        return false;
      }
    }, { timeout: 180000 });
    const raw = await page.$eval('#result', (el) => el.textContent || '');
    return { result: JSON.parse(raw), consoleMessages, pageErrors };
  } catch (err) {
    let raw = '';
    try {
      raw = await page.$eval('#result', (el) => el.textContent || '');
    } catch {}
    err.message = `${err.message}\nBrowser result element:\n${raw}`;
    throw err;
  } finally {
    await browser.close();
  }
}

async function main() {
  const bundlePath = path.join(
    teavmDir,
    'src',
    'main',
    'resources',
    'org',
    'ngengine',
    'platform',
    'teavm',
    'TeaVMBinds.bundle.js'
  );
  if (!fs.existsSync(bundlePath)) {
    throw new Error(`Missing TeaVM bundle at ${bundlePath}. Run :nge-platform-teavm:copyWebpackOutput first.`);
  }

  const server = makeServer();
  await new Promise((resolve) => server.listen(0, '0.0.0.0', resolve));
  const addr = server.address();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const browserSignalBase = `http://127.0.0.1:${port}/signal`;
  const androidSignalBase = `http://10.0.2.2:${port}/signal`;
  const pageUrl = `http://127.0.0.1:${port}/test-harness/rtc-android-interop.html?signalBase=${encodeURIComponent(browserSignalBase)}`;

  const androidPromise = spawnCapture('./gradlew', [
    ':nge-platform-interop-test:android:androidRtcEmulatorHarness',
    '--console=plain',
  ], {
    cwd: repoRoot,
    env: {
      ANDROID_RTC_TEST_FILTER: 'org.ngengine.platform.android.AndroidTeaVMRtcInteropInstrumentedTest',
      ANDROID_RTC_SIGNAL_BASE: androidSignalBase,
      ANDROID_AVD_NAME: process.env.ANDROID_AVD_NAME || 'Generic_AOSP',
    },
  });

  let browserOut = null;
  let browserError = null;
  try {
    browserOut = await runBrowser(pageUrl);
  } catch (e) {
    browserError = e;
  }
  const androidOut = await androidPromise;

  if (browserError) {
    throw browserError;
  }

  const combined = {
    ok: Boolean(state.results.browser?.ok) && Boolean(state.results.android?.ok) && androidOut.code === 0,
    browser: state.results.browser ?? browserOut?.result ?? null,
    android: state.results.android ?? null,
    androidExitCode: androidOut.code,
  };

  if (browserOut?.consoleMessages?.length) process.stderr.write(`${browserOut.consoleMessages.join('\n')}\n`);
  if (browserOut?.pageErrors?.length) process.stderr.write(`${browserOut.pageErrors.join('\n')}\n`);
  process.stdout.write(`${JSON.stringify(combined, null, 2)}\n`);
  server.close();
  if (!combined.ok) process.exit(1);
}

main().catch((err) => {
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
