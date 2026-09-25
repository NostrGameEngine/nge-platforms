import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const artifactDir = path.join(repoRoot, 'nge-platform-teavm/build/js-tests/js/org/ngengine/platform/teavm/TeaVMBackendParityTest');
const testName = 'allocatorUsesHandlesOnJsAndLinearAddressesOnWasmGc';
const test = JSON.parse(fs.readFileSync(path.join(artifactDir, 'tests.json'), 'utf8'))
  .find(entry => entry.name === testName);

if (!test || !fs.existsSync(path.join(artifactDir, 'classTest.js'))) {
  throw new Error('Compile TeaVMBackendParityTest for JavaScript before running this smoke test');
}

const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  if (pathname === '/') {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html><script type="module">
      import { main } from '/classTest.js';
      main([${JSON.stringify(test.argument)}], result => {
        window.allocatorSmoke = result instanceof Error ? result.stack || String(result) : 'OK';
      });
    </script>`);
    return;
  }
  const file = path.resolve(artifactDir, `.${decodeURIComponent(pathname)}`);
  if (!file.startsWith(`${artifactDir}${path.sep}`) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' });
  fs.createReadStream(file).pipe(response);
});

await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const chrome = process.env.CHROME_BIN || (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  : '/usr/bin/google-chrome');
let browser;
try {
  browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.stack || String(error)));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => window.allocatorSmoke !== undefined || window.teavmException !== undefined, { timeout: 30000 });
  const result = await page.evaluate(() => ({ result: window.allocatorSmoke, exception: window.teavmException }));
  if (result.result !== 'OK' || result.exception || errors.length) {
    throw new Error(JSON.stringify({ ...result, errors }));
  }
  process.stdout.write(`Compiled TeaVM JavaScript allocator smoke passed in Chrome: ${testName}\n`);
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
