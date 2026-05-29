import http from 'node:http';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { WebSocketServer } from 'ws';
import wrtc from '@roamhq/wrtc';
import { emitInteropAnnotation, firstFailureText } from './ci-annotations.mjs';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const state = { ios: null, messages: [], cursor: 0 };
const INTEROP_TITLE = 'Interop: iOS Simulator <-> Node Harness';
const buildTask = ':nge-platform-interop-test:ios:buildIosSimulatorApp';
const iosSimulatorDevice = process.env.IOS_SIMULATOR_DEVICE || process.env.IOS_SIMULATOR_UDID || '';
const iosResultTimeoutMs = Number.parseInt(process.env.IOS_INTEROP_RESULT_TIMEOUT_MS || `${8 * 60 * 1000}`, 10);
const iosBundleId = 'org.ngengine.platform.interop.ios';
const iosAppDir = path.join(projectDir, 'ios', 'build', 'ios-graal-simulator', 'NGEIosInteropSim.app');
const rtcStressMessages = 24;
const enableIosRtc = !/^false$/i.test(process.env.IOS_INTEROP_ENABLE_RTC || 'true');
const extraGradleArgs = (process.env.IOS_INTEROP_GRADLE_ARGS || '').split(/\s+/).filter(Boolean);

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
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
      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (peer !== 'ios') return json(res, 400, { error: 'invalid peer' });
        state[peer] = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
      }
      if (method === 'POST' && reqUrl.pathname === '/signal/send') {
        const msg = JSON.parse(await readBody(req));
        state.messages.push({ id: ++state.cursor, ...msg });
        return json(res, 200, { ok: true, cursor: state.cursor });
      }
      if (method === 'GET' && reqUrl.pathname === '/signal/poll') {
        const to = reqUrl.searchParams.get('to');
        const after = Number.parseInt(reqUrl.searchParams.get('after') || '0', 10);
        const messages = state.messages.filter((m) => m.to === to && m.id > after);
        return json(res, 200, { cursor: state.cursor, messages });
      }
      if (method === 'POST' && reqUrl.pathname === '/ios-http') {
        const body = await readBody(req);
        const reqHdr = req.headers['x-ios-interop'] || '';
        res.writeHead(201, {
          'Content-Type': 'text/plain; charset=utf-8',
          'X-IOS-Interop-Reply': 'ok',
          'Cache-Control': 'no-store',
        });
        res.end(`echo:${body}|req:${reqHdr}`);
        return;
      }
      if (method === 'POST' && reqUrl.pathname === '/parity-http') {
        const body = await readBody(req);
        const reqHdr = req.headers['x-parity-req'] || '';
        res.writeHead(201, {
          'Content-Type': 'text/plain; charset=utf-8',
          'X-Parity-Reply': 'ok',
          'Cache-Control': 'no-store',
        });
        res.end(`echo:${body}|req:${reqHdr}`);
        return;
      }
      if (method === 'GET' && reqUrl.pathname === '/ios-http-get') {
        const reqHdr = req.headers['x-ios-get'] || '';
        res.writeHead(200, {
          'Content-Type': 'text/plain; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        res.end(`get:${reqUrl.searchParams.get('from') || ''}|req:${reqHdr}`);
        return;
      }
      if (method === 'GET' && reqUrl.pathname === '/signal/results') return json(res, 200, state);
      return json(res, 404, { error: `Not found: ${reqUrl.pathname}` });
    } catch (err) {
      json(res, 500, { error: String(err?.stack || err) });
    }
  });
}

