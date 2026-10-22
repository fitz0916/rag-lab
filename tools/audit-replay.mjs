#!/usr/bin/env node
/** 分析 replay 数据里 doc3（行业规范 PDF）的暴露程度 */
import { readFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DIR = join(ROOT, 'src/main/resources/static/replay');
const load = async n => JSON.parse(await readFile(join(DIR, n), 'utf8'));

const sc = (await load('scenarios.json')).data;

function walk(node, fn, path = '$') {
    if (typeof node === 'string') return fn(path, node);
    if (Array.isArray(node)) return node.forEach((v, i) => walk(v, fn, `${path}[${i}]`));
    if (node && typeof node === 'object') {
        for (const k of Object.keys(node)) walk(node[k], fn, `${path}.${k}`);
    }
}

const PAT = /Q\/?SY\s*01015|油气田|附\s*录\s*E|采集深度应执行|01015/;

for (const key of Object.keys(sc)) {
    const list = sc[key];
    console.log(`\n=== ${key} (${list.length} 个场景) ===`);
    for (const s of list) {
        let hits = 0, chars = 0, paths = new Set();
        walk(s, (p, v) => {
            if (PAT.test(v)) {
                hits++;
                chars += v.length;
                const seg = p.split('.').slice(-2).join('.');
                paths.add(seg);
            }
        });
        // 统计结果里出现的 docName
        const docNames = new Set();
        walk(s.result, (p, v) => {
            if (/\.docName$/.test(p)) docNames.add(v.slice(0, 30));
        });
        if (hits) {
            console.log(`  ${s.id.padEnd(6)} 命中 ${String(hits).padStart(3)} 处 / ${String(chars).padStart(5)} 字  ${[...paths].slice(0, 4).join(', ')}`);
            console.log(`         结果含文档：${[...docNames].join(' | ') || '—'}`);
        } else {
            console.log(`  ${s.id.padEnd(6)} 干净  结果含文档：${[...docNames].join(' | ') || '—'}`);
        }
    }
}

// 单独看最危险的一份：doc3 切片正文是否原样出现
console.log('\n=== 抽样：命中片段最长的 3 处 ===');
const samples = [];
for (const key of Object.keys(sc)) {
    for (const s of sc[key]) {
        walk(s, (p, v) => { if (PAT.test(v)) samples.push({ id: s.id, p, v }); });
    }
}
samples.sort((a, b) => b.v.length - a.v.length);
samples.slice(0, 3).forEach(x => {
    console.log(`\n  [${x.id}] ${x.p}  长度 ${x.v.length}`);
    console.log('  ' + x.v.slice(0, 260).replace(/\n/g, ' ⏎ '));
});
