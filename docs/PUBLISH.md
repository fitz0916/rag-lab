# 发布为静态回放站

把 RAG Lab 的 9 个阶段发布成一个**纯静态站点**，别人打开就能看完整的入库、检索、重排、压缩、评测、生成、改写、缓存过程，包括右侧那个黑窗口里的逐条日志。

但站点里**没有后端、没有数据库、没有 API Key** —— 所有 `/api` 请求都被浏览器里的拦截层接住，直接读本地 JSON。

---

## 一、为什么这样不会泄露

B/S 架构下，浏览器本来就拿不到服务端的环境变量。真正的风险从来不是「打开网页看到 Key」，而是这四件事：

| 真实风险 | 这里怎么解决 |
|---|---|
| 别人调用 `/api/generate` 刷你的账单 | 站点里根本没有后端，请求不出浏览器 |
| 别人改你的知识库 | 同上 |
| 行业规范 PDF 的正文被公开 | 录制脚本只录 doc1，脱敏脚本把 PDF 内容全换成占位符 |
| 源码仓库里的明文密码 | `.env` 已进 `.gitignore`，`application.yml` 不再带默认密码 |

拦截层只替换了两个东西：`window.fetch` 和 `window.EventSource`。整个 `app.js`（1500 行）一行没改。

---

## 二、三个脚本

```
tools/
├── record-demo.mjs       录制：连本地 8080，把 9 个阶段的响应和 SSE 事件存成 JSON
├── sanitize-replay.mjs   脱敏：抹掉行业规范 PDF 的正文，保留真实指标
├── build-site.mjs        打包：把静态资源拷进 site/ 并自检
├── serve-site.mjs        预览：本地起服务器，模拟 /rag-lab/ 子路径
├── verify-site.mjs       自检：在 Node 沙箱里跑通 48 项断言
└── publish-gh-pages.mjs  发布：构建 + 推 gh-pages 分支
```

### 录制

先确保本地 8080 跑着，且知识库里有 doc1（89 片）：

```bash
node tools/record-demo.mjs
```

它会：

1. 读 `/api/ingest/docs`、`/api/ingest/docs/1/chunks`、89 个切片详情
2. 订阅**已结束任务**的 SSE，让后端把历史日志回放一遍 —— **不会重新入库，Chroma 不会被写脏**
3. 对每个阶段跑一次真实请求（阶段六会真跑 15 用例 × 5 策略，约 45 秒）
4. 阶段九按前端逻辑跑 MISS → L1 → L2 → L3 四步
5. 落盘后**自动备份原始数据并脱敏**

输出约 820 KB（gzip 后约 250 KB）。

### 脱敏做了什么

- 规范类用例的 `top[].text`（切片正文）→ `（行业标准原文，已隐去）`
- 日志里的 `Top1: xxx` 片段 → 截断
- 评测指标（Hit@1/3/K、MRR、覆盖率）**一个数字都不改** —— 学习者要看的就是真实表现
- 用例库和运行记录里的条款主题词 → 中性占位

备份留在 `tools/.replay-backup/`（已 gitignore），脱敏靠它取词表。

### 打包与预览

```bash
node tools/build-site.mjs            # 产物在 site/
node tools/serve-site.mjs 5173       # 打开 http://127.0.0.1:5173/rag-lab/
```

`serve-site.mjs` 特意把 `/rag-lab/` 映射到站点根目录，用来模拟 GitHub Pages 的子路径。**相对路径写错的话这里就会 404**，不用等推上去才发现。

### 自检

```bash
node tools/verify-site.mjs http://127.0.0.1:5173/rag-lab/
```

在 Node 的 vm 沙箱里加载 `demo-boot.js`，把每个阶段的 `POST → SSE → GET` 链路真跑一遍，覆盖 48 项断言，包括缓存四步是否真的依次 MISS/L1/L2/L3。

---

## 三、发布到 GitHub Pages

```bash
node tools/publish-gh-pages.mjs git@github.com:你的用户名/rag-lab.git
```

脚本会：重新构建 → 在 `site/` 里初始化**独立仓库**（避免把源码和 `.env` 带上去）→ 强推 `gh-pages` 分支。

推完后去 `Settings → Pages`，Source 选 `gh-pages` / `(root)`，等一分钟。

访问地址：`https://你的用户名.github.io/rag-lab/`

> 注意：用户名的 GitHub Pages 地址是小写的。

---

## 四、本地开发不受影响

`demo-boot.js` 按这个顺序判断：

| 场景 | 行为 |
|---|---|
| `localhost:8080` 或 `127.0.0.1:8080` | **不拦截**，走真后端（你日常开发） |
| URL 带 `?live=1` | 不拦截，强制真后端 |
| URL 带 `?demo=1` | 强制回放 |
| 其它（github.io 等） | 回放 |

所以同一份 `index.html` 既是开发页也是演示页，不用维护两份。

---

## 五、数据更新

改了切片参数、加了文档、调了 prompt 之后：

```bash
node tools/record-demo.mjs      # 重新录制（会覆盖 replay/）
node tools/build-site.mjs
node tools/publish-gh-pages.mjs <仓库地址> "更新演示数据"
```

---

## 六、常见问题

**页面白屏 / 数据加载失败**
`replay/` 没打包进去，或者路径不对。检查浏览器 Network 里 `replay/scenarios.json` 是不是 404。

**9 个 tab 能点，但点「开始检索」没反应**
`demo-boot.js` 没在 `app.js` 之前加载。`build-site.mjs` 的自检会检查这个顺序。

**想临时换成真后端演示**
在能访问 8080 的机器上打开 `http://你的地址/?live=1`。

**体积还能再小吗**
`scenarios.json` 占了 700 KB，主要是评测的 148 条日志。如果只想留指标，可以把评测的 `sse` 删掉，体积能降到 300 KB 以内，但黑窗口就没内容滚了。
