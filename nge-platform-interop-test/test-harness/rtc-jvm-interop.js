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
  if (!res.ok) {
    throw new Error(`POST ${path} failed: ${res.status} ${await res.text()}`);
  }
  return res.json();
}

async function get(path) {
  const res = await fetch(`${signalBase}${path}`);
  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${await res.text()}`);
  }
  return res.json();
}

function onceDataChannelOpen(channel, timeoutMs) {
  if (channel.readyState === 'open') {
    return Promise.resolve();
  }
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
  if (!signalBase) {
    throw new Error('Missing signalBase query parameter');
  }
  const timeoutAt = Date.now() + 30000;
  let cursor = 0;
  let pcRemoteDescReady = false;
  const pendingIce = [];

  step('create responder peer');
  const pc = B.rtcCreatePeerConnection([]);

  pc.onconnectionstatechange = () => step(`pc connectionState=${pc.connectionState}`);
  pc.oniceconnectionstatechange = () => step(`pc iceConnectionState=${pc.iceConnectionState}`);
  pc.onicecandidate = (e) => {
    if (e.candidate) {
      post('/send', {
        to: 'jvm',
        type: 'ice',
        candidate: e.candidate.candidate || '',
        sdpMid: e.candidate.sdpMid || '',
      }).catch((err) => fail(err));
    }
  };

  let remoteChannel = null;
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  const received = [];
  const remoteChannelPromise = new Promise((resolve) => {
    pc.ondatachannel = (ev) => {
      remoteChannel = ev.channel;
      step(`ondatachannel label=${remoteChannel.label}`);
      B.rtcSetOnMessageHandler(remoteChannel, (bytes) => {
        received.push(Array.from(bytes));
        const text = decoder.decode(bytes);
        step(`received message ${text}`);
        if (text === 'ping-from-jvm') {
          remoteChannel.send(encoder.encode('pong-from-browser'));
        }
      });
      resolve(remoteChannel);
    };
  });

  async function flushPendingIce() {
    while (pendingIce.length > 0) {
      const c = pendingIce.shift();
      await pc.addIceCandidate(c);
      step('added buffered remote ICE');
    }
  }

  async function handleSignalMessage(msg) {
    if (msg.type === 'offer') {
      step('received offer');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      pcRemoteDescReady = true;
      await flushPendingIce();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await post('/send', { to: 'jvm', type: 'answer', sdp: answer.sdp });
      step('sent answer');
    } else if (msg.type === 'ice') {
      const c = { candidate: msg.candidate, sdpMid: msg.sdpMid };
      if (!pcRemoteDescReady) {
        pendingIce.push(c);
        return;
      }
      await pc.addIceCandidate(c);
      step('added remote ICE');
    }
  }

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

  if (!remoteChannel && Date.now() >= timeoutAt) {
    throw new Error('Timed out waiting for remote data channel');
  }
  const channel = await remoteChannelPromise;
  await onceDataChannelOpen(channel, 15000);
  step('channel open');

  await post('/send', { to: 'jvm', type: 'meta', name: 'remoteLabel', value: channel.label || '' });
  await post('/send', { to: 'jvm', type: 'meta', name: 'protocol', value: channel.protocol || '' });
  await post('/send', { to: 'jvm', type: 'browser-ready' });

  const msgDeadline = Date.now() + 15000;
  while (Date.now() < msgDeadline) {
    if (received.some((x) => decoder.decode(new Uint8Array(x)) === 'ping-from-jvm')) {
      break;
    }
    const poll = await get(`/poll?to=browser&after=${cursor}`);
    cursor = poll.cursor || cursor;
    for (const msg of poll.messages || []) {
      await handleSignalMessage(msg);
    }
    await sleep(50);
  }

  if (!received.some((x) => decoder.decode(new Uint8Array(x)) === 'ping-from-jvm')) {
    throw new Error('Timed out waiting for ping-from-jvm');
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
      await post('/send', { to: 'jvm', type: 'browser-failed', error: String(e?.stack || e) });
      await post('/result/browser', { ok: false, error: String(e?.stack || e) });
    }
  } catch (_) {
    resultEl.textContent = JSON.stringify({ ok: false, error: String(e) }, null, 2);
  }
});
