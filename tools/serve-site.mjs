#!/usr/bin/env node
/**
 * 静态站预览服务器（零依赖）。
 *
 * 特意支持子路径：默认把 /rag-lab/ 映射到 site 根目录，
 * 用来模拟 https://<user>.github.io/rag-lab/ 的真实部署环境 ——
 * 相对路径写错的话这里就会 404，不用等推上去才发现。
 *
 * 用法：node tools/serve-site.mjs [port]
 */
import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { join, dirname, extname, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'site');
const PORT = Number(process.argv[2]) || 5173;
const BASE_PATH = '/rag-lab/';

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
    '.woff2': 'font/woff2'
};

function resolvePath(pathname) {
    let p = pathname;
    if (p.startsWith(BASE_PATH)) p = '/' + p.slice(BASE_PATH.length);
    else if (p === BASE_PATH.slice(0, -1)) p = '/';
    if (p.endsWith('/')) p += 'index.html';
    const safe = normalize(p).replace(/^([/\\])+/, '');
    const full = join(ROOT, safe);
    if (!full.startsWith(ROOT + sep)) return null;
    return full;
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const pathname = decodeURIComponent(url.pathname);
    const file = resolvePath(pathname);

    if (!file) {
        res.writeHead(403);
        res.end('forbidden');
        return;
    }

    try {
        const s = await stat(file);
        if (s.isDirectory()) {
            res.writeHead(302, { Location: pathname.replace(/\/?$/, '/') + 'index.html' });
            res.end();
            return;
        }
        const buf = await readFile(file);
        res.writeHead(200, {
            'Content-Type': MIME[extname(file).toLowerCase()] || 'application/octet-stream',
            'Content-Length': buf.length,
            'Cache-Control': 'no-store',
            'Access-Control-Allow-Origin': '*'
        });
        res.end(buf);
    } catch (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('404 ' + pathname);
    }
});

server.keepAliveTimeout = 5000;
server.listen(PORT, '127.0.0.1', () => {
    console.log(`静态站已启动`);
    console.log(`  根路径：  http://127.0.0.1:${PORT}/`);
    console.log(`  子路径：  http://127.0.0.1:${PORT}${BASE_PATH}   ← 模拟 GitHub Pages`);
});
