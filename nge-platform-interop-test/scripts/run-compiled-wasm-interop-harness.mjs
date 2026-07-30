import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import puppeteer from 'puppeteer-core';
import { WebSocketServer } from 'ws';
import { emitInteropAnnotation, firstFailureText } from './ci-annotations.mjs';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const wasmClassDir = path.join(
  repoRoot,
  'nge-platform-teavm',
  'build',
  'js-tests',
  'wasm-gc',
  'org',
  'ngengine',
  'platform',
  'teavm',
  'TeaVMCompiledWasmInteropTest'
);
const INTEROP_TITLE = 'Interop: compiled TeaVM Wasm GC <-> JVM';
const jvmRuntimeMetadataFile = path.join(projectDir, 'jvm', 'build', 'interop-runtime.json');

const state = {
  nextId: 1,
  queues: { browser: [], jvm: [] },
  results: { wasm: null, jvm: null },
};

function resetState() {
  state.nextId = 1;
  state.queues = { browser: [], jvm: [] };
  state.results = { wasm: null, jvm: null };
}

function json(res, status, value) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(JSON.stringify(value));
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

function mimeType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js') || file.endsWith('.mjs')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.wasm')) return 'application/wasm';
  if (file.endsWith('.json') || file.endsWith('.map')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}

function resolveWasmFile(urlPath) {
  if (!urlPath.startsWith('/wasm/')) return null;
  const relative = decodeURIComponent(urlPath.slice('/wasm/'.length));
  const candidate = path.resolve(wasmClassDir, relative);
  if (!candidate.startsWith(`${wasmClassDir}${path.sep}`)) return null;
  return fs.existsSync(candidate) && fs.statSync(candidate).isFile() ? candidate : null;
}

function makeServer() {
  return http.createServer(async (req, res) => {
    try {
      const method = req.method || 'GET';
      const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');
      process.stderr.write(`[interop http] ${method} ${reqUrl.pathname}${reqUrl.search}\n`);

      if (method === 'POST' && reqUrl.pathname === '/parity-http') {
        const body = await readBody(req);
        res.writeHead(201, {
          'Content-Type': 'text/plain; charset=utf-8',
          'X-Parity-Reply': 'ok',
          'Cache-Control': 'no-store',
        });
        res.end(`echo:${body}|req:${req.headers['x-parity-req'] || ''}`);
        return;
      }

      if (method === 'POST' && reqUrl.pathname === '/signal/send') {
        const body = JSON.parse(await readBody(req));
        if (body.to !== 'browser' && body.to !== 'jvm') {
          return json(res, 400, { error: 'invalid target' });
        }
        const message = { ...body, id: state.nextId++ };
        state.queues[body.to].push(message);
        return json(res, 200, { ok: true, id: message.id });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/poll') {
        const target = reqUrl.searchParams.get('to');
        const after = Number(reqUrl.searchParams.get('after') || 0);
        if (target !== 'browser' && target !== 'jvm') {
          return json(res, 400, { error: 'invalid target' });
        }
        return json(res, 200, {
          cursor: state.nextId - 1,
          messages: state.queues[target].filter((message) => message.id > after),
        });
      }

      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (peer !== 'wasm' && peer !== 'jvm') {
          return json(res, 400, { error: 'invalid peer' });
        }
        state.results[peer] = JSON.parse(await readBody(req));
        process.stderr.write(`[interop result:${peer}] ${JSON.stringify(state.results[peer])}\n`);
        return json(res, 200, { ok: true });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/results') {
        return json(res, 200, state.results);
      }

      if (method === 'GET' && reqUrl.pathname === '/favicon.ico') {
        res.writeHead(204);
        res.end();
        return;
      }

      const file = resolveWasmFile(reqUrl.pathname);
      if (!file) return json(res, 404, { error: `Not found: ${reqUrl.pathname}` });
      res.writeHead(200, { 'Content-Type': mimeType(file), 'Cache-Control': 'no-store' });
      fs.createReadStream(file).pipe(res);
    } catch (error) {
      json(res, 500, { error: String(error?.stack || error) });
    }
  });
}

