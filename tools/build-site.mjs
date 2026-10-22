#!/usr/bin/env node
/**
 * 把前端打包成一个可以丢到任意静态托管上的目录（GitHub Pages / Vercel / COS）。
 *
 * 产物结构：
 *   site/
 *     index.html  style.css  app.js  notes.js  demo-boot.js
 *     replay/*.json
 *     .nojekyll
 *
 * 关键点：只拷贝静态资源，永远不碰 .env、target/、src/main/java。
 * 打包完会自检一遍，确认产物里不含密钥和绝对路径。
 */
import { mkdir, cp, rm, writeFile, readdir, readFile, stat } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'src/main/resources/static');
const OUT = join(ROOT, 'site');

const FILES = ['index.html', 'style.css', 'app.js', 'notes.js', 'demo-boot.js'];
const DIRS = ['replay'];

async function main() {
    console.log('\n== 打包静态站 ==');
    await rm(OUT, { recursive: true, force: true });
    await mkdir(OUT, { recursive: true });

    for (const f of FILES) {
        await cp(join(SRC, f), join(OUT, f));
        const s = await stat(join(OUT, f));
        console.log(`  ${f.padEnd(16)} ${(s.size / 1024).toFixed(1)} KB`);
    }
    for (const d of DIRS) {
        await cp(join(SRC, d), join(OUT, d), { recursive: true });
        const names = await readdir(join(OUT, d));
        let bytes = 0;
        for (const n of names) {
            bytes += (await stat(join(OUT, d, n))).size;
        }
        console.log(`  ${d + '/'}`.padEnd(16) + `${(bytes / 1024).toFixed(1)} KB  (${names.length} 个文件)`);
    }

    // GitHub Pages 默认跑 Jekyll，会忽略下划线开头的文件，加这个直接当静态文件发
    await writeFile(join(OUT, '.nojekyll'), '');

    /* ------------------------------------------------ 自检 */
    console.log('\n== 自检 ==');
    let problems = 0;

    const html = await readFile(join(OUT, 'index.html'), 'utf8');
    const absRefs = [...html.matchAll(/(?:src|href)="(\/[^"]*)"/g)].map(m => m[1]);
    if (absRefs.length) {
        console.log('  ✗ index.html 还有绝对路径引用：' + absRefs.join(', '));
        problems++;
    } else {
        console.log('  ✓ 资源引用全是相对路径');
    }

    const mustHave = ['demo-boot.js', 'app.js', 'notes.js'];
    for (const f of mustHave) {
        if (!html.includes(f)) {
            console.log(`  ✗ index.html 没引用 ${f}`);
            problems++;
        }
    }
    const order = html.indexOf('demo-boot.js') < html.indexOf('app.js');
    console.log(order ? '  ✓ demo-boot.js 在 app.js 之前加载' : '  ✗ 加载顺序不对，拦截层会失效');
    if (!order) problems++;

    // 密钥扫描
    const secretPat = /sk-[A-Za-z0-9]{20,}|MYSQL_PASSWORD\s*=\s*\S{4,}|Thlg\d{3,}/;
    let scanned = 0;
    async function walk(dir) {
        for (const n of await readdir(dir)) {
            const p = join(dir, n);
            const s = await stat(p);
            if (s.isDirectory()) { await walk(p); continue; }
            if (!['.html', '.js', '.css', '.json'].includes(extname(n))) continue;
            scanned++;
            const text = await readFile(p, 'utf8');
            const m = text.match(secretPat);
            if (m) {
                console.log(`  ✗ ${p.replace(ROOT, '.')} 疑似含密钥：${m[0].slice(0, 20)}`);
                problems++;
            }
        }
    }
    await walk(OUT);
    console.log(`  ✓ 扫描 ${scanned} 个文件，未发现密钥` + (problems ? '（但上面有其它问题）' : ''));

    let total = 0;
    async function size(dir) {
        for (const n of await readdir(dir)) {
            const p = join(dir, n);
            const s = await stat(p);
            if (s.isDirectory()) await size(p);
            else total += s.size;
        }
    }
    await size(OUT);

    console.log(`\n产物目录：${OUT}`);
    console.log(`总体积：${(total / 1024).toFixed(0)} KB（gzip 后约 ${(total / 1024 / 3.2).toFixed(0)} KB）`);
    if (problems) {
        console.log(`\n有 ${problems} 个问题需要处理后再发布\n`);
        process.exit(1);
    }
    console.log('\n可以发布了。本地预览：npx serve site  或  python -m http.server -d site\n');
}

main().catch(e => { console.error(e); process.exit(1); });
