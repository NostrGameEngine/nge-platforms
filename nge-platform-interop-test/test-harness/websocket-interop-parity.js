const resultEl = document.getElementById('result');
const setResult = (obj) => { resultEl.textContent = JSON.stringify(obj, null, 2); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function qs(name) {
  return new URLSearchParams(location.search).get(name);
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${url} failed: ${res.status}`);
}

function waitLatch(timeoutMs, label, installer) {
  return new Promise((resolve, reject) => {
    let done = false;
    const t = setTimeout(() => {
      if (!done) {
        done = true;
        reject(new Error(`Timed out waiting for ${label}`));
      }
    }, timeoutMs);
    installer(
      (v) => {
        if (done) return;
        done = true;
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        if (done) return;
        done = true;
        clearTimeout(t);
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    );
  });
}

async function runServerClosePhase(wsUrl) {
  const out = {
    openCount: 0,
    connectedAfterOpen: false,
    welcome: '',
    echo: '',
    serverCloseReason: '',
    clientCloseCount: 0,
    connectedAfterServerClose: false,
    error: '',
  };
  let ws;
  let closeResolve;
  const closePromise = new Promise((r) => { closeResolve = r; });
  const inbox = [];
  let inboxWaiter = null;
  let error = null;

  const nextMessage = (label) => {
    if (inbox.length) return Promise.resolve(inbox.shift());
    return waitLatch(10000, label, (res) => { inboxWaiter = res; });
  };

  ws = new WebSocket(wsUrl);
  ws.onopen = () => {
    out.openCount += 1;
    out.connectedAfterOpen = ws.readyState === WebSocket.OPEN;
  };
  ws.onmessage = (ev) => {
    if (inboxWaiter) {
      const r = inboxWaiter;
      inboxWaiter = null;
      r(String(ev.data));
    } else {
      inbox.push(String(ev.data));
    }
  };
  ws.onerror = () => {
    error = error || new Error('browser websocket error');
    out.error = String(error);
  };
  ws.onclose = (ev) => {
    out.serverCloseReason = ev.reason || '';
    out.connectedAfterServerClose = ws.readyState === WebSocket.OPEN;
    closeResolve();
  };

  await waitLatch(10000, 'browser websocket open', (res, rej) => {
    const iv = setInterval(() => {
      if (error) { clearInterval(iv); rej(error); return; }
      if (out.openCount > 0) { clearInterval(iv); res(null); }
    }, 20);
  });
  out.welcome = await nextMessage('browser welcome');
  ws.send('echo:server-phase');
  out.echo = await nextMessage('browser echo');
  ws.send('close-by-server');
  await Promise.race([closePromise, sleep(10000).then(() => { throw new Error('Timed out waiting for server close'); })]);
  if (error) throw error;
  return out;
}

async function runClientClosePhase(wsUrl) {
  const out = {
    openCount: 0,
    connectedAfterOpen: false,
    welcome: '',
    echo: '',
    clientCloseCount: 0,
    clientCloseReason: '',
    serverCloseCount: 0,
    connectedAfterClientClose: false,
    error: '',
  };
  let ws;
  let closeResolve;
  const closePromise = new Promise((r) => { closeResolve = r; });
  const inbox = [];
  let inboxWaiter = null;
  let error = null;
  let initiatedClientClose = false;

  const nextMessage = (label) => {
    if (inbox.length) return Promise.resolve(inbox.shift());
    return waitLatch(10000, label, (res) => { inboxWaiter = res; });
  };

  ws = new WebSocket(wsUrl);
  ws.onopen = () => {
    out.openCount += 1;
    out.connectedAfterOpen = ws.readyState === WebSocket.OPEN;
  };
  ws.onmessage = (ev) => {
    if (inboxWaiter) {
      const r = inboxWaiter;
      inboxWaiter = null;
      r(String(ev.data));
    } else {
      inbox.push(String(ev.data));
    }
  };
  ws.onerror = () => {
    error = error || new Error('browser websocket error');
    out.error = String(error);
  };
  ws.onclose = () => {
    if (!initiatedClientClose) out.serverCloseCount += 1;
    out.connectedAfterClientClose = ws.readyState === WebSocket.OPEN;
    closeResolve();
  };

  await waitLatch(10000, 'browser client phase open', (res, rej) => {
    const iv = setInterval(() => {
      if (error) { clearInterval(iv); rej(error); return; }
      if (out.openCount > 0) { clearInterval(iv); res(null); }
    }, 20);
  });
  out.welcome = await nextMessage('browser client welcome');
  ws.send('echo:client-phase');
  out.echo = await nextMessage('browser client echo');
  initiatedClientClose = true;
  out.clientCloseCount += 1;
  out.clientCloseReason = 'client-close';
  ws.close(1000, 'client-close');
  await Promise.race([closePromise, sleep(10000).then(() => { throw new Error('Timed out waiting for client close'); })]);
  if (error) throw error;
  return out;
}

async function main() {
  const signalBase = qs('signalBase');
  const wsUrl = qs('wsUrl');
  if (!signalBase || !wsUrl) throw new Error('Missing signalBase/wsUrl');

  try {
    const out = {
      serverClosePhase: await runServerClosePhase(wsUrl),
      clientClosePhase: await runClientClosePhase(wsUrl),
      ok: true,
    };
    setResult(out);
    await postJson(`${signalBase}/result/browser`, out);
  } catch (e) {
    const out = { ok: false, error: e?.stack || e?.message || String(e) };
    setResult(out);
    try { await postJson(`${signalBase}/result/browser`, out); } catch {}
  }
}

main();
