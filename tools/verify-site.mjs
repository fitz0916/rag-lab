#!/usr/bin/env node
/**
 * 在 Node 沙箱里加载 demo-boot.js，对真实 replay 数据把每条路由跑一遍。
 *
 * 为什么要这么测：拦截层是 IIFE，路由表在闭包里，只有把 window.fetch 换掉
 * 再发请求才能真正验证。DOM 相关的部分（banner / chips）用 stub 跳过。
 *
 * 用法：先 build-site 并起静态服务器，然后
 *   node tools/verify-site.mjs [http://127.0.0.1:5173/]
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = process.argv[2] || 'http://127.0.0.1:5173/';

const SRC = join(ROOT, 'site/demo-boot.js');
const code = await readFile(SRC, 'utf8');

/* ------------------------------------------------------------ DOM stub */

const noop = () => { };
function fakeEl() {
    return {
        style: {}, dataset: {}, classList: { add: noop, remove: noop, toggle: noop, contains: () => false },
        appendChild: noop, addEventListener: noop, insertBefore: noop,
        set innerHTML(v) { }, set textContent(v) { }, textContent: ''
    };
}

const sandbox = {
    console,
    setTimeout, clearTimeout, setInterval, clearInterval,
    Promise, JSON, Math, Date, Object, Array, String, Number, Boolean, RegExp, Set, Map, Error,
    URL, URLSearchParams, TextDecoder, Buffer,
    location: {
        hostname: '127.0.0.1', port: '5173',
        search: '', href: BASE, pathname: '/'
    },
    document: {
        baseURI: BASE, readyState: 'complete',
        head: { appendChild: noop },
        querySelector: () => null,
        getElementById: () => null,
        createElement: () => fakeEl(),
        addEventListener: noop
    },
    EventSource: function () { throw new Error('占位'); }
};
sandbox.window = sandbox;
sandbox.window.fetch = globalThis.fetch;

const vm = await import('node:vm');
vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: 'demo-boot.js' });

const f = sandbox.window.fetch;
const ES = sandbox.window.EventSource;

/* ------------------------------------------------------------ 断言工具 */

let pass = 0, fail = 0;
const failures = [];

function check(name, cond, extra = '') {
    if (cond) {
        pass++;
        console.log(`  ✓ ${name}`);
    } else {
        fail++;
        failures.push(name + (extra ? ' — ' + extra : ''));
        console.log(`  ✗ ${name}${extra ? '  ' + extra : ''}`);
    }
}

async function getJson(path, init) {
    const r = await f(path, init);
    return r.json();
}