function attachWsServer(server) {
  const wss = new WebSocketServer({ server, path: '/ios-ws' });
  wss.on('connection', (socket) => {
    socket.send('welcome');
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        socket.send(data, { binary: true });
        return;
      }
      const msg = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
      if (msg.startsWith('echo:') || msg.startsWith('stress-client:')) {
        socket.send(msg);
        return;
      }
      if (msg.startsWith('burst-server:')) {
        const requested = Number(msg.slice('burst-server:'.length));
        const count = Number.isFinite(requested) && requested >= 0 ? Math.floor(requested) : 0;
        for (let i = 0; i < count; i += 1) socket.send(`stress-server:${i}`);
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

function spawnCapture(cmd, args, { cwd, env, label, timeoutMs } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, env: { ...process.env, ...(env || {}) }, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    let killTimer = null;
    const timeoutTimer = timeoutMs
      ? setTimeout(() => {
          timedOut = true;
          stderr += `\nTimed out after ${timeoutMs}ms; terminating ${cmd} ${args.join(' ')}\n`;
          child.kill('SIGTERM');
          killTimer = setTimeout(() => child.kill('SIGKILL'), 10_000);
          killTimer.unref?.();
        }, timeoutMs)
      : null;
    timeoutTimer?.unref?.();
    const outWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stdout] `) : null;
    const errWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stderr] `) : null;
    child.stdout.on('data', (d) => { const s = d.toString(); stdout += s; outWriter?.push(s); });
    child.stderr.on('data', (d) => { const s = d.toString(); stderr += s; errWriter?.push(s); });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (timeoutTimer) clearTimeout(timeoutTimer);
      if (killTimer) clearTimeout(killTimer);
      outWriter?.flush();
      errWriter?.flush();
      resolve({ code: timedOut ? -1 : (code ?? -1), stdout, stderr });
    });
  });
}

function runSimctl(args, options = {}) {
  return spawnCapture('xcrun', ['simctl', ...args], {
    cwd: repoRoot,
    label: options.label || 'simctl',
    env: options.env,
    timeoutMs: options.timeoutMs,
  });
}

async function expectSuccess(promise, description, { ignoreFailure = false } = {}) {
  const out = await promise;
  if (out.code !== 0 && !ignoreFailure) {
    throw new Error(`${description} failed with exit code ${out.code}\n${out.stderr || out.stdout}`);
  }
  return out;
}

async function postSignal(signalBase, body) {
  const res = await fetch(`${signalBase}/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST /send failed: ${res.status} ${await res.text()}`);
}