function attachWebsocketServer(server) {
  const wss = new WebSocketServer({ server, path: '/ws' });
  wss.on('connection', (socket) => {
    socket.send('welcome');
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        socket.send(data, { binary: true });
        return;
      }
      const message = data.toString('utf8');
      if (message.startsWith('echo:') || message.startsWith('stress-client:')) {
        socket.send(message);
      } else if (message.startsWith('burst-server:')) {
        const count = Number(message.slice('burst-server:'.length));
        for (let i = 0; i < count; i += 1) socket.send(`stress-server:${i}`);
      } else if (message === 'close-by-server') {
        socket.close(1000, 'server-close');
      } else {
        socket.send(`unknown:${message}`);
      }
    });
  });
  return wss;
}

function lineWriter(prefix) {
  let buffer = '';
  return {
    push(chunk) {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        process.stderr.write(`${prefix}${buffer.slice(0, newline + 1)}`);
        buffer = buffer.slice(newline + 1);
      }
    },
    flush() {
      if (buffer) process.stderr.write(`${prefix}${buffer}\n`);
      buffer = '';
    },
  };
}

function spawnJvm(mainClass, args, label, systemProperties = []) {
  return new Promise((resolve, reject) => {
    const metadata = JSON.parse(fs.readFileSync(jvmRuntimeMetadataFile, 'utf8'));
    const child = spawn(metadata.java, [
      ...systemProperties.map((property) => `-D${property}`),
      '-cp',
      metadata.classpath,
      mainClass,
      ...args,
    ], {
      cwd: repoRoot,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const stdout = lineWriter(`[${label} stdout] `);
    const stderr = lineWriter(`[${label} stderr] `);
    child.stdout.on('data', (chunk) => stdout.push(chunk.toString()));
    child.stderr.on('data', (chunk) => stderr.push(chunk.toString()));
    child.on('error', reject);
    child.on('exit', (code) => {
      stdout.flush();
      stderr.flush();
      resolve(code ?? -1);
    });
  });
}

async function runCompiledWasm(pageUrl, timeout = 90_000) {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--disable-features=WebRtcHideLocalIpsWithMdns',
    ],
  });
  const page = await browser.newPage();
  await page.evaluateOnNewDocument(() => {
    // Browser GC timing is intentionally nondeterministic. Delegate to the
    // native registry while exposing one deterministic callback hook to this
    // integration harness.
    const NativeFinalizationRegistry = globalThis.FinalizationRegistry;
    if (typeof NativeFinalizationRegistry !== 'function') return;

    const entries = [];
    class TrackedFinalizationRegistry {
      constructor(callback) {
        this.callback = callback;
        this.nativeRegistry = new NativeFinalizationRegistry(callback);
      }

      register(target, heldValue, unregisterToken) {
        entries.push({ registry: this, heldValue, unregisterToken, active: true });
        this.nativeRegistry.register(target, heldValue, unregisterToken);
      }

      unregister(unregisterToken) {
        for (const entry of entries) {
          if (entry.registry === this && entry.active && entry.unregisterToken === unregisterToken) entry.active = false;
        }
        return this.nativeRegistry.unregister(unregisterToken);
      }

      run(entry) {
        entry.active = false;
        if (entry.unregisterToken !== undefined) this.nativeRegistry.unregister(entry.unregisterToken);
        this.callback(entry.heldValue);
      }
    }

    globalThis.FinalizationRegistry = TrackedFinalizationRegistry;
    globalThis.__ngeFinalizationEntryCount = () => entries.length;
    globalThis.__ngeTriggerFinalizer = (index) => {
      const entry = entries[index];
      if (!entry?.active) return false;
      entry.registry.run(entry);
      return true;
    };
  });
  const diagnostics = [];
  page.on('console', (message) => {
    const line = `[console:${message.type()}] ${message.text()}`;
    diagnostics.push(line);
    process.stderr.write(`${line}\n`);
  });
  page.on('pageerror', (error) => diagnostics.push(`[pageerror] ${error.stack || error.message || error}`));
  page.on('requestfailed', (request) =>
    diagnostics.push(`[requestfailed] ${request.url()} ${request.failure()?.errorText || 'unknown'}`)
  );
  try {
    await page.goto(pageUrl, { waitUntil: 'networkidle2', timeout: 20_000 });
    await page.waitForFunction(
      () => {
        const pre = document.querySelector('pre');
        return pre && (pre.textContent || '').trim().length > 0;
      },
      { timeout }
    );
    const testResult = await page.$eval('pre', (element) => (element.textContent || '').trim());
    if (diagnostics.length) process.stderr.write(`${diagnostics.join('\n')}\n`);
    if (testResult !== 'OK') throw new Error(`Compiled Wasm JUnit failure:\n${testResult}`);
    return testResult;
  } finally {
    await browser.close();
  }
}

