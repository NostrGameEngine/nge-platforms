import * as B from '/org/ngengine/platform/teavm/TeaVMBinds.bundle.js';

const resultEl = document.getElementById('result');
const progress = [];

function setResult(obj) {
  resultEl.textContent = JSON.stringify({ progress, ...obj }, null, 2);
}

function fail(error, extra = {}) {
  const message = error instanceof Error ? `${error.message}\n${error.stack || ''}` : String(error);
  setResult({ ok: false, error: message, ...extra });
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function step(name) {
  progress.push({ t: Date.now(), step: name });
  resultEl.textContent = JSON.stringify({ ok: null, progress }, null, 2);
}

function withTimeout(promise, label, timeoutMs) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`Timeout: ${label}`)), timeoutMs)),
  ]);
}

function onceEvent(target, eventName, timeoutMs, mapper = (e) => e) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      target.removeEventListener(eventName, onEvent);
      reject(new Error(`Timeout waiting for ${eventName}`));
    }, timeoutMs);
    function onEvent(e) {
      clearTimeout(timeout);
      target.removeEventListener(eventName, onEvent);
      try {
        resolve(mapper(e));
      } catch (err) {
        reject(err);
      }
    }
    target.addEventListener(eventName, onEvent);
  });
}

function waitDataChannelOpen(channel, timeoutMs) {
  if (channel.readyState === 'open') {
    return Promise.resolve();
  }
  return onceEvent(channel, 'open', timeoutMs).then(() => undefined);
}