async function startNodeRtcPeer(signalBase) {
  const pc = new wrtc.RTCPeerConnection({ iceServers: [] });
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  const receivedTexts = [];
  let cursor = 0;
  let remoteDescriptionSet = false;
  let channel = null;
  const pendingIce = [];

  pc.onicecandidate = (e) => {
    if (e.candidate) {
      postSignal(signalBase, {
        to: 'ios',
        type: 'ice',
        candidate: e.candidate.candidate || '',
        sdpMid: e.candidate.sdpMid || '',
      }).catch((err) => {
        postSignal(signalBase, { to: 'ios', type: 'node-failed', error: String(err?.stack || err) }).catch(() => {});
      });
    }
  };

  async function flushIce() {
    while (pendingIce.length) {
      await pc.addIceCandidate(pendingIce.shift());
    }
  }

  async function handleMessage(msg) {
    if (msg.type === 'offer') {
      process.stderr.write('[ios-rtc] received offer from iOS\n');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      remoteDescriptionSet = true;
      await flushIce();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await postSignal(signalBase, { to: 'ios', type: 'answer', sdp: answer.sdp });
      process.stderr.write('[ios-rtc] sent answer to iOS\n');
    } else if (msg.type === 'ice') {
      const candidate = { candidate: msg.candidate, sdpMid: msg.sdpMid || undefined };
      if (!remoteDescriptionSet) pendingIce.push(candidate);
      else await pc.addIceCandidate(candidate);
    }
  }

  const channelReady = new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), iosResultTimeoutMs);
    pc.ondatachannel = (ev) => {
      process.stderr.write(`[ios-rtc] received data channel ${ev.channel?.label || ''}\n`);
      channel = ev.channel;
      channel.binaryType = 'arraybuffer';
      channel.onmessage = (event) => {
        const bytes = event.data instanceof ArrayBuffer
          ? new Uint8Array(event.data)
          : Buffer.isBuffer(event.data)
            ? new Uint8Array(event.data)
            : encoder.encode(String(event.data));
        receivedTexts.push(decoder.decode(bytes));
      };
      channel.onopen = () => {
        process.stderr.write('[ios-rtc] data channel open\n');
        clearTimeout(timer);
        resolve(channel);
      };
    };
  });

  const pollDeadline = Date.now() + iosResultTimeoutMs;
  while (!channel && Date.now() < pollDeadline) {
    const res = await fetch(`${signalBase}/poll?to=node&after=${cursor}`);
    if (!res.ok) throw new Error(`Node RTC poll failed: ${res.status} ${await res.text()}`);
    const poll = await res.json();
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) await handleMessage(msg);
    if (!channel) await new Promise((resolve) => setTimeout(resolve, 50));
  }
  const dc = await channelReady;
  if (!dc) {
    throw new Error('Timed out waiting for iOS RTC data channel');
  }
  await postSignal(signalBase, { to: 'ios', type: 'node-ready' });

  for (let i = 0; i < rtcStressMessages; i += 1) {
    dc.send(encoder.encode(`node-seq:${i}`));
  }

  const stressDeadline = Date.now() + 30_000;
  while (receivedTexts.length < rtcStressMessages && Date.now() < stressDeadline) {
    const res = await fetch(`${signalBase}/poll?to=node&after=${cursor}`);
    if (!res.ok) throw new Error(`Node RTC poll failed: ${res.status} ${await res.text()}`);
    const poll = await res.json();
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) await handleMessage(msg);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  if (receivedTexts.length < rtcStressMessages) {
    throw new Error(`Timed out waiting for iOS RTC stress burst (${receivedTexts.length}/${rtcStressMessages})`);
  }
  for (let i = 0; i < rtcStressMessages; i += 1) {
    const expected = `ios-seq:${i}`;
    if (receivedTexts[i] !== expected) {
      throw new Error(`Out-of-order iOS RTC message: expected=${expected} actual=${receivedTexts[i]}`);
    }
  }
  await postSignal(signalBase, { to: 'ios', type: 'node-result', iosToNodeStressCount: rtcStressMessages });
  setTimeout(() => {
    try { dc.close(); } catch {}
    try { pc.close(); } catch {}
  }, 100);
  return { ok: true, iosToNodeStressCount: rtcStressMessages, nodeToIosStressCount: rtcStressMessages };
}

function waitForIosResult(timeoutMs) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (state.ios) {
        clearInterval(timer);
        resolve(state.ios);
        return;
      }
      if (Date.now() - started > timeoutMs) {
        clearInterval(timer);
        reject(new Error(`Timed out waiting for iOS simulator result after ${timeoutMs}ms`));
      }
    }, 250);
  });
}

function parseIosResultFromLaunchOutput(out) {
  const text = `${out?.stdout || ''}\n${out?.stderr || ''}`;
  const matches = [...text.matchAll(/IOS_INTEROP_RESULT_JSON_BASE64=([A-Za-z0-9+/=]+)/g)];
  const last = matches.at(-1);
  if (!last) return null;
  return JSON.parse(Buffer.from(last[1], 'base64').toString('utf8'));
}