function assertResult(name, value) {
  if (!value?.ok) throw new Error(`${name} failed: ${value?.error || JSON.stringify(value)}`);
}

function comparePlatform(wasm, jvm) {
  const keys = [
    'sha256Bytes',
    'base64',
    'hmac',
    'httpRequest_status',
    'httpRequest_statusCode',
    'httpRequest_body',
    'httpRequestStream_statusCode',
    'httpRequestStream_body',
    'scrypt',
  ];
  const mismatches = [];
  for (const key of keys) {
    if (JSON.stringify(wasm[key]) !== JSON.stringify(jvm[key])) {
      mismatches.push(`${key}: wasm=${JSON.stringify(wasm[key])} jvm=${JSON.stringify(jvm[key])}`);
    }
  }
  if (!wasm.platformName?.startsWith('TeaVM Wasm GC (browser')) mismatches.push(`backend=${wasm.platformName}`);
  for (const key of [
    'callFunction',
    'openURL',
    'clipboard',
    'persistentStore',
    'executorRunLater',
    'finalizerExactlyOnce',
    'finalizerAutomatic',
  ]) {
    if (wasm[key] !== true) mismatches.push(`${key} was not verified`);
  }
  if (mismatches.length) throw new Error(`Compiled Wasm platform parity mismatch: ${mismatches.join('; ')}`);
  return keys;
}

async function runPlatformScenario(baseUrl, jvmBaseUrl) {
  resetState();
  const signalBase = `${baseUrl}/signal`;
  const httpUrl = `${baseUrl}/parity-http`;
  const jvmSignalBase = `${jvmBaseUrl}/signal`;
  const jvmHttpUrl = `${jvmBaseUrl}/parity-http`;
  const jvm = spawnJvm(
    'org.ngengine.platform.jvm.JVMPlatformParityMain',
    [jvmSignalBase, jvmHttpUrl],
    'platform-jvm',
    [
      'nge-platforms.allowLoopbackInURIs=true',
      'nge-platforms.allowPrivateNetworkInURIs=true',
      'nge-platforms.forceHttp1=true',
    ]
  );
  const pageUrl =
    `${baseUrl}/wasm/compiledWasmPlatformServicesParity/test.html` +
    `?signalBase=${encodeURIComponent(signalBase)}&httpUrl=${encodeURIComponent(httpUrl)}`;
  const [jvmExit] = await Promise.all([jvm, runCompiledWasm(pageUrl)]);
  assertResult('compiled Wasm platform parity', state.results.wasm);
  assertResult('JVM platform parity', state.results.jvm);
  if (jvmExit !== 0) throw new Error(`JVM platform parity exited with ${jvmExit}`);
  return { checkedExactKeys: comparePlatform(state.results.wasm, state.results.jvm), results: state.results };
}

