import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const teavmDir = path.resolve(repoRoot, 'nge-platform-teavm');
const STRESS_MESSAGES = 256;

const state = {
  nextId: 1,
  queues: { browser: [], jvm: [] },
  results: { browser: null, jvm: null },
};

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

async function readRequestBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
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

function makeServer() {
  return http.createServer(async (req, res) => {
    try {
      const method = req.method || 'GET';
      const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');

      if (method === 'POST' && reqUrl.pathname === '/signal/send') {
        const body = JSON.parse(await readRequestBody(req));
        const to = body.to;
        if (to !== 'browser' && to !== 'jvm') {
          return json(res, 400, { error: 'invalid target' });
        }
        const msg = { ...body, id: state.nextId++ };
        state.queues[to].push(msg);
        return json(res, 200, { ok: true, id: msg.id });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/poll') {
        const to = reqUrl.searchParams.get('to');
        const after = Number(reqUrl.searchParams.get('after') || '0');
        if (to !== 'browser' && to !== 'jvm') {
          return json(res, 400, { error: 'invalid target' });
        }
        const messages = state.queues[to].filter((m) => (m.id || 0) > after);
        return json(res, 200, { cursor: state.nextId - 1, messages });
      }

      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (peer !== 'browser' && peer !== 'jvm') {
          return json(res, 400, { error: 'invalid peer' });
        }
        state.results[peer] = JSON.parse(await readRequestBody(req));
        return json(res, 200, { ok: true });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/results') {
        return json(res, 200, state.results);
      }

      json(res, 404, { error: `Not found: ${reqUrl.pathname}` });
    } catch (err) {
      json(res, 500, { error: String(err?.stack || err) });
    }
  });
}