function validateIosParity(ios) {
  const exactKeys = [
    'pubA','pubB','hmac','hkdfExtract','hkdfExpand','base64','base64Roundtrip',
    'chachaEnc','chachaDec','sha256String','sha256Bytes','jsonMap','jsonList','fromJson_x','fromJson_y_len','fromJson_z_k',
    'httpRequest_status','httpRequest_statusCode','httpRequest_body','httpRequest_replyHeader','httpGet_statusCode','httpGet_body'
  ];
  const mismatches = [];
  if (!ios?.ok) mismatches.push(`missing/failed result for ios: ${ios?.error || 'missing'}`);
  if (mismatches.length) return { ok: false, mismatches, checkedKeys: exactKeys };
  if (ios.platformName !== 'iOS') mismatches.push(`platformName mismatch: ${ios.platformName}`);
  for (const key of exactKeys) {
    if (ios[key] === undefined || ios[key] === null) mismatches.push(`missing iOS parity key ${key}`);
  }
  for (const key of ['signatureLen', 'signatureAsyncLen']) {
    if (Number(ios[key]) !== 128) mismatches.push(`ios.${key} != 128`);
  }
  for (const key of ['verifyOwn', 'verifyAsync', 'signatureVerified']) {
    if (Boolean(ios[key]) !== true) mismatches.push(`ios.${key} != true`);
  }
  if (Boolean(ios.verifyWrong) !== false) mismatches.push('ios.verifyWrong != false');
  if (Number(ios.randomLen) !== 16 || ios.randomNonZero !== true) mismatches.push('ios random invariant failed');
  if (Number(ios.generatedPrivateKeyLen) !== 32 || ios.generatedPrivateKeyNonZero !== true) mismatches.push('ios generated private key invariant failed');
  if (ios.httpRequest_status !== true || ios.httpRequest_statusCode !== 201) mismatches.push('iOS HTTP POST status failed');
  if (ios.httpRequest_body !== 'echo:parity-http-body|req:parity' || ios.httpRequest_replyHeader !== 'ok') {
    mismatches.push('iOS HTTP POST body/header failed');
  }
  if (ios.httpGet_statusCode !== 200 || ios.httpGet_body !== 'get:ios|req:ios') mismatches.push('iOS HTTP GET failed');
  if (ios.asyncValue !== 'ios-async-ok') mismatches.push(`asyncValue mismatch: ${ios.asyncValue}`);
  if (ios.assetResource !== 'ios-resource-ok') mismatches.push(`assetResource mismatch: ${ios.assetResource}`);
  if (ios.websocketOk !== true) mismatches.push('iOS WebSocket failed');
  if (ios.websocketClientStressCount !== 24 || ios.websocketServerStressCount !== 24) mismatches.push('iOS WebSocket stress counts failed');
  if (enableIosRtc) {
    if (ios.rtcOk !== true) mismatches.push('iOS RTC failed');
    if (ios.iosToNodeRtcStressCount !== rtcStressMessages || ios.nodeToIosRtcStressCount !== rtcStressMessages) {
      mismatches.push('iOS RTC stress counts failed');
    }
  }
  return { ok: mismatches.length === 0, mismatches, checkedKeys: exactKeys };
}

