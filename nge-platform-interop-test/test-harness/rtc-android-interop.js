import * as B from '/org/ngengine/platform/teavm/TeaVMBinds.bundle.js';

const resultEl = document.getElementById('result');
const progress = [];
const url = new URL(window.location.href);
const signalBase = url.searchParams.get('signalBase');

function setResult(obj) {
  resultEl.textContent = JSON.stringify({ progress, ...obj }, null, 2);
}

function step(name) {
  progress.push({ t: Date.now(), step: name });
  resultEl.textContent = JSON.stringify({ ok: null, progress }, null, 2);
}

function fail(error) {
  const message = error instanceof Error ? `${error.message}\n${error.stack || ''}` : String(error);
  setResult({ ok: false, error: message });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function post(path, body) {
  const res = await fetch(`${signalBase}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${await res.text()}`);
  return res.json();
}

async function get(path) {
  const res = await fetch(`${signalBase}${path}`);
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status} ${await res.text()}`);
  return res.json();
}

function onceDataChannelOpen(channel, timeoutMs) {
  if (channel.readyState === 'open') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timeout waiting for datachannel open')), timeoutMs);
    const onOpen = () => {
      clearTimeout(timeout);
      channel.removeEventListener('open', onOpen);
      resolve();
    };
    channel.addEventListener('open', onOpen);
  });
}

async function main() {
  if (!signalBase) throw new Error('Missing signalBase query parameter');

  const timeoutMs = 120000;
  let cursor = 0;
  let remoteDescSet = false;
  const pendingIce = [];
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  const received = [];

  step('create initiator peer');
  const pc = B.rtcCreatePeerConnection(['stun:stun.l.google.com:19302']);
  pc.onconnectionstatechange = () => step(`pc connectionState=${pc.connectionState}`);
  pc.oniceconnectionstatechange = () => step(`pc iceConnectionState=${pc.iceConnectionState}`);
  pc.onicecandidate = (e) => {
    if (e.candidate) {
      post('/send', {
        to: 'android',
        type: 'ice',
        candidate: e.candidate.candidate || '',
        sdpMid: e.candidate.sdpMid || '',
      }).catch((err) => fail(err));
    }
  };

  const channel = B.rtcCreateDataChannel(pc, 'nostr4j-browser-android-interop', 'browser-android-proto', true, true, -1, -1);
  B.rtcSetOnMessageHandler(channel, (bytes) => {
    received.push(Array.from(bytes));
    const text = decoder.decode(bytes);
    step(`received message ${text}`);
  });

  step('create offer');
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  await post('/send', { to: 'android', type: 'offer', sdp: offer.sdp });
  step('sent offer');

  async function flushPendingIce() {
    while (pendingIce.length > 0) {
      await pc.addIceCandidate(pendingIce.shift());
      step('added buffered remote ICE');
    }
  }

  let androidReady = false;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const poll = await get(`/poll?to=browser&after=${cursor}`);
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) {
      if (msg.type === 'answer') {
        await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
        remoteDescSet = true;
        await flushPendingIce();
        step('applied answer');
      } else if (msg.type === 'ice') {
        const c = { candidate: msg.candidate, sdpMid: msg.sdpMid };
        if (!remoteDescSet) pendingIce.push(c);
        else {
          try {
            await pc.addIceCandidate(c);
            step('added remote ICE');
          } catch (_) {
            // Ignore incompatible candidates.
          }
        }
      } else if (msg.type === 'android-ready') {
        androidReady = true;
      } else if (msg.type === 'meta') {
        // observed in final result via /result/android; no-op here
      } else if (msg.type === 'android-failed') {
        throw new Error(`Android harness failed: ${msg.error}`);
      }
    }

    if (pc.connectionState === 'connected' || pc.iceConnectionState === 'connected') {
      break;
    }
    await sleep(50);
  }

  await onceDataChannelOpen(channel, 30000);
  step('channel open');

  await post('/send', { to: 'android', type: 'meta', name: 'browserLabel', value: channel.label || '' });
  await post('/send', { to: 'android', type: 'browser-ready' });

  const readyDeadline = Date.now() + 30000;
  while (!androidReady && Date.now() < readyDeadline) {
    const poll = await get(`/poll?to=browser&after=${cursor}`);
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) {
      if (msg.type === 'android-ready') androidReady = true;
      if (msg.type === 'ice') {
        const c = { candidate: msg.candidate, sdpMid: msg.sdpMid };
        try { await pc.addIceCandidate(c); } catch (_) {}
      }
      if (msg.type === 'android-failed') throw new Error(`Android harness failed: ${msg.error}`);
    }
    if (!androidReady) await sleep(50);
  }
  if (!androidReady) throw new Error('Timed out waiting for android-ready');

  channel.send(encoder.encode('ping-from-browser'));
  step('sent ping');

  const msgDeadline = Date.now() + 60000;
  while (Date.now() < msgDeadline) {
    if (received.some((x) => decoder.decode(new Uint8Array(x)) === 'pong-from-android')) break;
    const poll = await get(`/poll?to=browser&after=${cursor}`);
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) {
      if (msg.type === 'ice') {
        const c = { candidate: msg.candidate, sdpMid: msg.sdpMid };
        try { await pc.addIceCandidate(c); } catch (_) {}
      }
      if (msg.type === 'android-failed') throw new Error(`Android harness failed: ${msg.error}`);
    }
    await sleep(50);
  }

  if (!received.some((x) => decoder.decode(new Uint8Array(x)) === 'pong-from-android')) {
    throw new Error('Timed out waiting for pong-from-android');
  }

  setResult({
    ok: true,
    stats: {
      label: channel.label || null,
      protocol: channel.protocol || null,
      ordered: B.rtcDataChannelIsOrdered(channel),
      reliable: B.rtcDataChannelIsReliable(channel),
      maxMessageSize: B.rtcGetMaxMessageSize(pc),
      received,
    },
  });
  await post('/result/browser', {
    ok: true,
    label: channel.label || '',
    protocol: channel.protocol || '',
  });

  try { channel.close(); } catch (_) {}
  try { pc.close(); } catch (_) {}
}

main().catch(async (e) => {
  try {
    fail(e);
    if (signalBase) {
      await post('/send', { to: 'android', type: 'browser-failed', error: String(e?.stack || e) });
      await post('/result/browser', { ok: false, error: String(e?.stack || e) });
    }
  } catch (_) {
    resultEl.textContent = JSON.stringify({ ok: false, error: String(e) }, null, 2);
  }
});
