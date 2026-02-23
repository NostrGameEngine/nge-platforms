import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import puppeteer from 'puppeteer-core';

const projectDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const repoRoot = path.resolve(projectDir, '..');
const teavmDir = path.resolve(repoRoot, 'nge-platform-teavm');
const rootDirs = [path.join(projectDir, 'test-harness'), path.join(teavmDir, 'src', 'main', 'resources')];
const state = { results: { jvm: null, android: null, browser: null } };

const json = (res, s, b) => { res.writeHead(s, {'Content-Type':'application/json; charset=utf-8'}); res.end(JSON.stringify(b)); };
const mime = (f) => f.endsWith('.html') ? 'text/html; charset=utf-8' : (f.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'application/octet-stream');
function resolvePath(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]).replace(/^\/+/, '');
  for (const base of rootDirs) {
    const normalized = clean.startsWith('test-harness/') ? clean.replace(/^test-harness\//,'') : clean;
    const c = path.resolve(base, normalized);
    if (c.startsWith(base) && fs.existsSync(c) && fs.statSync(c).isFile()) return c;
  }
  return null;
}
async function readBody(req){ const chunks=[]; for await (const c of req) chunks.push(c); return Buffer.concat(chunks).toString('utf8'); }
function makeServer() {
  return http.createServer(async (req,res)=>{
    try {
      const u = new URL(req.url || '/', 'http://127.0.0.1');
      if ((req.method||'GET') === 'POST' && u.pathname.startsWith('/signal/result/')) {
        const peer = u.pathname.split('/').pop();
        state.results[peer] = JSON.parse(await readBody(req));
        return json(res,200,{ok:true});
      }
      const file = resolvePath(u.pathname === '/' ? '/test-harness/async-parity.html' : u.pathname);
      if (!file) return json(res,404,{error:'not found'});
      res.writeHead(200, {'Content-Type': mime(file)}); fs.createReadStream(file).pipe(res);
    } catch (e) { json(res,500,{error:String(e?.stack||e)}); }
  });
}
function createLinePrefixWriter(target, prefix){
  let buffer='';
  return {
    push(chunk){
      buffer += chunk;
      while(true){
        const nl = buffer.indexOf('\n');
        if(nl < 0) break;
        target.write(`${prefix}${buffer.slice(0,nl+1)}`);
        buffer = buffer.slice(nl+1);
      }
    },
    flush(){
      if(buffer.length) target.write(`${prefix}${buffer}\n`);
      buffer='';
    }
  };
}
function spawnCapture(cmd,args,{cwd,env,label}={}){
  return new Promise((resolve,reject)=>{
    const ch=spawn(cmd,args,{cwd,env:{...process.env,...(env||{})},stdio:['ignore','pipe','pipe']}); let stdout='',stderr='';
    const outWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stdout] `) : null;
    const errWriter = label ? createLinePrefixWriter(process.stderr, `[${label} stderr] `) : null;
    ch.stdout.on('data',d=>{ const s=d.toString(); stdout+=s; outWriter?.push(s); });
    ch.stderr.on('data',d=>{ const s=d.toString(); stderr+=s; errWriter?.push(s); });
    ch.on('error',reject); ch.on('exit',code=>{ outWriter?.flush(); errWriter?.flush(); resolve({code:code??-1,stdout,stderr}); });
  });
}
async function runBrowser(url){
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args:['--no-sandbox','--disable-dev-shm-usage','--disable-gpu'] });
  const page = await browser.newPage(); const consoleMessages=[]; const pageErrors=[];
  page.on('console',m=>consoleMessages.push(`[${m.type()}] ${m.text()}`)); page.on('pageerror',e=>pageErrors.push(e.stack||e.message||String(e)));
  try {
    await page.goto(url,{waitUntil:'networkidle2',timeout:20000});
    await page.waitForFunction(()=>{ const el=document.getElementById('result'); if(!el) return false; const t=(el.textContent||'').trim(); if(!t||t==='RUNNING') return false; try{ const p=JSON.parse(t); return typeof p.ok==='boolean'; }catch{return false;} }, {timeout:30000});
    const raw = await page.$eval('#result', el => el.textContent || '');
    return { result: JSON.parse(raw), consoleMessages, pageErrors };
  } finally { await browser.close(); }
}
function compare(results){
  const peers=['jvm','android','browser']; const keys=['executorRun','executorRunLater','wrapResolved','wrapRejectedCaught','wrapRejectedAwaitFailed','promisifyResolved','thenChain','composeChain','awaitAllOrder','awaitAnyFirstSuccess','awaitAnyFilter','awaitAllFail','awaitAnyAllFail','awaitAnyNoMatch','awaitAllSettledCount','awaitAllSettledPattern','queueOrder'];
  const mismatches=[]; for(const p of peers){ if(!results[p]||!results[p].ok) mismatches.push(`missing/failed result for ${p}`); }
  if (!mismatches.length) for(const k of keys){ const vals=peers.map(p=>JSON.stringify(results[p][k])); if(!(vals[0]===vals[1]&&vals[1]===vals[2])) mismatches.push(`mismatch ${k}: ${vals.join(' | ')}`); }
  return { ok: mismatches.length===0, mismatches, checkedKeys: keys };
}

async function main(){
  const bundlePath = path.join(teavmDir,'src','main','resources','org','ngengine','platform','teavm','TeaVMBinds.bundle.js'); if(!fs.existsSync(bundlePath)) throw new Error('Missing TeaVM bundle');
  const server = makeServer(); await new Promise(r=>server.listen(0,'0.0.0.0',r)); const port = server.address().port;
  const jvmSignalBase = `http://127.0.0.1:${port}/signal`; const androidSignalBase = `http://10.0.2.2:${port}/signal`; const browserSignalBase = `http://127.0.0.1:${port}/signal`;
  const pageUrl = `http://127.0.0.1:${port}/test-harness/async-parity.html?signalBase=${encodeURIComponent(browserSignalBase)}`;
  const jvmPromise = spawnCapture('./gradlew',[':nge-platform-interop-test:jvm:jvmAsyncParityJvmSide',`-PinteropSignalBase=${jvmSignalBase}`,'--console=plain'],{cwd:repoRoot,label:'jvm'});
  const androidPromise = spawnCapture('./gradlew',[':nge-platform-interop-test:android:androidRtcEmulatorHarness','--console=plain'],{cwd:repoRoot,label:'android',env:{ANDROID_RTC_TEST_FILTER:'org.ngengine.platform.android.AndroidAsyncParityInstrumentedTest',ANDROID_RTC_SIGNAL_BASE:androidSignalBase,ANDROID_AVD_NAME:process.env.ANDROID_AVD_NAME||'Generic_AOSP'}});
  let browserOut=null,browserErr=null; try{ browserOut = await runBrowser(pageUrl); } catch(e){ browserErr=e; }
  const [jvmOut, androidOut] = await Promise.all([jvmPromise,androidPromise]); server.close();
  if (browserOut?.consoleMessages?.length) process.stderr.write(browserOut.consoleMessages.join('\n')+'\n');
  if (browserOut?.pageErrors?.length) process.stderr.write(browserOut.pageErrors.join('\n')+'\n');
  if (browserErr) throw browserErr;
  const cmp = compare(state.results);
  const out = { ok: cmp.ok && jvmOut.code===0 && androidOut.code===0 && Boolean(browserOut?.result?.ok), compare: cmp, results: state.results, jvmExitCode:jvmOut.code, androidExitCode:androidOut.code, exclusions:['TeaVM browser side is a JS Promise semantic baseline, not direct TeaVMPlatform AsyncTask Java execution'] };
  process.stdout.write(JSON.stringify(out,null,2)+'\n'); if(!out.ok) process.exit(1);
}
main().catch(e=>{ process.stderr.write(`${e.stack||e.message||String(e)}\n`); process.exit(1); });