function spawnJvmSide(signalBase) {
  return new Promise((resolve, reject) => {
    const args = [
      ':nge-platform-interop-test:jvm:jvmTeaVMInteropJvmSide',
      `-PinteropSignalBase=${signalBase}`,
      '--console=plain',
    ];
    const child = spawn('./gradlew', args, { cwd: repoRoot, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    const outWriter = createLinePrefixWriter(process.stderr, '[jvm stdout] ');
    const errWriter = createLinePrefixWriter(process.stderr, '[jvm stderr] ');
    child.stdout.on('data', (d) => {
      const s = d.toString();
      stdout += s;
      outWriter.push(s);
    });
    child.stderr.on('data', (d) => {
      const s = d.toString();
      stderr += s;
      errWriter.push(s);
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      outWriter.flush();
      errWriter.flush();
      resolve({ code: code ?? -1, stdout, stderr });
    });
  });
}

async function setupNodeWebRTC() {
  if (typeof globalThis.RTCPeerConnection === 'function' && typeof globalThis.RTCIceCandidate === 'function') {
    return 'global';
  }

  const importWrtc = async () => import('wrtc');
  const tryRepairWrtcBinary = () => {
    const wrtcDir = path.join(projectDir, 'node_modules', 'wrtc');
    const binDir = path.join(projectDir, 'node_modules', '.bin');
    const env = {
      ...process.env,
      PATH: `${binDir}${path.delimiter}${process.env.PATH || ''}`,
    };
    const result = spawnSync('node', ['scripts/download-prebuilt.js'], {
      cwd: wrtcDir,
      env,
      stdio: 'inherit',
    });
    return result.status === 0;
  };

  let wrtcModule;
  try {
    wrtcModule = await importWrtc();
  } catch (error) {
    const message = String(error);
    const missingNative = message.includes('wrtc.node') || message.includes('build/Release/wrtc.node');
    if (!missingNative) {
      throw new Error(
        `Node WebRTC not available. Install wrtc@0.4.7 (pinned) or provide globals. Original error: ${message}`
      );
    }

    const repaired = tryRepairWrtcBinary();
    if (repaired) {
      try {
        wrtcModule = await importWrtc();
      } catch (retryError) {
        throw new Error(
          'Node WebRTC not available: wrtc native binding repair succeeded but import still failed. ' +
          `Original error: ${String(retryError)}`
        );
      }
    } else {
      throw new Error(
        'Node WebRTC not available: wrtc native binding was not installed and automatic prebuilt download failed. ' +
        'Ensure npm scripts are enabled (`npm install --ignore-scripts=false`) and ' +
        '`@mapbox/node-pre-gyp` is present, then run `npm rebuild wrtc --ignore-scripts=false`. ' +
        `Original error: ${message}`
      );
    }
  }

  const wrtc = wrtcModule.default ?? wrtcModule;
  if (typeof wrtc.RTCPeerConnection !== 'function' || typeof wrtc.RTCIceCandidate !== 'function') {
    throw new Error('wrtc loaded but does not expose RTCPeerConnection/RTCIceCandidate');
  }

  globalThis.RTCPeerConnection ??= wrtc.RTCPeerConnection;
  globalThis.RTCIceCandidate ??= wrtc.RTCIceCandidate;
  if (typeof wrtc.RTCSessionDescription === 'function') {
    globalThis.RTCSessionDescription ??= wrtc.RTCSessionDescription;
  }
  if (typeof wrtc.RTCDataChannel === 'function') {
    globalThis.RTCDataChannel ??= wrtc.RTCDataChannel;
  }
  return 'wrtc';
}

async function runNodeTeaVMPeer(signalBase, bundlePath) {
  const provider = await setupNodeWebRTC();
  const B = await import(pathToFileURL(bundlePath).href);

  const progress = [];
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  const received = [];
  const receivedTexts = [];
  const timeoutAt = Date.now() + 30000;
  let cursor = 0;
  let pcRemoteDescReady = false;
  const pendingIce = [];
  let remoteChannel = null;

  const step = (name) => {
    progress.push({ t: Date.now(), step: name });
  };

  const post = async (pathSuffix, body) => {
    const res = await fetch(`${signalBase}${pathSuffix}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      throw new Error(`POST ${pathSuffix} failed: ${res.status} ${await res.text()}`);
    }
    return res.json();
  };

  const get = async (pathSuffix) => {
    const res = await fetch(`${signalBase}${pathSuffix}`);
    if (!res.ok) {
      throw new Error(`GET ${pathSuffix} failed: ${res.status} ${await res.text()}`);
    }
    return res.json();
  };

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  const onceDataChannelOpen = (channel, timeoutMs) => {
    if (channel.readyState === 'open') {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout waiting for datachannel open')), timeoutMs);
      const onOpen = () => {
        clearTimeout(timeout);
        resolve();
      };
      if (typeof channel.addEventListener === 'function') {
        channel.addEventListener('open', onOpen, { once: true });
        return;
      }
      const prev = channel.onopen;
      channel.onopen = (ev) => {
        if (typeof prev === 'function') prev(ev);
        onOpen();
      };
    });
  };

  const pc = B.rtcCreatePeerConnection([]);
  step(`create responder peer via ${provider}`);
  pc.onconnectionstatechange = () => step(`pc connectionState=${pc.connectionState}`);
  pc.oniceconnectionstatechange = () => step(`pc iceConnectionState=${pc.iceConnectionState}`);
  pc.onicecandidate = (e) => {
    if (e.candidate) {
      post('/send', {
        to: 'jvm',
        type: 'ice',
        candidate: e.candidate.candidate || '',
        sdpMid: e.candidate.sdpMid || '',
      }).catch((err) => {
        step(`failed posting ICE: ${String(err)}`);
      });
    }
  };

  const remoteChannelPromise = new Promise((resolve) => {
    pc.ondatachannel = (ev) => {
      remoteChannel = ev.channel;
      step(`ondatachannel label=${remoteChannel.label}`);
      B.rtcSetOnMessageHandler(remoteChannel, (bytes) => {
        received.push(Array.from(bytes));
        const text = decoder.decode(bytes);
        receivedTexts.push(text);
      });
      resolve(remoteChannel);
    };
  });

  const flushPendingIce = async () => {
    while (pendingIce.length > 0) {
      const c = pendingIce.shift();
      try {
        await pc.addIceCandidate(c);
        step('added buffered remote ICE');
      } catch (error) {
        // libwebrtc/wrtc can reject candidate variants that browsers accept.
        // Keep parity with JVM side behavior by ignoring per-candidate failures.
        step(`ignored buffered remote ICE add failure: ${String(error)}`);
      }
    }
  };

  const handleSignalMessage = async (msg) => {
    if (msg.type === 'offer') {
      step('received offer');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      pcRemoteDescReady = true;
      await flushPendingIce();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await post('/send', { to: 'jvm', type: 'answer', sdp: answer.sdp });
      step('sent answer');
      return;
    }
    if (msg.type === 'ice') {
      // Avoid creating a candidate object with sdpMLineIndex:null because
      // wrtc's addIceCandidate expects a 32-bit integer when the field exists.
      const candidate = {
        candidate: msg.candidate,
        sdpMid: msg.sdpMid ?? undefined,
      };
      if (!pcRemoteDescReady) {
        pendingIce.push(candidate);
        return;
      }
      try {
        await pc.addIceCandidate(candidate);
        step('added remote ICE');
      } catch (error) {
        step(`ignored remote ICE add failure: ${String(error)}`);
      }
    }
  };

  try {
    step('polling for offer');
    while (!remoteChannel && Date.now() < timeoutAt) {
      const poll = await get(`/poll?to=browser&after=${cursor}`);
      cursor = poll.cursor || cursor;
      for (const msg of poll.messages || []) {
        await handleSignalMessage(msg);
      }
      if (!remoteChannel) {
        await sleep(50);
      }
    }

    if (!remoteChannel) {
      throw new Error('Timed out waiting for remote data channel');
    }

    const channel = await remoteChannelPromise;
    await onceDataChannelOpen(channel, 15000);
    step('channel open');

    await post('/send', { to: 'jvm', type: 'meta', name: 'remoteLabel', value: channel.label || '' });
    await post('/send', { to: 'jvm', type: 'meta', name: 'protocol', value: channel.protocol || '' });
    await post('/send', { to: 'jvm', type: 'browser-ready' });

    for (let i = 0; i < STRESS_MESSAGES; i += 1) {
      channel.send(encoder.encode(`browser-seq:${i}`));
    }
    step('sent node stress burst');

    const msgDeadline = Date.now() + 60000;
    while (Date.now() < msgDeadline) {
      if (receivedTexts.length >= STRESS_MESSAGES) {
        break;
      }
      const poll = await get(`/poll?to=browser&after=${cursor}`);
      cursor = poll.cursor || cursor;
      for (const msg of poll.messages || []) {
        await handleSignalMessage(msg);
      }
      await sleep(50);
    }

    if (receivedTexts.length < STRESS_MESSAGES) {
      throw new Error(`Timed out waiting for jvm stress burst (${receivedTexts.length}/${STRESS_MESSAGES})`);
    }
    for (let i = 0; i < STRESS_MESSAGES; i += 1) {
      const expected = `jvm-seq:${i}`;
      const actual = receivedTexts[i];
      if (actual !== expected) {
        throw new Error(`Out-of-order RTC message from JVM: expected=${expected} actual=${actual}`);
      }
    }

    const result = {
      ok: true,
      provider,
      progress,
      stats: {
        label: channel.label || null,
        protocol: channel.protocol || null,
        ordered: B.rtcDataChannelIsOrdered(channel),
        reliable: B.rtcDataChannelIsReliable(channel),
        maxMessageSize: B.rtcGetMaxMessageSize(pc),
        received,
      },
    };

    await post('/result/browser', {
      ok: true,
      provider,
      label: channel.label || '',
      protocol: channel.protocol || '',
      browserToJvmStressCount: STRESS_MESSAGES,
      jvmToBrowserStressCount: STRESS_MESSAGES,
    });

    // NOTE: wrtc@0.4.7 can segfault during explicit close/teardown on some
    // environments even after successful exchange. We avoid manual closes
    // here and terminate the harness process explicitly in main().
    return result;
  } catch (error) {
    const message = String(error?.stack || error);
    try {
      await post('/send', { to: 'jvm', type: 'browser-failed', error: message });
    } catch (_postError) {}
    try {
      await post('/result/browser', { ok: false, error: message, progress });
    } catch (_postError) {}
    throw error;
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
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const addr = server.address();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const signalBase = `http://127.0.0.1:${port}/signal`;

  const jvmPromise = spawnJvmSide(signalBase);
  let nodeOut = null;
  let nodeError = null;
  try {
    nodeOut = await runNodeTeaVMPeer(signalBase, bundlePath);
  } catch (e) {
    nodeError = e;
  }
  const jvmOut = await jvmPromise;

  if (nodeError) {
    throw nodeError;
  }

  const combined = {
    ok: Boolean(state.results.browser?.ok) && Boolean(state.results.jvm?.ok) && jvmOut.code === 0,
    node: state.results.browser ?? nodeOut ?? null,
    jvm: state.results.jvm ?? null,
    jvmExitCode: jvmOut.code,
  };

  process.stdout.write(`${JSON.stringify(combined, null, 2)}\n`);
  await new Promise((resolve) => server.close(resolve));
  // Exit immediately to avoid wrtc teardown crashes after successful run.
  process.exit(combined.ok ? 0 : 1);
}

main().catch((err) => {
  process.stderr.write(`${String(err?.stack || err)}\n`);
  process.exit(1);
});
