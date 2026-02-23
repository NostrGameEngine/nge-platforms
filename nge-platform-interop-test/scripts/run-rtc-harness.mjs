import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const teavmDir = path.resolve(projectDir, '..', 'nge-platform-teavm');
const rootDirs = [
  path.join(projectDir, 'test-harness'),
  path.join(teavmDir, 'src', 'main', 'resources'),
];

function mimeType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.json')) return 'application/json; charset=utf-8';
  if (file.endsWith('.txt')) return 'text/plain; charset=utf-8';
  return 'application/octet-stream';
}

function resolvePath(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]).replace(/^\/+/, '');
  for (const base of rootDirs) {
    const candidate = path.resolve(base, clean.startsWith('test-harness/') ? clean.replace(/^test-harness\//, '') : clean);
    if (candidate.startsWith(base) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return candidate;
    }
  }
  return null;
}

async function runBrowserHarness(url) {
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

  page.on('console', (msg) => {
    consoleMessages.push(`[${msg.type()}] ${msg.text()}`);
  });
  page.on('pageerror', (err) => {
    pageErrors.push(err.stack || err.message || String(err));
  });
  page.on('requestfailed', (req) => {
    const failure = req.failure();
    consoleMessages.push(
      `[requestfailed] ${req.url()} ${failure?.errorText || 'unknown'}`
    );
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
    }, { timeout: 25000 });

    const raw = await page.$eval('#result', (el) => el.textContent || '');
    let result;
    try {
      result = JSON.parse(raw);
    } catch (e) {
      throw new Error(`Harness result is not valid JSON: ${raw}`);
    }
    return { result, consoleMessages, pageErrors };
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
    throw new Error(`Missing TeaVM bundle at ${bundlePath}. Run webpack/copyWebpackOutput first.`);
  }

  const server = http.createServer((req, res) => {
    const reqPath = req.url || '/';
    const file = resolvePath(reqPath === '/' ? '/test-harness/rtc-harness.html' : reqPath);
    if (!file) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(`Not found: ${reqPath}`);
      return;
    }
    res.writeHead(200, { 'Content-Type': mimeType(file) });
    fs.createReadStream(file).pipe(res);
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const addr = server.address();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const url = `http://127.0.0.1:${port}/test-harness/rtc-harness.html`;

  try {
    const { result, consoleMessages, pageErrors } = await runBrowserHarness(url);
    if (consoleMessages.length) {
      process.stderr.write(`${consoleMessages.join('\n')}\n`);
    }
    if (pageErrors.length) {
      process.stderr.write(`${pageErrors.join('\n')}\n`);
    }
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (!result.ok) {
      process.exitCode = 1;
    }
  } finally {
    server.close();
  }
}

main().catch((err) => {
  process.stderr.write(`${err.stack || err.message || String(err)}\n`);
  process.exit(1);
});
