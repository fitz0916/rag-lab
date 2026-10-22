#!/usr/bin/env node
/**
 * 录制 RAG Lab 的 9 个阶段，产出静态回放站需要的 replay 数据。
 *
 * 用法（服务已在 8080 跑着）：
 *   node tools/record-demo.mjs
 *   node tools/record-demo.mjs --out src/main/resources/static/replay
 *
 * 设计要点：
 * 1. 不重新入库 —— 直接订阅已结束任务的 SSE，后端会把 DB 里的历史日志回放一遍，
 *    所以 Chroma 不会被写脏。
 * 2. 只录 doc1（技术文章）。doc3 是行业规范 PDF，任何切片正文都不进 replay。
 * 3. SSE 事件按原顺序存，时间戳保留在 data.ts 里；回放节奏由前端按条数自适应，
 *    不依赖录制时的真实耗时（否则评测阶段要等 5 分钟）。
 */
import { mkdir, writeFile, cp, rm } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sanitizeDir } from './sanitize-replay.mjs';

const BASE = process.env.RAG_BASE || 'http://127.0.0.1:8080';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const argOut = process.argv.indexOf('--out');
const OUT = argOut > -1
    ? join(ROOT, process.argv[argOut + 1])
    : join(ROOT, 'src/main/resources/static/replay');

const KEEP_DOC_ID = 1;
const EVAL_TIMEOUT = 15 * 60 * 1000;

/* ---------------------------------------------------------------- 基础请求 */

async function postJson(path, body) {
    const r = await fetch(BASE + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    return r.json();
}

async function getJson(path) {
    const r = await fetch(BASE + path);
    return r.json();
}

async function delJson(path) {
    const r = await fetch(BASE + path, { method: 'DELETE' });
    return r.json();
}

function parseSseBlock(raw) {
    let event = 'message';
    const data = [];
    for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length && event === 'message') return null;
    const text = data.join('\n');
    let parsed;
    try { parsed = JSON.parse(text); } catch { parsed = text; }
    return { event, data: parsed };
}

/** 读完一条 SSE 流，返回事件数组（含相对时间 delay） */
async function collectSse(path, timeoutMs = 180000) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const t0 = Date.now();
    const events = [];
    try {
        const res = await fetch(BASE + path, {
            headers: { Accept: 'text/event-stream' },
            signal: ctrl.signal
        });
        if (!res.body) throw new Error('无响应体：' + path);
        const reader = res.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += dec.decode(value, { stream: true });
            let idx;
            while ((idx = buf.indexOf('\n\n')) >= 0) {
                const block = buf.slice(0, idx);
                buf = buf.slice(idx + 2);
                const ev = parseSseBlock(block);
                if (!ev) continue;
                ev.delay = Date.now() - t0;
                events.push(ev);
                if (ev.event === 'end') {
                    reader.cancel().catch(() => {});
                    return events;
                }
            }
        }
    } finally {
        clearTimeout(timer);
    }
    return events;
}

/* ------------------------------------------------------------ 场景录制核心 */

/**
 * 跑一次「POST → SSE → GET 结果」，存成一个可回放的场景。
 * @param {object} opt
 * @param {string} opt.id      场景 id
 * @param {number} opt.stage   阶段号
 * @param {string} opt.preset  前端展示用的入口名
 * @param {string} opt.query   触发这个场景的问题
 * @param {string} opt.post    提交路径，如 /api/retrieve
 * @param {object} opt.body    POST 请求体
 * @param {string} opt.idField 响应里承载 id 的字段名（traceId / runId / taskId）
 * @param {string} opt.sseTpl  SSE 路径模板，用 {id} 占位
 * @param {string} opt.getTpl  结果路径模板，用 {id} 占位
 */
