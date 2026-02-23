import http from 'node:http';
import { spawn } from 'node:child_process';

const repoRoot = new URL('../../', import.meta.url).pathname;

const state = {
  nextId: 1,
  queues: { jvm: [], android: [] },
  results: { jvm: null, android: null },
};

function resetState() {
  state.nextId = 1;
  state.queues = { jvm: [], android: [] };
  state.results = { jvm: null, android: null };
}

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

      if (method === 'POST' && reqUrl.pathname === '/signal/send') {
        const body = JSON.parse(await readBody(req));
        const to = body.to;
        if (to !== 'jvm' && to !== 'android') return json(res, 400, { error: 'invalid target' });
        const msg = { ...body, id: state.nextId++ };
        state.queues[to].push(msg);
        return json(res, 200, { ok: true, id: msg.id });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/poll') {
        const to = reqUrl.searchParams.get('to');
        const after = Number(reqUrl.searchParams.get('after') || '0');
        if (to !== 'jvm' && to !== 'android') return json(res, 400, { error: 'invalid target' });
        const messages = state.queues[to].filter((m) => (m.id || 0) > after);
        return json(res, 200, { cursor: state.nextId - 1, messages });
      }

      if (method === 'POST' && reqUrl.pathname.startsWith('/signal/result/')) {
        const peer = reqUrl.pathname.split('/').pop();
        if (peer !== 'jvm' && peer !== 'android') return json(res, 400, { error: 'invalid peer' });
        state.results[peer] = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
      }

      if (method === 'GET' && reqUrl.pathname === '/signal/results') {
        return json(res, 200, state.results);
      }

      json(res, 404, { error: 'not found' });
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
    child.stdout.on('data', (d) => {
      const s = d.toString();
      stdout += s;
      outWriter?.push(s);
    });
    child.stderr.on('data', (d) => {
      const s = d.toString();
      stderr += s;
      errWriter?.push(s);
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      outWriter?.flush();
      errWriter?.flush();
      resolve({ code: code ?? -1, stdout, stderr });
    });
  });
}

async function runOnce(attempt) {
  resetState();
  const server = makeServer();
  await new Promise((resolve) => server.listen(0, '0.0.0.0', resolve));
  const addr = server.address();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const jvmSignalBase = `http://127.0.0.1:${port}/signal`;
  const androidSignalBase = `http://10.0.2.2:${port}/signal`;

  const jvmPromise = spawnCapture('./gradlew', [
    ':nge-platform-interop-test:jvm:jvmAndroidInteropJvmSide',
    `-PinteropSignalBase=${jvmSignalBase}`,
    '--console=plain',
  ], { cwd: repoRoot, label: 'jvm' });

  const androidPromise = spawnCapture('./gradlew', [
    ':nge-platform-interop-test:android:androidRtcEmulatorHarness',
    '--console=plain',
  ], {
    cwd: repoRoot,
    label: 'android',
    env: {
      ANDROID_RTC_TEST_FILTER: 'org.ngengine.platform.android.AndroidJVMRtcInteropInstrumentedTest',
      ANDROID_RTC_SIGNAL_BASE: androidSignalBase,
      ANDROID_AVD_NAME: process.env.ANDROID_AVD_NAME || 'Generic_AOSP',
    },
  });

  const timeoutMs = 180000;
  let timeoutId = null;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error(`Interop harness timed out after ${timeoutMs}ms`)), timeoutMs);
    timeoutId.unref?.();
  });

  let jvmOut;
  let androidOut;
  try {
    [jvmOut, androidOut] = await Promise.race([Promise.all([jvmPromise, androidPromise]), timeout]);
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
    server.close();
  }

  const combined = {
    attempt,
    ok: Boolean(state.results.jvm?.ok) && Boolean(state.results.android?.ok) && jvmOut.code === 0 && androidOut.code === 0,
    jvm: state.results.jvm,
    android: state.results.android,
    jvmExitCode: jvmOut.code,
    androidExitCode: androidOut.code,
  };

  return combined;
}

function isConnectedTimeoutFlake(result) {
  const texts = [
    result?.jvm?.error,
    result?.android?.error,
  ].filter(Boolean).join('\n');
  return /RTC connected event missing|Timed out waiting for RTC connected event/i.test(texts);
}

async function main() {
  const attempts = 2;
  let last = null;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    last = await runOnce(attempt);
    if (last.ok) {
      process.stdout.write(`${JSON.stringify(last, null, 2)}\n`);
      return;
    }
    if (attempt < attempts && isConnectedTimeoutFlake(last)) {
      process.stderr.write(`Retrying JVM<->Android interop after transient RTC connected timeout (attempt ${attempt}/${attempts})\n`);
      continue;
    }
    break;
  }
  process.stdout.write(`${JSON.stringify(last, null, 2)}\n`);
  process.exit(1);
}

main().catch((err) => {
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