async function runWebsocketScenario(baseUrl, jvmBaseUrl) {
  resetState();
  const signalBase = `${baseUrl}/signal`;
  const wsUrl = baseUrl.replace(/^http/, 'ws') + '/ws';
  const jvmWsUrl = jvmBaseUrl.replace(/^http/, 'ws') + '/ws';
  const jvm = spawnJvm(
    'org.ngengine.platform.jvm.JVMWebsocketParityMain',
    [`${jvmBaseUrl}/signal`, jvmWsUrl],
    'websocket-jvm',
    ['nge-platforms.allowLoopbackInURIs=true', 'nge-platforms.allowPrivateNetworkInURIs=true']
  );
  const pageUrl =
    `${baseUrl}/wasm/compiledWasmWebsocketParity/test.html` +
    `?signalBase=${encodeURIComponent(signalBase)}&wsUrl=${encodeURIComponent(wsUrl)}`;
  const [jvmExit] = await Promise.all([jvm, runCompiledWasm(pageUrl)]);
  assertResult('compiled Wasm WebSocket parity', state.results.wasm);
  assertResult('JVM WebSocket parity', state.results.jvm);
  if (jvmExit !== 0) throw new Error(`JVM WebSocket parity exited with ${jvmExit}`);
  if (
    state.results.wasm.clientToServerStressCount !== 256 ||
    state.results.wasm.serverToClientStressCount !== 256 ||
    state.results.wasm.binaryDirectEcho !== true ||
    state.results.wasm.ordered !== true
  ) {
    throw new Error(`Incomplete compiled Wasm WebSocket result: ${JSON.stringify(state.results.wasm)}`);
  }
  return { results: state.results };
}

async function runRtcScenario(baseUrl, jvmBaseUrl) {
  resetState();
  const signalBase = `${baseUrl}/signal`;
  const jvm = spawnJvm(
    'org.ngengine.platform.jvm.JVMTeaVMRtcInteropMain',
    [`${jvmBaseUrl}/signal`],
    'rtc-jvm'
  );
  const pageUrl =
    `${baseUrl}/wasm/compiledWasmRtcInteroperatesWithJvm/test.html` +
    `?signalBase=${encodeURIComponent(signalBase)}`;
  const [jvmExit] = await Promise.all([jvm, runCompiledWasm(pageUrl, 120_000)]);
  assertResult('compiled Wasm RTC interoperability', state.results.wasm);
  assertResult('JVM RTC interoperability', state.results.jvm);
  if (jvmExit !== 0) throw new Error(`JVM RTC interoperability exited with ${jvmExit}`);
  for (const peer of ['wasm', 'jvm']) {
    if (
      state.results[peer].browserToJvmStressCount !== 256 ||
      state.results[peer].jvmToBrowserStressCount !== 256
    ) {
      throw new Error(`Incomplete ${peer} RTC stress result: ${JSON.stringify(state.results[peer])}`);
    }
  }
  if (state.results.wasm.directInboundBuffers !== true) {
    throw new Error('Compiled Wasm RTC did not verify direct inbound ByteBuffers');
  }
  return { results: state.results };
}

async function main() {
  const wasmFile = path.join(wasmClassDir, 'classTest.wasm');
  if (!fs.existsSync(wasmFile) || fs.statSync(wasmFile).size < 4) {
    throw new Error(`Missing compiled Wasm test artifact: ${wasmFile}`);
  }
  if (!fs.readFileSync(wasmFile).subarray(0, 4).equals(Buffer.from([0x00, 0x61, 0x73, 0x6d]))) {
    throw new Error(`Invalid WebAssembly header: ${wasmFile}`);
  }

  const server = makeServer();
  const wss = attachWebsocketServer(server);
  await new Promise((resolve) => server.listen(0, '0.0.0.0', resolve));
  const port = server.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;
  const jvmBaseUrl = baseUrl;
  try {
    const platform = await runPlatformScenario(baseUrl, jvmBaseUrl);
    const websocket = await runWebsocketScenario(baseUrl, jvmBaseUrl);
    const rtc = await runRtcScenario(baseUrl, jvmBaseUrl);
    const output = {
      ok: true,
      artifact: { path: wasmFile, bytes: fs.statSync(wasmFile).size },
      platform,
      websocket,
      rtc,
      exclusions: [],
    };
    process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
    emitInteropAnnotation(
      INTEROP_TITLE,
      true,
      'Compiled TeaVM Wasm GC passed JVM parity, real WebSocket, and bidirectional JVM RTC interoperability.'
    );
  } finally {
    wss.close();
    server.close();
  }
}

main().catch((error) => {
  emitInteropAnnotation(INTEROP_TITLE, false, firstFailureText(error?.stack || error?.message || error));
  process.stderr.write(`${error.stack || error.message || String(error)}\n`);
  process.exit(1);
});