async function recordScenario(opt) {
    const t0 = Date.now();
    const payload = { ...opt.body, query: opt.query };
    const res = await postJson(opt.post, payload);
    if (!res || !res.ok) {
        throw new Error(`[${opt.id}] 提交失败：${res && res.message}`);
    }
    const id = res.data[opt.idField];
    if (!id) throw new Error(`[${opt.id}] 响应里没有 ${opt.idField}`);

    const ssePath = opt.sseTpl.replace('{id}', id);
    const sse = await collectSse(ssePath, opt.timeout || 180000);

    const getPath = opt.getTpl.replace('{id}', id);
    const result = await getJson(getPath);

    const sc = {
        id: opt.id,
        stage: opt.stage,
        label: opt.label || opt.query,
        query: opt.query,
        body: payload,
        traceId: id,
        sse,
        result
    };
    const cost = Date.now() - t0;
    console.log(`  ✓ ${opt.id.padEnd(22)} ${cost}ms  ${sse.length} 事件  ${opt.query}`);
    return sc;
}

/* ------------------------------------------------------------------ 主流程 */

const log = (...a) => console.log(...a);

async function main() {
    await mkdir(OUT, { recursive: true });

    log('\n== 0. 健康检查 ==');
    const health = await getJson('/api/health');
    if (!health.ok) throw new Error('服务没起来，先在项目根目录启动应用');
    log('  ✓ ' + health.data.qwen.detail);

    log('\n== 1. 阶段一：文档 / 切片 / 单片解剖 ==');
    const docsAll = await getJson('/api/ingest/docs?limit=30');
    const docs = docsAll.data.filter(d => d.id === KEEP_DOC_ID);
    log(`  文档列表：原始 ${docsAll.data.length} 份，保留 ${docs.length} 份（只留 doc${KEEP_DOC_ID}）`);
    const dropped = docsAll.data.filter(d => d.id !== KEEP_DOC_ID).map(d => d.docName);
    if (dropped.length) log('  已排除：' + dropped.join('、'));

    const chunksRes = await getJson(`/api/ingest/docs/${KEEP_DOC_ID}/chunks?limit=200`);
    const chunks = chunksRes.data;
    log(`  ✓ 切片 ${chunks.length} 片`);

    // 单片解剖：每片都录，点开任意一片都有真向量可看
    const details = {};
    const CONCURRENCY = 6;
    let cursor = 0;
    let done = 0;
    async function worker() {
        for (;;) {
            const i = cursor++;
            if (i >= chunks.length) return;
            const idx = chunks[i].chunkIndex;
            const r = await getJson(`/api/ingest/docs/${KEEP_DOC_ID}/chunks/${idx}`);
            if (r.ok) details[idx] = r.data;
            if (++done % 20 === 0) log(`    …已取 ${done}/${chunks.length}`);
        }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    log(`  ✓ 单片解剖 ${Object.keys(details).length} 条（含 1024 维真向量）`);

    // 入库任务的 SSE：订阅已结束任务，后端回放历史日志
    const tasks = await getJson('/api/ingest/tasks?limit=20');
    const task = tasks.data.find(t => t.docId === KEEP_DOC_ID);
    let ingestSse = [];
    if (task) {
        log(`  入库任务 ${task.id.slice(0, 8)}，回放历史日志…`);
        ingestSse = await collectSse(`/api/ingest/tasks/${task.id}/stream`, 120000);
        log(`  ✓ ${ingestSse.length} 条日志`);
    }

    const runs = await getJson('/api/runs?limit=50&type=ALL&onlyFailed=false');

    // 缓存可能命中，先清掉，保证第 7 阶段录到的是完整链路
    await delJson('/api/cache');

    log('\n== 2. 阶段二：向量检索 ==');
    const R2 = [
        'RRF 是怎么把两路排名融合成一个分数的',
        '文档为什么要切成小片段再入库',
        '多层缓存怎么提升命中率'
    ];
    const s2 = [];
    for (const [i, q] of R2.entries()) {
        s2.push(await recordScenario({
            id: `s2-${i + 1}`, stage: 2, query: q,
            post: '/api/retrieve', body: { query: q, topK: 5 }, idField: 'traceId',
            sseTpl: '/api/retrieve/{id}/stream', getTpl: '/api/retrieve/{id}'
        }));
    }

    log('\n== 3. 阶段三：BM25 + RRF 混合检索 ==');
    const s3 = [];
    for (const [i, q] of ['怎么用 BM25 做关键词检索', 'RRF 是怎么把两路排名融合成一个分数的'].entries()) {
        s3.push(await recordScenario({
            id: `s3-${i + 1}`, stage: 3, query: q,
            post: '/api/hybrid',
            body: { query: q, vecTopN: 20, bm25TopN: 20, finalTopK: 5, rrfK: 60 },
            idField: 'traceId',
            sseTpl: '/api/hybrid/{id}/stream', getTpl: '/api/hybrid/{id}'
        }));
    }

    log('\n== 4. 阶段四：Rerank 精排 ==');
    const s4 = [];
    for (const [i, q] of ['查询重写是怎么做的', '上下文塞太多会导致什么问题'].entries()) {
        s4.push(await recordScenario({
            id: `s4-${i + 1}`, stage: 4, query: q,
            post: '/api/rerank', body: { query: q, recallTopN: 20, finalTopK: 5 },
            idField: 'traceId',
            sseTpl: '/api/rerank/{id}/stream', getTpl: '/api/rerank/{id}'
        }));
    }

    log('\n== 5. 阶段五：句子级上下文压缩 ==');
    const s5 = [];
    for (const [i, q] of ['大模型幻觉会影响答案准确性吗，怎么缓解', '文档为什么要切成小片段再入库'].entries()) {
        s5.push(await recordScenario({
            id: `s5-${i + 1}`, stage: 5, query: q,
            post: '/api/context',
            body: { query: q, recallTopN: 20, topK: 5, strategy: 'VECTOR', maxChars: 1200 },
            idField: 'traceId',
            sseTpl: '/api/context/{id}/stream', getTpl: '/api/context/{id}'
        }));
    }

    log('\n== 6. 阶段六：策略评测（15 用例 × 5 策略，这步最慢）==');
    const evalCases = await getJson('/api/eval/cases');
    const enabledCases = evalCases.data.filter(c => c.enabled);
    log(`  用例 ${enabledCases.length} 条`);
    const s6 = await recordScenario({
        id: 's6-1', stage: 6, label: '全策略评测', query: '(批量评测)',
        post: '/api/eval/run',
        body: { strategies: 'VECTOR,HYBRID,RERANK,CONTEXT,REWRITE', topK: 5, recallTopN: 20 },
        idField: 'runId',
        sseTpl: '/api/eval/{id}/stream', getTpl: '/api/eval/{id}',
        timeout: EVAL_TIMEOUT
    });
    const evalRuns = await getJson('/api/eval/runs');

    log('\n== 7. 阶段七：生成 ==');
    const s7 = [];
    for (const [i, q] of ['RRF 是怎么把两路排名融合成一个分数的', '多层缓存怎么提升命中率'].entries()) {
        s7.push(await recordScenario({
            id: `s7-${i + 1}`, stage: 7, query: q,
            post: '/api/generate', body: { query: q, topK: 5, recallTopN: 20 },
            idField: 'traceId',
            sseTpl: '/api/generate/{id}/stream', getTpl: '/api/generate/{id}'
        }));
    }

    log('\n== 8. 阶段八：查询重写 ==');
    const s8 = [];
    for (const [i, q] of ['查询重写是怎么做的', '怎么用 BM25 做关键词检索'].entries()) {
        s8.push(await recordScenario({
            id: `s8-${i + 1}`, stage: 8, query: q,
            post: '/api/rewrite',
            body: { query: q, variants: 3, temperature: 0.7, rerank: true },
            idField: 'traceId',
            sseTpl: '/api/rewrite/{id}/stream', getTpl: '/api/rewrite/{id}'
        }));
    }

    log('\n== 9. 阶段九：三层缓存 ==');
    // 按前端 runCacheDemo 的真实顺序跑：MISS → L1 → 清L1 → L2 → 换说法 → L3
    const cq = '多层缓存怎么提升命中率';
    const cpq = '缓存命中率怎么提高';
    await delJson('/api/cache');
    const s9steps = [];

    async function cacheStepGen(label, q, note) {
        const sc = await recordScenario({
            id: `s9-${s9steps.length + 1}`, stage: 9, query: q,
            post: '/api/generate', body: { query: q, topK: 5, recallTopN: 20 },
            idField: 'traceId',
            sseTpl: '/api/generate/{id}/stream', getTpl: '/api/generate/{id}'
        });
        sc.label = label;
        sc.note = note;
        const t = sc.result && sc.result.data && sc.result.data.trace;
        log(`    ${label} → ${t ? (t.cacheHit ? t.cacheLayer : 'MISS') : '?'} ${t && t.costMs}ms`);
        s9steps.push(sc);
        return sc;
    }

    await cacheStepGen('① 首次提问', cq, '清空缓存后第一次，必然未命中');
    await cacheStepGen('② 原样再问', cq, '同样的字符串 → L1 内存');
    await delJson('/api/cache/l1');
    await cacheStepGen('③ 清 L1 后再问', cq, '内存被清空了 → 应该落到 L2 Redis');
    await cacheStepGen('④ 换个说法', cpq, '措辞不同 → 只能靠 L3 语义');

    const cacheStats = await getJson('/api/cache/stats');

    /* ---------------------------------------------------------- 落盘 */
    log('\n== 10. 写文件 ==');
    const files = {
        'health.json': health,
        'docs.json': { ok: true, data: docs, message: null },
        'doc-tasks.json': { ok: true, data: tasks.data.filter(t => t.docId === KEEP_DOC_ID), message: null },
        'chunks.json': { ok: true, data: { docId: KEEP_DOC_ID, list: chunks }, message: null },
        'chunk-details.json': { ok: true, data: details, message: null },
        'ingest-stream.json': { ok: true, data: ingestSse, message: null },
        'runs.json': runs,
        'eval-cases.json': { ok: true, data: enabledCases, message: null },
        'eval-runs.json': evalRuns,
        'cache-stats.json': cacheStats,
        'scenarios.json': {
            ok: true,
            data: {
                retrieve: s2, hybrid: s3, rerank: s4, context: s5,
                eval: [s6], generate: s7, rewrite: s8, cache: s9steps
            },
            message: null
        }
    };

    let total = 0;
    for (const [name, obj] of Object.entries(files)) {
        const text = JSON.stringify(obj);
        await writeFile(join(OUT, name), text, 'utf8');
        total += Buffer.byteLength(text);
        console.log(`  ${name.padEnd(22)} ${(Buffer.byteLength(text) / 1024).toFixed(1)} KB`);
    }
    console.log(`  合计 ${(total / 1024).toFixed(1)} KB`);

    // 原始数据留一份在 tools/.replay-backup（脱敏要拿它当词表来源），
    // 然后立刻脱敏，保证仓库里任何时刻都不存在未脱敏的副本
    const BACKUP = join(ROOT, 'tools/.replay-backup');
    await rm(BACKUP, { recursive: true, force: true });
    await cp(OUT, BACKUP, { recursive: true });
    await sanitizeDir(OUT, { backupDir: BACKUP });

    /* ---------------------------------------------------------- 审计 */
    log('\n== 11. 泄露审计（找 doc3 的痕迹）==');
    const suspects = [];
    function scan(node, path) {
        if (typeof node === 'string') {
            if (/Q\/?SY\s*01015|油气田|附\s*录\s*E|采集深度应执行/.test(node)) {
                suspects.push({ path, head: node.slice(0, 60) });
            }
            return;
        }
        if (Array.isArray(node)) return node.forEach((v, i) => scan(v, `${path}[${i}]`));
        if (node && typeof node === 'object') {
            for (const k of Object.keys(node)) scan(node[k], `${path}.${k}`);
        }
    }
    for (const [name, obj] of Object.entries(files)) scan(obj, name);
    if (!suspects.length) {
        log('  ✓ 干净，没有命中');
    } else {
        log(`  ⚠ 发现 ${suspects.length} 处可疑内容：`);
        const byFile = {};
        suspects.forEach(s => {
            const f = s.path.split(/[.\[]/)[0];
            byFile[f] = (byFile[f] || 0) + 1;
        });
        Object.entries(byFile).forEach(([f, n]) => log(`    ${f}: ${n} 处`));
        suspects.slice(0, 5).forEach(s => log(`    · ${s.path} → ${s.head}`));
        log('  建议人工确认后再发布。');
    }

    log('\n完成。输出目录：' + OUT + '\n');
}

main().catch(e => {
    console.error('\n录制失败：' + e.message);
    console.error(e.stack);
    process.exit(1);
});