function post(path, body) {
    return getJson(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
}

/** 收集一次 SSE 的全部事件 */
function collect(path, ms = 4000) {
    return new Promise(resolve => {
        const events = [];
        const es = new ES(path);
        es.addEventListener('log', e => {
            try { events.push({ type: 'log', data: JSON.parse(e.data) }); }
            catch (err) { events.push({ type: 'log', data: e.data }); }
        });
        es.addEventListener('end', () => { es.close(); resolve(events); });
        es.onerror = () => resolve(events);
        setTimeout(() => { es.close(); resolve(events); }, ms);
    });
}

/* ------------------------------------------------------------ 开跑 */

console.log(`\n== 静态站自检 @ ${BASE} ==\n`);

console.log('[1] 基础接口');
{
    const h = await getJson('/api/health');
    check('健康检查可用', h.ok === true && h.data && h.data.qwen);
    check('健康检查暴露的是录制时的配置', typeof h.data.config.dimensions === 'number');

    const docs = await getJson('/api/ingest/docs?limit=30');
    check('文档列表只有 doc1', docs.data.length === 1 && docs.data[0].id === 1,
        `实际 ${docs.data.length} 份`);
    check('切片数对得上', docs.data[0].chunkCount === 89);

    const chunks = await getJson('/api/ingest/docs/1/chunks?limit=200');
    check('切片列表 89 条', chunks.data.length === 89, `实际 ${chunks.data.length}`);

    const detail = await getJson('/api/ingest/docs/1/chunks/0');
    const vec = detail.data && detail.data.vector && detail.data.vector.head;
    check('单片解剖带真向量', !!vec && String(vec).length > 10);

    const miss = await getJson('/api/ingest/docs/3/chunks?limit=200');
    check('doc3 的切片返回空（不在回放里）', Array.isArray(miss.data) && miss.data.length === 0);

    const runs = await getJson('/api/runs?limit=50&type=ALL&onlyFailed=false');
    check('运行记录可读', runs.data && runs.data.records.length > 0,
        `records=${runs.data && runs.data.records.length}`);
}

console.log('\n[2] 阶段二~五、七、八：POST → SSE → GET');
const STAGES = [
    { name: '阶段二 向量检索', post: '/api/retrieve', get: '/api/retrieve', q: 'RRF 是怎么把两路排名融合成一个分数的', topKey: 'hits' },
    { name: '阶段三 混合检索', post: '/api/hybrid', get: '/api/hybrid', q: '怎么用 BM25 做关键词检索', topKey: null },
    { name: '阶段四 重排', post: '/api/rerank', get: '/api/rerank', q: '查询重写是怎么做的', topKey: null },
    { name: '阶段五 上下文压缩', post: '/api/context', get: '/api/context', q: '文档为什么要切成小片段再入库', topKey: null },
    { name: '阶段七 生成', post: '/api/generate', get: '/api/generate', q: 'RRF 是怎么把两路排名融合成一个分数的', topKey: null },
    { name: '阶段八 查询重写', post: '/api/rewrite', get: '/api/rewrite', q: '查询重写是怎么做的', topKey: null }
];

for (const st of STAGES) {
    const sub = await post(st.post, { query: st.q, topK: 5, recallTopN: 20 });
    check(`${st.name} · 提交返回 traceId`, sub.ok && sub.data && sub.data.traceId);
    if (!sub.ok) continue;

    const tid = sub.data.traceId;
    const events = await collect(`${st.get}/${tid}/stream`);
    check(`${st.name} · 有日志事件`, events.length > 3, `${events.length} 条`);
    check(`${st.name} · 日志字段完整`,
        events.every(e => e.data && typeof e.data.message === 'string' && e.data.level));

    const res = await getJson(`${st.get}/${tid}`);
    check(`${st.name} · 结果可读`, res.ok && res.data);
}

console.log('\n[3] 模糊匹配（用户手输任意问题）');
{
    const cases = [
        ['RRF 是怎么把两路排名融合成一个分数的', '应当命中同名场景'],
        ['RRF 融合是怎么回事', '应当是同一场景'],
        ['怎么把两路排名合成一个分数', '相似但不完全一致'],
        ['今天天气怎么样', '完全无关，仍应回放最接近的一条']
    ];
    for (const [q, note] of cases) {
        const sub = await post('/api/retrieve', { query: q, topK: 5 });
        const ok = sub.ok && sub.data && sub.data.traceId;
        const res = ok ? await getJson(`/api/retrieve/${sub.data.traceId}`) : null;
        check(`「${q}」→ 有结果`, ok && res && res.data, note);
    }
}

console.log('\n[4] 阶段六 评测');
{
    const sub = await post('/api/eval/run', { strategies: 'VECTOR,HYBRID,RERANK,CONTEXT,REWRITE', topK: 5, recallTopN: 20 });
    check('提评测返回 runId', sub.ok && sub.data.runId);
    const rid = sub.data.runId;

    const events = await collect(`/api/eval/${rid}/stream`, 8000);
    check('评测日志 148 条左右', events.length > 100, `${events.length} 条`);

    const res = await getJson(`/api/eval/${rid}`);
    const cases = res.data.result.cases;
    check('评测 15 条用例', cases.length === 15, `实际 ${cases.length}`);
    check('5 种策略都跑了',
        cases[0].metrics.length === 5,
        `实际 ${cases[0].metrics.length}`);
    check('指标字段齐全',
        cases.every(c => c.metrics.every(m =>
            typeof m.hit1 === 'boolean' && typeof m.mrr === 'number' && typeof m.coverage === 'number')));
    check('规范类用例的正文已隐去',
        cases.filter(c => c.name === '行业规范类用例').every(c =>
            c.metrics.every(m => (m.top || []).every(t => !t.text || t.text.includes('已隐去')))));

    const kv = await getJson('/api/eval/cases');
    check('用例库 15 条', kv.data.length === 15);
}

console.log('\n[5] 阶段九 三层缓存');
{
    const stats = await getJson('/api/cache/stats');
    check('缓存统计可读', stats.ok && stats.data.stats);

    await getJson('/api/cache', { method: 'DELETE' });
    const q1 = '多层缓存怎么提升命中率';
    const q2 = '缓存命中率怎么提高';
    const layers = [];

    for (const [i, q] of [q1, q1, q1, q2].entries()) {
        if (i === 2) await getJson('/api/cache/l1', { method: 'DELETE' });
        const sub = await post('/api/generate', { query: q, topK: 5, recallTopN: 20 });
        const res = await getJson(`/api/generate/${sub.data.traceId}`);
        const t = res.data.trace;
        layers.push(t.cacheHit ? t.cacheLayer : 'MISS');
    }
    check('四步依次是 MISS / L1 / L2 / L3',
        layers.join(',') === 'MISS,L1,L2,L3',
        '实际 ' + layers.join(','));

    const lookup = await post('/api/cache/lookup', { query: q2, topK: 5 });
    check('缓存探针可用', lookup.ok && typeof lookup.data.hit === 'boolean');
}

console.log('\n[6] 越权 / 写操作应被拒绝');
{
    const add = await post('/api/eval/cases', { name: 'x', query: 'y', expectText: 'z', difficulty: 'EASY' });
    check('新增用例被拒绝', add.ok === false);
    const toggle = await post('/api/eval/cases/1/toggle?enabled=false', {});
    check('切换用例被拒绝', toggle.ok === false);
}

/* ------------------------------------------------------------ 结果 */
console.log(`\n通过 ${pass} 项，失败 ${fail} 项`);
if (failures.length) {
    console.log('\n失败明细：');
    failures.forEach(x => console.log('  · ' + x));
    process.exit(1);
}
console.log('\n全部通过。\n');
