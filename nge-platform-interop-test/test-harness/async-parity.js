const resultEl = document.getElementById('result');
const signalBase = new URL(location.href).searchParams.get('signalBase');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const post = async (path, body) => {
  const res = await fetch(`${signalBase}${path}`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status}`);
};
const setResult = (o) => { resultEl.textContent = JSON.stringify(o, null, 2); };

async function snapshot() {
  const executorRun = await Promise.resolve().then(() => 7);
  const executorRunLater = await new Promise((res) => setTimeout(() => res(9), 10));
  const wrapResolved = await Promise.resolve(5);
  let wrapRejectedCaught = false;
  let wrapRejectedAwaitFailed = false;
  try { await Promise.reject(new Error('boom')).catch((e) => { wrapRejectedCaught = true; throw e; }); } catch { wrapRejectedAwaitFailed = true; }
  const promisifyResolved = await new Promise((res) => setTimeout(() => res('exec-ok'), 0));
  const thenChain = await Promise.resolve(3).then((v) => v + 4);
  const composeChain = await Promise.resolve(3).then((v) => Promise.resolve(v * 2));
  const awaitAllOrder = (await Promise.all([
    new Promise((r) => setTimeout(() => r('a'), 30)),
    new Promise((r) => setTimeout(() => r('b'), 0)),
    new Promise((r) => setTimeout(() => r('c'), 10)),
  ])).join(',');
  const awaitAnyFirstSuccess = await Promise.any([Promise.reject(new Error('x')), new Promise((r) => setTimeout(() => r(42), 10))]);

  const awaitAnyFilter = await new Promise((resolve, reject) => {
    let remaining = 2;
    let done = false;
    [new Promise((r) => setTimeout(() => r(5), 0)), new Promise((r) => setTimeout(() => r(12), 10))].forEach((p) => {
      p.then((v) => {
        if (done) return;
        if (v > 10) { done = true; resolve(v); return; }
        remaining--; if (remaining === 0) reject(new Error('No promises matched the filter'));
      }).catch(() => { remaining--; if (remaining === 0 && !done) reject(new Error('All promises failed')); });
    });
  });

  const awaitAllFail = await Promise.all([Promise.resolve(1), Promise.reject(new Error('x'))]).then(() => false).catch(() => true);
  const awaitAnyAllFail = await Promise.any([Promise.reject(new Error('a')), Promise.reject(new Error('b'))]).then(() => false).catch(() => true);
  const awaitAnyNoMatch = await new Promise((resolve) => {
    let remaining = 2;
    [Promise.resolve(1), Promise.resolve(2)].forEach((p) => p.then((v) => {
      if (v > 10) return resolve(false);
      remaining--; if (remaining === 0) resolve(true);
    }).catch(() => { remaining--; if (remaining === 0) resolve(true); }));
  });

  const settled = await Promise.allSettled([Promise.resolve(1), Promise.reject(new Error('z'))]);
  const awaitAllSettledCount = settled.length;
  const awaitAllSettledPattern = settled.map((s) => s.status === 'fulfilled' ? 'S' : 'F').join('');
  const q = []; q.push('q1'); q.push('q2'); const queueOrder = `${q.shift()},${q.shift()}`;

  return {
    ok: true,
    executorRun, executorRunLater, wrapResolved, wrapRejectedCaught, wrapRejectedAwaitFailed,
    promisifyResolved, thenChain, composeChain, awaitAllOrder, awaitAnyFirstSuccess, awaitAnyFilter,
    awaitAllFail, awaitAnyAllFail, awaitAnyNoMatch, awaitAllSettledCount, awaitAllSettledPattern, queueOrder
  };
}

(async () => {
  try {
    if (!signalBase) throw new Error('Missing signalBase');
    const r = await snapshot();
    setResult(r);
    await post('/result/browser', r);
  } catch (e) {
    const err = { ok:false, error: String(e?.stack || e) };
    setResult(err);
    try { if (signalBase) await post('/result/browser', err); } catch {}
  }
})();
