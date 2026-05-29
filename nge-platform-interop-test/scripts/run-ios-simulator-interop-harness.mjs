import http from 'node:http';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { emitInteropAnnotation, firstFailureText } from './ci-annotations.mjs';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const state = { ios: null };
const INTEROP_TITLE = 'Interop: iOS Simulator <-> Node Harness';
const buildTask = ':nge-platform-interop-test:ios:buildIosSimulatorApp';
const runTask = ':nge-platform-interop-test:ios:runIosSimulatorInteropHarness';
const iosSimulatorDevice = process.env.IOS_SIMULATOR_DEVICE || process.env.IOS_SIMULATOR_UDID || '';
const iosResultTimeoutMs = Number.parseInt(process.env.IOS_INTEROP_RESULT_TIMEOUT_MS || `${8 * 60 * 1000}`, 10);

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
      if (method === 'POST' && reqUrl.pathname === '/signal/result/ios') {
        state.ios = JSON.parse(await readBody(req));
        return json(res, 200, { ok: true });
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
      if (method === 'GET' && reqUrl.pathname === '/signal/results') return json(res, 200, state);
      return json(res, 404, { error: `Not found: ${reqUrl.pathname}` });
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

async function main() {
  const simulatorArgs = iosSimulatorDevice ? [`-PiosSimulatorDevice=${iosSimulatorDevice}`] : [];
  const buildOut = await spawnCapture('./gradlew', [buildTask, '--console=plain', ...simulatorArgs], {
    cwd: repoRoot,
    label: 'ios-build',
  });
  if (buildOut.code !== 0) {
    throw new Error(`iOS simulator app build failed with exit code ${buildOut.code}\n${buildOut.stderr || buildOut.stdout}`);
  }

  const server = makeServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const signalBase = `http://127.0.0.1:${port}/signal`;

  const gradlePromise = spawnCapture(
    './gradlew',
    [runTask, '-x', buildTask, '--console=plain', ...simulatorArgs],
    {
      cwd: repoRoot,
      label: 'ios',
      env: {
        IOS_INTEROP_SIGNAL_BASE: signalBase,
        IOS_INTEROP_KIND: 'smoke',
      },
      timeoutMs: iosResultTimeoutMs + 30_000,
    }
  );

  let iosResult = null;
  let resultError = null;
  try {
    iosResult = await Promise.race([
      waitForIosResult(iosResultTimeoutMs),
      gradlePromise.then((out) => {
        if (!state.ios) {
          throw new Error(
            `iOS simulator Gradle task exited before publishing a result (exit code ${out.code}).\n${out.stderr || out.stdout}`
          );
        }
        return state.ios;
      }),
    ]);
  } catch (e) {
    resultError = e;
  }

  const gradleOut = await gradlePromise;
  server.close();

  if (resultError) throw resultError;
  const checks = [];
  if (!iosResult?.ok) checks.push(`iOS result failed: ${iosResult?.error || 'missing result'}`);
  if (iosResult?.platformName !== 'iOS') checks.push(`platformName mismatch: ${iosResult?.platformName}`);
  if (iosResult?.signatureLen !== 128) checks.push(`signatureLen mismatch: ${iosResult?.signatureLen}`);
  if (iosResult?.signatureVerified !== true) checks.push('signatureVerified != true');
  if (iosResult?.asyncValue !== 'ios-async-ok') checks.push(`asyncValue mismatch: ${iosResult?.asyncValue}`);
  if (iosResult?.randomLen !== 8 || iosResult?.randomNonZero !== true) checks.push('random invariant failed');
  if (iosResult?.httpStatusCode !== 201 || iosResult?.httpBody !== 'echo:ios-http-body|req:ios') checks.push('HTTP host<->simulator interop failed');

  const out = {
    ok: gradleOut.code === 0 && checks.length === 0,
    ios: iosResult,
    gradleExitCode: gradleOut.code,
    checks,
    coverage: [
      'iOS simulator app build through libJGLIOS/GraalVM',
      'iOS simulator launch through simctl',
      'iOS NGEPlatform crypto/random/async smoke',
      'iOS simulator HTTP interoperability with local Node harness',
    ],
  };
  process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  emitInteropAnnotation(
    INTEROP_TITLE,
    out.ok,
    out.ok ? 'iOS simulator built, launched, and exchanged HTTP data with the Node harness.' : firstFailureText(checks.join('; '), iosResult?.error)
  );
  if (!out.ok) process.exit(1);
}

main().catch((err) => {
  emitInteropAnnotation(INTEROP_TITLE, false, firstFailureText(err?.stack || err?.message || err));
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