async function main() {
  const simulatorArgs = iosSimulatorDevice ? [`-PiosSimulatorDevice=${iosSimulatorDevice}`] : [];
  const gradleInteropArgs = ['-PskipAndroidInterop=true', '-PforceIosInterop=true', ...simulatorArgs, ...extraGradleArgs];
  const buildOut = await spawnCapture('./gradlew', [buildTask, '--console=plain', ...gradleInteropArgs], {
    cwd: repoRoot,
    label: 'ios-build',
  });
  if (buildOut.code !== 0) {
    throw new Error(`iOS simulator app build failed with exit code ${buildOut.code}\n${buildOut.stderr || buildOut.stdout}`);
  }
  if (!iosSimulatorDevice) {
    throw new Error('IOS_SIMULATOR_DEVICE or IOS_SIMULATOR_UDID is required to launch the iOS simulator harness.');
  }

  const server = makeServer();
  const wss = attachWsServer(server);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const signalBase = `http://127.0.0.1:${port}/signal`;

  const nodeRtcPromise = enableIosRtc
    ? startNodeRtcPeer(signalBase).catch(async (err) => {
        await postSignal(signalBase, { to: 'ios', type: 'node-failed', error: String(err?.stack || err) }).catch(() => {});
        return { ok: false, error: String(err?.stack || err) };
      })
    : Promise.resolve({ skipped: true });

  await expectSuccess(runSimctl(['boot', iosSimulatorDevice]), 'Boot iOS simulator', { ignoreFailure: true });
  await expectSuccess(runSimctl(['bootstatus', iosSimulatorDevice, '-b']), 'Wait for iOS simulator boot');
  await expectSuccess(runSimctl(['terminate', iosSimulatorDevice, iosBundleId]), 'Terminate previous iOS interop app', { ignoreFailure: true });
  await expectSuccess(runSimctl(['install', iosSimulatorDevice, iosAppDir]), 'Install iOS interop app');

  const launchPromise = runSimctl(
    ['launch', '--console', '--terminate-running-process', iosSimulatorDevice, iosBundleId],
    {
      label: 'ios',
      env: {
        SIMCTL_CHILD_IOS_INTEROP_SIGNAL_BASE: signalBase,
        SIMCTL_CHILD_IOS_INTEROP_KIND: 'smoke',
        SIMCTL_CHILD_IOS_INTEROP_ENABLE_RTC: String(enableIosRtc),
        SIMCTL_CHILD_LIBJGLIOS_MAX_FRAMES: process.env.LIBJGLIOS_MAX_FRAMES || '3600',
      },
      timeoutMs: iosResultTimeoutMs + 30_000,
    }
  );

  let iosResult = null;
  let resultError = null;
  try {
    iosResult = await Promise.race([
      waitForIosResult(iosResultTimeoutMs),
      launchPromise.then((out) => {
        if (!state.ios) {
          state.ios = parseIosResultFromLaunchOutput(out);
        }
        if (!state.ios) {
          throw new Error(
            `iOS simulator app exited before publishing a result (exit code ${out.code}).\n${out.stderr || out.stdout}`
          );
        }
        return state.ios;
      }),
    ]);
  } catch (e) {
    resultError = e;
  }

  if (state.ios) {
    await expectSuccess(
      runSimctl(['terminate', iosSimulatorDevice, iosBundleId], { timeoutMs: 10_000 }),
      'Terminate completed iOS interop app',
      { ignoreFailure: true }
    );
  }
  const launchOut = await launchPromise;
  const nodeRtcOut = await nodeRtcPromise;
  await new Promise((resolve) => wss.close(resolve));
  server.close();

  if (resultError) throw resultError;
  const compare = validateIosParity(iosResult);
  const checks = [...compare.mismatches];
  if (enableIosRtc && !nodeRtcOut?.ok) checks.push('Node RTC side failed');

  const out = {
    ok: launchOut.code === 0 && checks.length === 0,
    ios: iosResult,
    nodeRtc: nodeRtcOut,
    compare,
    simulatorExitCode: launchOut.code,
    checks,
    coverage: [
      'iOS simulator app build through libJGLIOS/GraalVM',
      'iOS simulator launch through simctl',
      'iOS NGEPlatform parity-style snapshot for crypto/hash/encoding/json/random/http',
      'iOS async executor and async Schnorr calls',
      'iOS classpath resource loading',
      'iOS HTTP GET/POST interoperability with local Node harness',
      'iOS WebSocket interoperability with local Node harness',
      enableIosRtc
        ? 'iOS RTC data channel interoperability with Node WebRTC'
        : 'iOS RTC data channel interoperability skipped by IOS_INTEROP_ENABLE_RTC=false',
    ],
  };
  process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  emitInteropAnnotation(
    INTEROP_TITLE,
    out.ok,
    out.ok
      ? `iOS simulator built, launched, and passed platform parity-style, HTTP, WebSocket${enableIosRtc ? ', and RTC' : ''} checks.`
      : firstFailureText(checks.join('; '), iosResult?.error)
  );
  if (!out.ok) process.exit(1);
}

main().catch((err) => {
  emitInteropAnnotation(INTEROP_TITLE, false, firstFailureText(err?.stack || err?.message || err));
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
