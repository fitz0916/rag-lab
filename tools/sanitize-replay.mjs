#!/usr/bin/env node
/**
 * 对 replay 数据做脱敏：把行业规范 PDF（doc3）的痕迹抹掉。
 *
 * 原则：
 *   · 指标（hit / mrr / coverage / firstRank）一律不动 —— 它们只是数字，
 *     学习者需要看到真实表现；
 *   · 只替换「内容类」字段（text / content / preview / heading / docName）
 *     和「条款主题词」（用例名、问题、日志里打印的用例标题）；
 *   · 运行记录里属于规范问答的条目直接丢弃。
 *
 * 幂等：可以重复执行，已经是占位符的不会被二次破坏。
 *
 * 用法：
 *   node tools/sanitize-replay.mjs                     # 处理默认目录
 *   node tools/sanitize-replay.mjs --dir <path>        # 指定目录
 */
import { readFile, writeFile, readdir, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const HIDDEN_DOC_ID = 3;
export const DOC3_PAT = /Q\/?SY\s*01015|油气田|01015/;

const TEXT_PLACEHOLDER = '（行业标准原文，已隐去）';
const DOC_PLACEHOLDER = '行业标准（原文隐去）';
const QUERY_PLACEHOLDER = '（行业规范类问题 · 原文已隐去）';
const NAME_PLACEHOLDER = '行业规范类用例';

const TEXT_FIELDS = new Set(['text', 'content', 'preview', 'heading', 'snippet', 'body']);

function isDoc3Node(node) {
    if (!node || typeof node !== 'object') return false;
    if (node.docId === HIDDEN_DOC_ID) return true;
    if (typeof node.docName === 'string' && DOC3_PAT.test(node.docName)) return true;
    return false;
}

function maskDoc3Object(obj, stats) {
    const out = { ...obj };
    for (const f of TEXT_FIELDS) {
        if (typeof out[f] === 'string' && out[f].length > 0 && !out[f].includes('已隐去')) {
            out[f] = f === 'heading' ? '' : TEXT_PLACEHOLDER;
            stats.fields++;
        }
    }
    if (typeof out.docName === 'string' && DOC3_PAT.test(out.docName)) {
        out.docName = DOC_PLACEHOLDER;
        stats.docs++;
    }
    return out;
}

function scrubNode(node, stats) {
    if (Array.isArray(node)) {
        for (let i = 0; i < node.length; i++) {
            if (isDoc3Node(node[i])) node[i] = maskDoc3Object(node[i], stats);
            else scrubNode(node[i], stats);
        }
        return;
    }
    if (node && typeof node === 'object') {
        for (const k of Object.keys(node)) {
            if (isDoc3Node(node[k])) node[k] = maskDoc3Object(node[k], stats);
            else scrubNode(node[k], stats);
        }
    }
}

function isDoc3Case(c) {
    const tops = [];
    (c.metrics || []).forEach(m => (m.top || []).forEach(t => tops.push(t)));
    if (!tops.length) return false;
    const n3 = tops.filter(t => t.docId === HIDDEN_DOC_ID).length;
    return n3 >= tops.length * 0.5;
}

function sanitizeEvalResult(res, stats) {
    if (!res || !Array.isArray(res.cases)) return;
    for (const c of res.cases) {
        if (isDoc3Case(c)) {
            if (!String(c.query).includes('已隐去')) { c.query = QUERY_PLACEHOLDER; stats.cases++; }
            c.name = NAME_PLACEHOLDER;
            (c.metrics || []).forEach(m => {
                if (Array.isArray(m.matched) && m.matched.some(x => !String(x).includes('已隐去'))) {
                    stats.matched += m.matched.length;
                    m.matched = m.matched.map(() => '（已隐去）');
                }
            });
        }
    }
}

/** 通用字符串替换：把条款主题词换成中性说法 */
function applyTermRules(node, rules, stats) {
    if (typeof node === 'string') return rewriteString(node, rules, stats);
    if (Array.isArray(node)) {
        for (let i = 0; i < node.length; i++) node[i] = applyTermRules(node[i], rules, stats);
        return node;
    }
    if (node && typeof node === 'object') {
        for (const k of Object.keys(node)) node[k] = applyTermRules(node[k], rules, stats);
    }
    return node;
}

function rewriteString(s, rules, stats) {
    let out = s;
    // 日志里会把命中片段的开头打进 message，比如「VECTOR 返回 5 条，Top1: 编码保持一致…」
    // 这类文本没有结构化字段可依：先看有没有规范特征词，再看是否命中规范切片的指纹
    if (DOC3_PAT.test(out) || hasFingerprint(out)) {
        out = out
            .replace(/Top\d\s*[:：][\s\S]*$/, 'Top1: （行业标准原文，已隐去）')
            .replace(/Q\/?SY\s*01015[—\-–]?\d{4}/g, '（标准号已隐去）')
            .replace(/油气田/g, '某行业');
        stats.scrubbed = (stats.scrubbed || 0) + 1;
    }
    for (const [from, to] of rules) {
        if (out.includes(from)) {
            out = out.split(from).join(to);
            stats.terms++;
        }
    }
    return out;
}

/* -------------------------------------------------- 规范切片指纹 */

let FINGERPRINTS = [];

function cleanFp(s) {
    return String(s).replace(/[\s"“”'‘’「」『』、，,。.；;：:]+/g, '').slice(0, 12);
}

function hasFingerprint(s) {
    if (!FINGERPRINTS.length) return false;
    const flat = cleanFpPlain(s);
    return FINGERPRINTS.some(f => flat.includes(f));
}

function cleanFpPlain(s) {
    return String(s).replace(/[\s"“”'‘’「」『』、，,。.；;：:]+/g, '');
}

async function loadFingerprints(backupDir) {
    try {
        const scen = JSON.parse(await readFile(join(backupDir, 'scenarios.json'), 'utf8'));
        const fps = new Set();
        (scen.data.eval || []).forEach(sc => {
            const cases = sc.result && sc.result.data && sc.result.data.result && sc.result.data.result.cases;
            (cases || []).forEach(c => (c.metrics || []).forEach(m => (m.top || []).forEach(t => {
                if (t.docId === HIDDEN_DOC_ID && typeof t.text === 'string') {
                    const f = cleanFp(t.text);
                    if (f.length >= 8) fps.add(f);
                }
            })));
        });
        FINGERPRINTS = [...fps];
    } catch (e) {
        FINGERPRINTS = [];
    }
}

function sanitizeRuns(runs, rules, stats) {
    if (!runs || !runs.data || !Array.isArray(runs.data.records)) return;
    const before = runs.data.records.length;
    runs.data.records = runs.data.records.filter(r => {
        const t = String(r.title || '');
        if (DOC3_PAT.test(t)) return false;
        if (rules.some(([from]) => t.includes(from))) return false;
        return true;
    });
    stats.runs += before - runs.data.records.length;
    if (typeof runs.data.totalLogRows === 'number' && before > 0) {
        runs.data.totalLogRows = Math.round(runs.data.totalLogRows * (runs.data.records.length / before));
    }
}

/** 第一遍：从 scenarios 里找出哪些用例属于规范类，拿到 caseId */
function collectDoc3CaseIds(scenJson) {
    const ids = new Set();
    const evals = (scenJson.data && scenJson.data.eval) || [];
    evals.forEach(sc => {
        const cases = sc.result && sc.result.data && sc.result.data.result && sc.result.data.result.cases;
        (cases || []).forEach(c => { if (isDoc3Case(c)) ids.add(c.caseId); });
    });
    return ids;
}

/** 用原始用例库构建替换表（必须读脱敏前的备份，否则词条已被自己改掉） */
async function buildTermRules(backupDir, doc3Ids) {
    const rules = [];
    try {
        const raw = JSON.parse(await readFile(join(backupDir, 'eval-cases.json'), 'utf8'));
        for (const c of raw.data || []) {
            if (!doc3Ids.has(c.id)) continue;
            if (c.name && c.name.length >= 3) rules.push([c.name, NAME_PLACEHOLDER]);
            if (c.query && c.query.length >= 6) {
                rules.push([c.query, QUERY_PLACEHOLDER]);
                // 运行记录 / 日志里的标题会被截断，补两条前缀规则兜底
                for (const n of [8, 6]) {
                    const head = c.query.slice(0, n);
                    if (head.length >= 6) rules.push([head, '行业规范类问题']);
                }
            }
        }
    } catch (e) {
        // 没有备份就退化成不处理主题词（正文已经抹掉了）
    }
    rules.sort((a, b) => b[0].length - a[0].length);
    return rules;
}

export async function sanitizeDir(dir, { backupDir = null, quiet = false } = {}) {
    const stats = { fields: 0, docs: 0, cases: 0, matched: 0, runs: 0, terms: 0 };

    const files = (await readdir(dir)).filter(f => f.endsWith('.json'));
    const parsed = {};
    for (const f of files) parsed[f] = JSON.parse(await readFile(join(dir, f), 'utf8'));

    // 1. 找出规范类用例，构建词条屏蔽表
    const doc3Ids = parsed['scenarios.json'] ? collectDoc3CaseIds(parsed['scenarios.json']) : new Set();
    const rules = backupDir ? await buildTermRules(backupDir, doc3Ids) : [];
    if (backupDir) await loadFingerprints(backupDir);

    // 2. 抹正文 + 抹用例元信息
    if (parsed['scenarios.json']) {
        const data = parsed['scenarios.json'].data || {};
        Object.keys(data).forEach(key => {
            const list = data[key];
            if (!Array.isArray(list)) return;
            list.forEach(sc => {
                scrubNode(sc.result, stats);
                scrubNode(sc.sse, stats);
            });
        });
        ((data.eval) || []).forEach(sc => {
            const res = sc.result && sc.result.data && sc.result.data.result;
            if (res) sanitizeEvalResult(res, stats);
        });
    }

    // 3. 全量替换条款主题词（日志里会打印用例名）
    if (rules.length) {
        for (const name of Object.keys(parsed)) {
            if (name === 'scenarios.json') applyTermRules(parsed[name].data, rules, stats);
            else applyTermRules(parsed[name], rules, stats);
        }
    }

    // 4. 运行记录：丢掉规范问答那几条
    if (parsed['runs.json']) sanitizeRuns(parsed['runs.json'], rules, stats);

    // 5. 用例库
    if (parsed['eval-cases.json'] && Array.isArray(parsed['eval-cases.json'].data)) {
        for (const c of parsed['eval-cases.json'].data) {
            if (doc3Ids.has(c.id)) {
                c.query = QUERY_PLACEHOLDER;
                c.name = NAME_PLACEHOLDER;
                if (Array.isArray(c.expect)) c.expect = [['（已隐去）']];
                stats.terms++;
            }
        }
    }

    for (const f of files) {
        await writeFile(join(dir, f), JSON.stringify(parsed[f]), 'utf8');
    }

    if (!quiet) {
        console.log('\n== 脱敏 ==');
        console.log(`  规范类用例      ${doc3Ids.size} 条（caseId ${[...doc3Ids].join(', ') || '—'}）`);
        console.log(`  正文替换        ${stats.fields} 处`);
        console.log(`  文档名替换      ${stats.docs} 处`);
        console.log(`  关键词打码      ${stats.matched} 项`);
        console.log(`  主题词替换      ${stats.terms} 处`);
        console.log(`  运行记录丢弃    ${stats.runs} 条`);
    }
    return stats;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
    const ai = process.argv.indexOf('--dir');
    const dir = ai > -1 ? join(ROOT, process.argv[ai + 1]) : join(ROOT, 'src/main/resources/static/replay');
    const backup = join(ROOT, 'tools/.replay-backup');
    const hasBackup = await stat(backup).then(() => true).catch(() => false);
    await sanitizeDir(dir, { backupDir: hasBackup ? backup : null });
}