async function main() {
  const timeoutMs = 15000;
  step('create peer connections');
  const pc1 = B.rtcCreatePeerConnection([]);
  const pc2 = B.rtcCreatePeerConnection([]);

  const cleanup = () => {
    try { pc1.close(); } catch (_) {}
    try { pc2.close(); } catch (_) {}
  };

  let pc1RemoteDescReady = false;
  let pc2RemoteDescReady = false;
  const pendingForPc1 = [];
  const pendingForPc2 = [];

  async function flushPending(targetPc, queue, label) {
    while (queue.length > 0) {
      const candidate = queue.shift();
      try {
        await targetPc.addIceCandidate(candidate);
        step(`${label} addIceCandidate ok`);
      } catch (e) {
        step(`${label} addIceCandidate failed`);
        throw e;
      }
    }
  }

  pc1.onconnectionstatechange = () => step(`pc1 connectionState=${pc1.connectionState}`);
  pc2.onconnectionstatechange = () => step(`pc2 connectionState=${pc2.connectionState}`);
  pc1.oniceconnectionstatechange = () => step(`pc1 iceConnectionState=${pc1.iceConnectionState}`);
  pc2.oniceconnectionstatechange = () => step(`pc2 iceConnectionState=${pc2.iceConnectionState}`);

  pc1.onicecandidate = (e) => {
    if (e.candidate) {
      step(`pc1 local candidate ${e.candidate.candidate || ''}`);
    } else {
      step('pc1 ice gathering complete');
    }
    if (e.candidate) {
      if (!pc2RemoteDescReady) {
        pendingForPc2.push(e.candidate);
      } else {
        pc2.addIceCandidate(e.candidate).then(
          () => step('pc2 addIceCandidate ok'),
          () => step('pc2 addIceCandidate failed')
        );
      }
    }
  };
  pc2.onicecandidate = (e) => {
    if (e.candidate) {
      step(`pc2 local candidate ${e.candidate.candidate || ''}`);
    } else {
      step('pc2 ice gathering complete');
    }
    if (e.candidate) {
      if (!pc1RemoteDescReady) {
        pendingForPc1.push(e.candidate);
      } else {
        pc1.addIceCandidate(e.candidate).then(
          () => step('pc1 addIceCandidate ok'),
          () => step('pc1 addIceCandidate failed')
        );
      }
    }
  };

  const received = [];
  let remoteChannel = null;
  const remoteChannelPromise = new Promise((resolve) => {
    pc2.ondatachannel = (e) => {
      step('pc2 ondatachannel');
      remoteChannel = e.channel;
      resolve(e.channel);
    };
  });

  const localChannel = B.rtcCreateDataChannel(
    pc1,
    'nostr4j-browser-harness',
    'harness-proto',
    false,
    false,
    2,
    -1
  );
  step('created local data channel');

  assert(B.rtcDataChannelGetProtocol(localChannel) === 'harness-proto', 'protocol getter mismatch');
  assert(B.rtcDataChannelIsOrdered(localChannel) === false, 'ordered getter mismatch');
  assert(B.rtcDataChannelIsReliable(localChannel) === false, 'reliable getter mismatch');
  assert(B.rtcDataChannelGetMaxRetransmits(localChannel) === 2, 'maxRetransmits getter mismatch');

  B.rtcSetOnMessageHandler(localChannel, (bytes) => {
    received.push({ side: 'local', bytes: Array.from(bytes) });
  });

  step('create offer');
  const offer = await withTimeout(pc1.createOffer(), 'pc1.createOffer', timeoutMs);
  step('pc1 setLocalDescription');
  await withTimeout(pc1.setLocalDescription(offer), 'pc1.setLocalDescription', timeoutMs);
  step('pc2 setRemoteDescription');
  await withTimeout(pc2.setRemoteDescription(offer), 'pc2.setRemoteDescription(offer)', timeoutMs);
  pc2RemoteDescReady = true;
  await flushPending(pc2, pendingForPc2, 'pc2');

  step('create answer');
  const answer = await withTimeout(pc2.createAnswer(), 'pc2.createAnswer', timeoutMs);
  step('pc2 setLocalDescription');
  await withTimeout(pc2.setLocalDescription(answer), 'pc2.setLocalDescription', timeoutMs);
  step('pc1 setRemoteDescription');
  await withTimeout(pc1.setRemoteDescription(answer), 'pc1.setRemoteDescription(answer)', timeoutMs);
  pc1RemoteDescReady = true;
  await flushPending(pc1, pendingForPc1, 'pc1');

  step('wait remote channel');
  const remote = await Promise.race([
    remoteChannelPromise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout waiting for remote channel')), timeoutMs)),
  ]);
  step('remote channel received');

  B.rtcSetOnMessageHandler(remote, (bytes) => {
    received.push({ side: 'remote', bytes: Array.from(bytes) });
  });

  step('wait open events');
  await Promise.all([
    waitDataChannelOpen(localChannel, timeoutMs),
    waitDataChannelOpen(remote, timeoutMs),
  ]);
  step('channels open');

  B.rtcDataChannelSetBufferedAmountLowThreshold(localChannel, 64);
  const bufferedAmount = B.rtcDataChannelGetBufferedAmount(localChannel);
  const availableAmount = B.rtcDataChannelGetAvailableAmount(pc1, localChannel);
  const maxMessageSize = B.rtcGetMaxMessageSize(pc1);

  assert(typeof bufferedAmount === 'number', 'bufferedAmount helper must return a number');
  assert(typeof availableAmount === 'number', 'availableAmount helper must return a number');
  assert(typeof maxMessageSize === 'number', 'maxMessageSize helper must return a number');

  localChannel.send(new Uint8Array([1, 2, 3, 4]));
  remote.send(new Uint8Array([5, 6, 7]));
  step('messages sent');

  const deadline = Date.now() + timeoutMs;
  while (received.length < 2 && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 20));
  }
  assert(received.length >= 2, 'Timed out waiting for bidirectional messages');
  step('messages received');

  cleanup();

  setResult({
    ok: true,
    stats: {
      bufferedAmount,
      availableAmount,
      maxMessageSize,
      received,
      remoteLabel: remoteChannel?.label || remote?.label || null,
      localLabel: localChannel.label,
    },
  });
}

main().catch((e) => {
  try {
    fail(e);
  } catch (_) {
    resultEl.textContent = JSON.stringify({ ok: false, error: String(e) });
  }
});
