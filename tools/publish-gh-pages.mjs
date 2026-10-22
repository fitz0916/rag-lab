#!/usr/bin/env node
/**
 * 把 site/ 推到 GitHub Pages（gh-pages 分支）。
 *
 * 用法：
 *   node tools/publish-gh-pages.mjs git@github.com:you/rag-lab.git
 *   node tools/publish-gh-pages.mjs https://github.com/you/rag-lab.git "提交说明"
 *
 * 做的事：
 *   1. 重新构建 site/（含密钥自检，不通过直接中止）
 *   2. 在 site/ 里初始化一个独立仓库，避免把源码和 .env 带上去
 *   3. 强推到 gh-pages 分支
 *
 * 推完之后去仓库 Settings → Pages，Source 选 gh-pages / (root)，等一分钟。
 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SITE = join(ROOT, 'site');

const repo = process.argv[2];
const message = process.argv[3] || 'publish: 静态回放站';

if (!repo) {
    console.error('用法：node tools/publish-gh-pages.mjs <仓库地址> [提交说明]');
    console.error('例：  node tools/publish-gh-pages.mjs git@github.com:you/rag-lab.git');
    process.exit(1);
}

const run = (cmd, args, opts = {}) => {
    console.log(`  $ ${cmd} ${args.join(' ')}`);
    return execFileSync(cmd, args, { cwd: opts.cwd || ROOT, stdio: 'inherit', ...opts });
};

console.log('\n[1/3] 构建静态站');
run(process.execPath, [join(ROOT, 'tools/build-site.mjs')]);

if (!existsSync(SITE)) {
    console.error('构建后没有 site/ 目录，中止');
    process.exit(1);
}

console.log('\n[2/3] 在 site/ 里初始化独立仓库');
if (!existsSync(join(SITE, '.git'))) {
    run('git', ['init', '-q'], { cwd: SITE });
    run('git', ['checkout', '-q', '-b', 'gh-pages'], { cwd: SITE });
} else {
    run('git', ['checkout', '-q', 'gh-pages'], { cwd: SITE });
}
run('git', ['add', '-A'], { cwd: SITE });
try {
    run('git', ['commit', '-q', '-m', message], { cwd: SITE });
} catch (e) {
    console.log('  （没有变更，跳过提交）');
}

console.log('\n[3/3] 推送到 gh-pages');
const remotes = execFileSync('git', ['remote'], { cwd: SITE, encoding: 'utf8' }).trim().split('\n').filter(Boolean);
if (!remotes.includes('origin')) {
    run('git', ['remote', 'add', 'origin', repo], { cwd: SITE });
} else {
    run('git', ['remote', 'set-url', 'origin', repo], { cwd: SITE });
}
run('git', ['push', '-f', 'origin', 'gh-pages'], { cwd: SITE });

const m = repo.match(/github\.com[:/]([^/]+)\/([^/.]+)/);
if (m) {
    const [, user, name] = m;
    console.log(`\n完成。等 1~2 分钟后访问：https://${user.toLowerCase()}.github.io/${name}/`);
    console.log(`如果 404，去 https://github.com/${user}/${name}/settings/pages 确认 Source 是 gh-pages / (root)\n`);
} else {
    console.log('\n已推送。到仓库的 Settings → Pages 里选择 gh-pages 分支即可。\n');
}
