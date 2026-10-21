/* ============================================================
   RAG Lab · 阶段一 前端逻辑
   核心：提交任务 → 订阅 SSE → 逐条渲染日志（含错误诊断展开）
   ============================================================ */

const STAGE_LABEL = {
    // 阶段一：入库
    RECEIVE: '接收',
    HASH: '指纹',
    SAVE_DOC: '落库',
    PARSE: '解析',
    CLEAN: '清洗',
    SPLIT: '切片',
    CHROMA_INIT: '建集合',
    EMBED: '向量化',
    CHROMA_WRITE: '写库',
    VERIFY: '校验',
    DONE: '完成',
    // 阶段二：检索
    QUERY_RECEIVE: '接收问题',
    QUERY_EMBED: '问题向量化',
    VECTOR_SEARCH: '向量检索',
    SCORE_RANK: '打分排序',
    HIT_LIST: '命中列表',
    QUERY_DONE: '完成',
    // 阶段三：混合检索
    HYBRID_RECEIVE: '接收问题',
    VEC_RECALL: '向量召回',
    BM25_RECALL: 'BM25召回',
    RRF_FUSE: 'RRF融合',
    HYBRID_RESULT: '融合结果',
    HYBRID_DONE: '完成',
    // 阶段四：重排序
    RERANK_RECALL: '混合召回',
    RERANK_SCORE: '重排打分',
    RERANK_REORDER: '按分重排',
    RERANK_RESULT: '重排结果',
    RERANK_DONE: '完成',
    // 阶段五：上下文控制
    CONTEXT_RECALL: '召回重排',
    CONTEXT_SPLIT: '拆句',
    CONTEXT_SCORE: '句子打分',
    CONTEXT_SELECT: '选句去重',
    CONTEXT_BUILD: '组装上下文',
    CONTEXT_DONE: '完成',

    // 阶段六：评测
    EVAL_LOAD: '载入测试集',
    EVAL_CASE: '逐用例检索',
    EVAL_SCORE: '计算指标',
    EVAL_AGGREGATE: '汇总对比',
    EVAL_DONE: '完成',

    // 阶段八：查询重写
    REWRITE_GEN: '生成变体',
    REWRITE_RECALL: '多变体召回',
    REWRITE_MERGE: '跨变体融合',
    REWRITE_RERank: '融合后重排',
    REWRITE_DONE: '完成',

    // 阶段七：生成
    GEN_CONTEXT: '准备上下文',
    GEN_PROMPT: '拼 Prompt',
    GEN_CALL: '调大模型',
    GEN_CITE: '标注依据',
    GEN_DONE: '完成'
};

const state = {
    tab: 'file',
    pickedFile: null,
    es: null,
    taskId: null,
    docId: null,
    autoScroll: true,
    logCount: 0,
    errorCount: 0,
    pollTimer: null
};

const $ = (id) => document.getElementById(id);

/* ---------------- 健康检查 ---------------- */
async function checkHealth() {
    try {
        const res = await fetch('/api/health').then(r => r.json());
        if (!res.ok) {
            return;
        }
        const d = res.data;
        ['mysql', 'chroma', 'qwen'].forEach(k => {
            const el = document.querySelector(`.pill[data-key="${k}"]`);
            if (!el) return;
            el.classList.toggle('ok', !!d[k].ok);
            el.classList.toggle('bad', !d[k].ok);
            el.querySelector('em').textContent = d[k].ok
                ? (k === 'mysql' ? '已连接' : k === 'chroma' ? '在线' : 'Key 就绪')
                : '异常';
            el.title = d[k].detail || '';
        });
        if (d.config) {
            if (!$('chunkSize').value) $('chunkSize').placeholder = d.config.chunkSize;
            if (!$('chunkOverlap').value) $('chunkOverlap').placeholder = d.config.chunkOverlap;

            // 把「用的什么模型」直接摆在页面上，不用去翻配置文件
            $('piModel').textContent =
                `${d.config.embeddingModel} · ${d.config.dimensions} 维`;
            $('piChroma').textContent =
                `Chroma · ${d.config.chromaCollection}`;
            $('piChunk').textContent =
                `size ${d.config.chunkSize} / overlap ${d.config.chunkOverlap}`;
        }
    } catch (e) {
        console.warn('健康检查失败', e);
    }
}

/* ---------------- Tab ---------------- */
document.querySelectorAll('.tab[data-tab]').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.tab[data-tab]').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        state.tab = btn.dataset.tab;
        $('pane-file').classList.toggle('hidden', state.tab !== 'file');
        $('pane-text').classList.toggle('hidden', state.tab !== 'text');
    });
});

document.querySelectorAll('.tab[data-result]').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.tab[data-result]').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        const isChunks = btn.dataset.result === 'chunks';
        $('chunkPane').classList.toggle('hidden', !isChunks);
        $('docPane').classList.toggle('hidden', isChunks);
        if (!isChunks) loadDocs();
    });
});

/* ---------------- 文件选择 ---------------- */
const dz = $('dropzone');
const fileInput = $('fileInput');

dz.addEventListener('click', () => fileInput.click());
fileInput.addEventListener('change', () => {
    if (fileInput.files.length) setFile(fileInput.files[0]);
});

['dragenter', 'dragover'].forEach(ev => dz.addEventListener(ev, e => {
    e.preventDefault();
    dz.classList.add('dragover');
}));
['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => {
    e.preventDefault();
    dz.classList.remove('dragover');
}));
dz.addEventListener('drop', e => {
    if (e.dataTransfer.files.length) setFile(e.dataTransfer.files[0]);
});

function setFile(f) {
    state.pickedFile = f;
    $('pickedFile').textContent = `已选择：${f.name}  (${(f.size / 1024).toFixed(1)} KB)`;
}

/* ---------------- 开始入库 ---------------- */
$('btnStart').addEventListener('click', async () => {
    const size = $('chunkSize').value.trim();
    const overlap = $('chunkOverlap').value.trim();

    let url, options;
    if (state.tab === 'file') {
        if (!state.pickedFile) {
            alert('请先选择文件');
            return;
        }
        const fd = new FormData();
        fd.append('file', state.pickedFile);
        if (size) fd.append('chunkSize', size);
        if (overlap) fd.append('chunkOverlap', overlap);
        url = '/api/ingest/file';
        options = { method: 'POST', body: fd };
    } else {
        const name = $('textName').value.trim() || ('文本-' + Date.now());
        const text = $('textBody').value;
        if (!text.trim()) {
            alert('请先粘贴文本');
            return;
        }
        const params = new URLSearchParams({ name, text });
        if (size) params.append('chunkSize', size);
        if (overlap) params.append('chunkOverlap', overlap);
        url = '/api/ingest/text';
        options = {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        };
    }

    setBusy(true);
    clearLog();
    try {
        const res = await fetch(url, options).then(r => r.json());
        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusy(false);
            return;
        }
        state.taskId = res.data.taskId;
        $('logTaskId').textContent = 'task ' + state.taskId.slice(0, 8);
        $('taskCard').style.display = '';
        $('taskError').classList.add('hidden');
        subscribe(state.taskId, () => {
            setBusy(false);
            stopPolling();
            pollTask(state.taskId);
        });
        startPolling(state.taskId);
    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusy(false);
    }
});

function setBusy(busy) {
    const btn = $('btnStart');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '执行中…' : '开始入库';
}

/* ---------------- SSE 订阅 ---------------- */
/**
 * 订阅 SSE 日志流。阶段一（入库）和阶段二（检索）共用这一套渲染。
 *
 * @param {string}   id     入库 taskId 或检索 traceId
 * @param {function} onEnd  流结束后的回调（不同阶段要刷新的东西不一样）
 * @param {string}   [url]  自定义流地址，默认走入库那个端点
 */
function subscribe(id, onEnd, url) {
    if (state.es) {
        state.es.close();
        state.es = null;
    }
    const es = new EventSource(url || `/api/ingest/tasks/${id}/stream`);
    state.es = es;
    setConn('connecting');

    es.addEventListener('log', e => {
        try {
            appendLog(JSON.parse(e.data));
        } catch (err) {
            console.error('日志解析失败', err, e.data);
        }
    });

    es.addEventListener('end', () => {
        setConn('done');
        es.close();
        state.es = null;
        if (onEnd) {
            onEnd();
        } else {
            setBusy(false);
            stopPolling();
            pollTask(id); // 最后刷一次状态
        }
    });

    es.onerror = () => {
        // 浏览器会自动重连；任务已结束时我们主动 close，不会走到这里
        setConn('retry');
    };
}

function setConn(s) {
    const el = $('logConn');
    el.className = 'conn';
    if (s === 'live') {
        el.classList.add('live');
        el.textContent = '● 实时接收';
    } else if (s === 'done') {
        el.classList.add('live');
        el.textContent = '● 已结束';
    } else if (s === 'retry') {
        el.classList.add('dead');
        el.textContent = '● 重连中';
    } else {
        el.textContent = '连接中…';
    }
}

/* ---------------- 任务状态轮询 ---------------- */
function startPolling(taskId) {
    stopPolling();
    pollTask(taskId);
    state.pollTimer = setInterval(() => pollTask(taskId), 1200);
}

function stopPolling() {
    if (state.pollTimer) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }
}

async function pollTask(taskId) {
    try {
        const res = await fetch(`/api/ingest/tasks/${taskId}`).then(r => r.json());
        if (!res.ok) return;
        const t = res.data;
        renderTask(t);
        if (t.status === 'SUCCESS' || t.status === 'FAILED') {
            stopPolling();
            setBusy(false);
            state.docId = t.docId;
            if (t.status === 'SUCCESS' && t.docId) loadChunks(t.docId);
        }
    } catch (e) {
        // 静默
    }
}

function renderTask(t) {
    const status = $('taskStatus');
    status.textContent = t.status;
    status.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    $('statStage').textContent = STAGE_LABEL[t.currentStage] || t.currentStage || '—';
    $('statChunks').textContent = t.totalChunks || 0;
    $('statVectors').textContent = t.storedVectors || 0;
    $('statCost').textContent = t.costMs ? (t.costMs / 1000).toFixed(1) + 's' : '—';

    const total = t.totalChunks || 0;
    const done = t.status === 'SUCCESS' ? total : Math.min(t.storedVectors || 0, total);
    const pct = total > 0 ? Math.round(done / total * 100) : (t.status === 'SUCCESS' ? 100 : 8);
    $('progressBar').style.width = pct + '%';

    $('taskMeta').textContent = `docId=${t.docId ?? '—'}  ·  任务 ${t.id.slice(0, 8)}  ·  ${t.docName || ''}`;

    if (t.status === 'FAILED') {
        $('taskError').classList.remove('hidden');
        $('taskError').textContent =
            `失败步骤：${t.errorStage || '未知'}（${STAGE_LABEL[t.errorStage] || ''}）\n` +
            `原因：${t.errorMsg || '见右侧日志'}\n` +
            `完整诊断已打印在右侧黑窗口的 ERROR 行，点开可看排查命令。`;
    }
}

/* ---------------- 日志渲染 ---------------- */
const logBody = $('logBody');

function appendLog(ev) {
    const line = document.createElement('div');
    line.className = `log-line ${ev.level} new`;
    if (ev.detail) line.classList.add('has-detail');

    const t = document.createElement('span');
    t.className = 't';
    t.textContent = ev.ts || '';

    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = ev.level;

    const stage = document.createElement('span');
    stage.className = 'stage';
    stage.textContent = STAGE_LABEL[ev.stage] || ev.stage;

    const msg = document.createElement('span');
    msg.className = 'msg';
    msg.textContent = ev.message || '';

    const cost = document.createElement('span');
    cost.className = 'cost';
    cost.textContent = ev.costMs > 0 ? ev.costMs + 'ms' : '';

    line.append(t, badge, stage, msg, cost);

    if (ev.detail) {
        const detail = document.createElement('div');
        detail.className = 'log-detail';
        detail.textContent = ev.detail;
        line.appendChild(detail);

        // ERROR / WARN 默认展开，其余折叠，点一下切换
        const expandedByDefault = ev.level === 'ERROR';
        if (!expandedByDefault) detail.style.display = 'none';
        line.addEventListener('click', () => {
            detail.style.display = detail.style.display === 'none' ? 'block' : 'none';
        });
    }

    logBody.appendChild(line);

    // 首条日志时清掉欢迎屏
    const welcome = logBody.querySelector('.log-welcome');
    if (welcome) welcome.remove();

    state.logCount++;
    if (ev.level === 'ERROR') state.errorCount++;
    updateLogStats();

    if (state.autoScroll) {
        logBody.scrollTop = logBody.scrollHeight;
    }

    // 移除动画类，避免后续重排
    setTimeout(() => line.classList.remove('new'), 220);
}

function appendLocalError(text) {
    appendLog({
        seq: -1,
        ts: new Date().toLocaleTimeString('zh-CN', { hour12: false }) + '.' +
            String(new Date().getMilliseconds()).padStart(3, '0'),
        level: 'ERROR',
        stage: 'CLIENT',
        message: text,
        costMs: 0,
        detail: '这是浏览器侧的错误，请求还没到服务端。常见原因：服务没启动、端口不对、被 CORS 拦截。'
    });
}

function updateLogStats() {
    $('logCount').textContent = state.logCount + ' 条';
    const e = $('logErrors');
    e.textContent = state.errorCount + ' 错误';
    e.classList.toggle('has', state.errorCount > 0);
}

function clearLog() {
    logBody.innerHTML = '';
    state.logCount = 0;
    state.errorCount = 0;
    updateLogStats();
    setConn('live');
}

/* ---------------- 日志工具条 ---------------- */
$('btnAuto').addEventListener('click', () => {
    state.autoScroll = !state.autoScroll;
    $('btnAuto').classList.toggle('on', state.autoScroll);
});

$('btnClear').addEventListener('click', clearLog);

$('btnCopy').addEventListener('click', async () => {
    const text = [...logBody.querySelectorAll('.log-line')].map(line => {
        const t = line.querySelector('.t')?.textContent || '';
        const b = line.querySelector('.badge')?.textContent || '';
        const s = line.querySelector('.stage')?.textContent || '';
        const m = line.querySelector('.msg')?.textContent || '';
        const d = line.querySelector('.log-detail')?.textContent || '';
        return `${t} [${b}] [${s}] ${m}${d ? '\n' + d : ''}`;
    }).join('\n');
    try {
        await navigator.clipboard.writeText(text);
        $('btnCopy').textContent = '✓ 已复制';
        setTimeout(() => $('btnCopy').textContent = '⧉ 复制', 1500);
    } catch (e) {
        alert('复制失败，请手动选中');
    }
});

/* ---------------- 结果区 ---------------- */
async function loadChunks(docId) {
    try {
        const res = await fetch(`/api/ingest/docs/${docId}/chunks?limit=200`).then(r => r.json());
        if (!res.ok || !res.data.length) return;
        const chunks = res.data;
        const pane = $('chunkPane');
        pane.innerHTML = '';

        // ---- 顶部统计条：一眼看出切片粒度 ----
        const total = chunks.length;
        const sum = chunks.reduce((a, c) => a + c.charCount, 0);
        const max = Math.max(...chunks.map(c => c.charCount));
        const min = Math.min(...chunks.map(c => c.charCount));
        const bar = document.createElement('div');
        bar.className = 'chunk-summary';
        bar.innerHTML = `<span>共 <b>${total}</b> 片</span>
            <span>总 <b>${sum}</b> 字</span>
            <span>平均 <b>${Math.round(sum / total)}</b> 字</span>
            <span>最长 <b>${max}</b> · 最短 <b>${min}</b></span>
            <span style="margin-left:auto;color:#9aa3af">点任意切片看完整正文 + 向量</span>`;
        pane.appendChild(bar);

        // ---- 按 Markdown 标题分组：能看出「标题 → 段落 → 切片」的结构 ----
        const counts = {};
        chunks.forEach(c => {
            const k = c.heading || '（无标题段落）';
            counts[k] = (counts[k] || 0) + 1;
        });

        let lastHeading = '\u0000';
        chunks.forEach(c => {
            const key = c.heading || '（无标题段落）';
            if (key !== lastHeading) {
                lastHeading = key;
                const g = document.createElement('div');
                g.className = 'group-head';
                g.innerHTML = `<span>${escapeHtml(key)}</span><span class="count">${counts[key]} 片</span>`;
                pane.appendChild(g);
            }

            const div = document.createElement('div');
            div.className = 'chunk-item clickable';
            div.innerHTML = `
                <div class="ci-head">
                    <span class="ci-idx">#${c.chunkIndex}</span>
                    <span>${c.charCount} 字</span>
                    <span>${c.chromaId}</span>
                    <span>${c.status}</span>
                </div>
                <div class="ci-preview">${escapeHtml((c.content || '').slice(0, 120))}</div>
                <span class="ci-open">🔍 查看完整切片 + 向量</span>`;
            div.addEventListener('click', () => openChunkDetail(docId, c.chunkIndex));
            pane.appendChild(div);
        });
    } catch (e) {
        console.warn('加载切片失败', e);
    }
}

/* ---------------- 切片解剖抽屉 ---------------- */
async function openChunkDetail(docId, index) {
    $('drawer').classList.remove('hidden');
    $('drawerMask').classList.remove('hidden');
    $('dwTitle').textContent = `切片 #${index}`;
    $('dwSub').textContent = '读取中…';
    $('dwBody').innerHTML = '<div class="empty">正在从 MySQL 取正文、从 Chroma 取向量…</div>';
    try {
        const res = await fetch(`/api/ingest/docs/${docId}/chunks/${index}`).then(r => r.json());
        if (!res.ok) {
            $('dwBody').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        renderChunkDetail(res.data);
    } catch (e) {
        $('dwBody').innerHTML = `<div class="empty">请求失败：${escapeHtml(e.message)}</div>`;
    }
}

function renderChunkDetail(d) {
    $('dwTitle').textContent = `切片 #${d.chunkIndex}`;
    $('dwSub').textContent = `${d.docName} · ${d.chromaId}`;

    const v = d.vector || {};
    const parts = [];

    // ---- 1. 元信息 ----
    parts.push(`
        <div class="dw-section">
            <h4>元信息 <span class="tag">来自 MySQL kb_chunk</span></h4>
            <div class="meta-grid">
                <div class="meta-cell"><small>所属标题</small><span>${escapeHtml(d.heading || '—')}</span></div>
                <div class="meta-cell"><small>字符数</small><span>${d.charCount}</span></div>
                <div class="meta-cell"><small>估算 token</small><span>${d.tokenEst}</span></div>
                <div class="meta-cell"><small>向量库主键</small><span>${escapeHtml(d.chromaId)}</span></div>
                <div class="meta-cell"><small>落库状态</small><span>${escapeHtml(d.status)}</span></div>
                <div class="meta-cell"><small>collection</small><span>${escapeHtml(d.collection || '—')}</span></div>
            </div>
        </div>`);

    // ---- 2. 正文（overlap 高亮）----
    let bodyHtml;
    if (d.overlapChars > 0) {
        const ov = escapeHtml(d.content.slice(0, d.overlapChars));
        const rest = escapeHtml(d.content.slice(d.overlapChars));
        bodyHtml = `<span class="ov-badge">↓ 黄色这 ${d.overlapChars} 字与上一片（#${d.chunkIndex - 1}）重复</span>
            <div class="chunk-text"><span class="ov-hl">${ov}</span>${rest}</div>`;
    } else {
        bodyHtml = `<div class="chunk-text">${escapeHtml(d.content)}</div>`;
    }
    parts.push(`
        <div class="dw-section">
            <h4>完整正文 <span class="tag">${d.charCount} 字</span></h4>
            ${bodyHtml}
            <p class="dw-note">
                ${d.overlapChars > 0
                    ? `<b>为什么要重叠？</b>如果一句话正好被切在边界上，前一片有上半句、后一片有下半句，
                       命中任意一片都能读到完整意思。这 <b>${d.overlapChars}</b> 字是切片时按
                       <b>overlap</b> 配置补进来的前缀，而且会尽量从整句边界开始，不会补半句话。`
                    : `<b>这一片没有 overlap。</b>它是文档的第一片，或者上一片的结尾正好落在段落边界上，
                       不需要补前缀。`}
            </p>
        </div>`);

    // ---- 3. 向量 ----
    if (v.stored) {
        const head = v.head || [];
        const maxAbs = Math.max(...head.map(x => Math.abs(x)), 0.0001);
        const bars = head.map((val, i) => {
            const pct = (Math.abs(val) / maxAbs) * 50;
            const cls = val >= 0 ? 'pos' : 'neg';
            return `<div class="vb-row">
                    <span class="vb-idx">${i}</span>
                    <div class="vb-track"><div class="vb-fill ${cls}" style="width:${pct}%"></div></div>
                    <span class="vb-val">${val}</span>
                </div>`;
        }).join('');

        parts.push(`
            <div class="dw-section">
                <h4>向量 <span class="tag">从 Chroma 实时读回</span></h4>
                <div class="vec-stats">
                    <span>维度 <b>${v.dimensions}</b></span>
                    <span>L2 范数 <b>${v.norm}</b></span>
                    <span>min <b>${v.min}</b></span>
                    <span>max <b>${v.max}</b></span>
                    <span>均值 <b>${v.mean}</b></span>
                </div>
                <div class="vec-bars">${bars}</div>
                <div class="vec-head-dump">
                    <b>前 16 维原始值：</b><br>[ ${head.join(', ')} … ]<br>
                    尾部 4 维：${(v.tail || []).join(', ')}
                </div>
                <p class="dw-note">
                    <b>这 ${v.dimensions} 个浮点数是什么？</b>Qwen <code>text-embedding-v4</code> 把这段
                    ${d.charCount} 字的文本映射成 ${v.dimensions} 维空间里的一个点。这个点就是「语义坐标」——
                    语义越接近的文本，坐标之间的距离越近。检索时把问题也变成同样维度的点，
                    算一下距离就能找到最近的切片。<br>
                    <b>蓝条向右 = 正值，橙条向左 = 负值</b>，长度按绝对值的最大值归一化。
                    范数接近 1 说明做了归一化，此时余弦相似度等价于点积。
                </p>
            </div>`);
    } else {
        parts.push(`
            <div class="dw-section">
                <h4>向量 <span class="tag">未读到</span></h4>
                <div class="empty" style="padding:16px">
                    ${escapeHtml(v.note || 'Chroma 里没有读到这条向量')}
                </div>
                <p class="dw-note">
                    正常情况下这里会显示从 Chroma 读回的 ${v.dimensions || 1024} 维向量。
                    读不到通常说明：集合被删/重建过，或者这条切片在写入阶段失败了（看状态是不是 STORED）。
                </p>
            </div>`);
    }

    // ---- 4. 上下文位置 ----
    parts.push(`
        <div class="dw-section">
            <h4>它在文档里的位置</h4>
            <div class="meta-grid">
                <div class="meta-cell"><small>文档</small><span>#${d.docId} ${escapeHtml(d.docName)}</span></div>
                <div class="meta-cell"><small>序号</small><span>第 ${d.chunkIndex + 1} 片</span></div>
                <div class="meta-cell"><small>相邻</small><span>
                    ${d.chunkIndex > 0 ? `<a href="#" onclick="openChunkDetail(${d.docId},${d.chunkIndex - 1});return false">← 上一片</a>` : '—'}
                    &nbsp;/&nbsp;
                    <a href="#" onclick="openChunkDetail(${d.docId},${d.chunkIndex + 1});return false">下一片 →</a>
                </span></div>
            </div>
        </div>`);

    $('dwBody').innerHTML = parts.join('');
    $('dwBody').scrollTop = 0;
}

function closeDrawer() {
    $('drawer').classList.add('hidden');
    $('drawerMask').classList.add('hidden');
}

$('dwClose').addEventListener('click', closeDrawer);
$('drawerMask').addEventListener('click', () => {
    closeDrawer();
    closeRuns();
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
        closeDrawer();
        closeRuns();
    }
});

async function loadDocs() {
    try {
        const res = await fetch('/api/ingest/docs?limit=30').then(r => r.json());
        const pane = $('docPane');
        if (!res.ok || !res.data.length) {
            pane.innerHTML = '<div class="empty">还没有入库过文档</div>';
            return;
        }
        pane.innerHTML = '';
        res.data.forEach(d => {
            const div = document.createElement('div');
            div.className = 'doc-item';
            div.innerHTML = `
                <span class="doc-name"><span class="dot-s ${d.status}"></span>${escapeHtml(d.docName)}</span>
                <span class="doc-meta">${d.chunkCount} 片 / ${d.vectorCount} 向量 · ${d.status}</span>`;
            div.addEventListener('click', () => {
                document.querySelectorAll('.tab[data-result]').forEach(b => b.classList.remove('active'));
                document.querySelector('.tab[data-result="chunks"]').classList.add('active');
                $('chunkPane').classList.remove('hidden');
                $('docPane').classList.add('hidden');
                loadChunks(d.id);
            });
            pane.appendChild(div);
        });
    } catch (e) {
        console.warn('加载文档列表失败', e);
    }
}

function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function escapeAttr(s) {
    return escapeHtml(s).replace(/\n/g, ' ');
}

/* ---------------- 初始化 ---------------- */
checkHealth();
setInterval(checkHealth, 20000);
loadDocs();

// 支持 ?task=<taskId> 直接回放某次任务的全过程日志（刷新/分享都用得上）
// 支持 ?doc=<docId>&chunk=<index> 直接打开某个切片的解剖抽屉
const params = new URLSearchParams(location.search);
const urlTask = params.get('task');
if (urlTask) {
    state.taskId = urlTask;
    $('logTaskId').textContent = 'task ' + urlTask.slice(0, 8);
    $('taskCard').style.display = '';
    subscribe(urlTask, () => {
        setBusy(false);
        stopPolling();
        pollTask(urlTask);
    });
    startPolling(urlTask);
}

const urlDoc = params.get('doc');
if (urlDoc) {
    loadChunks(Number(urlDoc));
    const urlChunk = params.get('chunk');
    if (urlChunk !== null && urlChunk !== '') {
        openChunkDetail(Number(urlDoc), Number(urlChunk));
    }
}

/* ============================================================
   阶段切换
   ============================================================ */
const STAGE_TIPS = {
    1: '阶段一：先把知识灌进向量库',
    2: '阶段二：把问题变成向量，去库里找最像的几片',
    3: '阶段三：向量 + BM25 两路召回，RRF 融合排名',
    4: '阶段四：召回 Top-N，再让重排模型逐条判断相关性',
    5: '阶段五：只保留真正回答问题的句子，压成可直接用的上下文',
    6: '阶段六：同一批问题跑所有策略，用 Hit@K / MRR 判断优化到底有没有用',
    7: '阶段七：把上下文拼进 Prompt，生成带引用的答案 —— 这一步才是用户看到的东西',
    8: '阶段八：把问题换几种说法分别检索，再看融合 —— 唯一每次都要多花 LLM 钱的一步'
};

const STAGE_BADGE = {
    1: '阶段一 · 切片入库',
    2: '阶段二 · 向量检索',
    3: '阶段三 · 混合检索',
    4: '阶段四 · 重排序',
    5: '阶段五 · 上下文控制',
    6: '阶段六 · 评测',
    7: '阶段七 · 生成',
    8: '阶段八 · 查询重写'
};

function switchStage(n) {
    document.querySelectorAll('.stage-tab').forEach(t => {
        t.classList.toggle('active', Number(t.dataset.stage) === n);
    });
    $('stage1Pane').classList.toggle('hidden', n !== 1);
    $('stage2Pane').classList.toggle('hidden', n !== 2);
    $('stage3Pane').classList.toggle('hidden', n !== 3);
    $('stage4Pane').classList.toggle('hidden', n !== 4);
    $('stage5Pane').classList.toggle('hidden', n !== 5);
    $('stage6Pane').classList.toggle('hidden', n !== 6);
    $('stage7Pane').classList.toggle('hidden', n !== 7);
    $('stage8Pane').classList.toggle('hidden', n !== 8);
    $('stageTip').textContent = STAGE_TIPS[n] || '';
    const badge = $('phaseBadge');
    if (badge) {
        badge.textContent = STAGE_BADGE[n] || '';
    }
    state.stage = n;
    try {
        history.replaceState(null, '', n === 1 ? location.pathname : '?stage=' + n);
    } catch (e) {
        /* file:// 下可能报错，忽略 */
    }
}

document.querySelectorAll('.stage-tab').forEach(tab => {
    tab.addEventListener('click', () => switchStage(Number(tab.dataset.stage)));
});

/* ============================================================
   阶段二：向量检索
   ============================================================ */
const state2 = { traceId: null, busy: false };

function setBusyRetrieve(busy) {
    state2.busy = busy;
    const btn = $('btnRetrieve');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '检索中…' : '开始检索';
}

async function doRetrieve() {
    const query = $('queryInput').value.trim();
    if (!query) {
        $('queryInput').focus();
        return;
    }
    const topK = Number($('topK').value);

    setBusyRetrieve(true);
    clearLog();
    $('traceCard').style.display = '';
    $('traceStatus').textContent = 'RUNNING';
    $('traceStatus').className = 'badge-status';
    $('tsVector').textContent = '';
    $('hitPane').innerHTML = '<div class="empty">正在向量化问题、查询向量库…</div>';
    $('hitCount').textContent = '检索中';

    try {
        const res = await fetch('/api/retrieve', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query, topK })
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交检索失败');
            setBusyRetrieve(false);
            return;
        }
        const traceId = res.data.traceId;
        state2.traceId = traceId;
        $('logTaskId').textContent = 'query ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyRetrieve(false);
            loadTrace(traceId);
        }, `/api/retrieve/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyRetrieve(false);
    }
}

async function loadTrace(traceId) {
    try {
        const res = await fetch(`/api/retrieve/${traceId}`).then(r => r.json());
        if (!res.ok) {
            $('hitPane').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        renderTrace(res.data.trace);
        renderHits(res.data.hits);
    } catch (e) {
        console.warn('读取检索结果失败', e);
    }
}

function renderTrace(t) {
    const badge = $('traceStatus');
    badge.textContent = t.status;
    badge.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    $('tsDim').textContent = t.queryDim != null ? t.queryDim : '—';
    $('tsNorm').textContent = t.queryNorm != null ? Number(t.queryNorm).toFixed(4) : '—';
    $('tsTotal').textContent = t.chromaCount != null ? t.chromaCount : '—';
    $('tsCost').textContent = t.costMs ? t.costMs + 'ms' : '—';

    if (t.queryHead) {
        $('tsVector').innerHTML = `问题向量前 8 维：<b>${escapeHtml(t.queryHead)}</b>`;
    }
    if (t.status === 'FAILED') {
        $('hitPane').innerHTML = `<div class="empty">${escapeHtml(t.errorMsg || '检索失败')}</div>`;
        $('hitCount').textContent = '失败';
    }
}

function renderHits(hits) {
    const pane = $('hitPane');
    if (!hits || !hits.length) {
        $('hitCount').textContent = '无命中';
        pane.innerHTML = '<div class="empty">没有命中。检查一下阶段一是否已经入库过文档。</div>';
        return;
    }
    $('hitCount').textContent = `命中 ${hits.length} 条`;
    pane.innerHTML = '';

    hits.forEach(h => {
        const color = simColor(h.similarity);
        const pct = Math.max(0, Math.min(1, h.similarity)) * 100;
        const div = document.createElement('div');
        div.className = 'hit-item';
        div.innerHTML = `
            <div class="hit-head">
                <span class="hit-rank">#${h.rank}</span>
                <div class="sim-wrap">
                    <div class="sim-bar"><div class="sim-fill" style="width:${pct}%;background:${color}"></div></div>
                    <span class="sim-val" style="color:${color}">${Number(h.similarity).toFixed(4)}</span>
                </div>
                <span class="hit-meta">距离 ${Number(h.distance).toFixed(4)}</span>
            </div>
            <div class="hit-title">${escapeHtml(h.heading || '（无标题段落）')} · #${h.chunkIndex}</div>
            <div class="hit-body">${escapeHtml(h.content)}</div>
            <div class="hit-foot">${escapeHtml(h.docName || '')} · ${escapeHtml(h.chromaId)} · ${h.charCount} 字 · 点击查看切片解剖</div>`;
        div.addEventListener('click', () => {
            if (h.docId) {
                openChunkDetail(h.docId, h.chunkIndex);
            }
        });
        pane.appendChild(div);
    });
}

/** 相似度配色：越高越绿，越低越灰 */
function simColor(s) {
    if (s >= 0.7) return '#16a34a';
    if (s >= 0.5) return '#2563eb';
    if (s >= 0.35) return '#f59e0b';
    return '#94a3b8';
}

/* 阶段二事件绑定 */
$('btnRetrieve').addEventListener('click', doRetrieve);

$('queryInput').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doRetrieve();
    }
});

/* ============================================================
   阶段三：混合检索 + RRF
   ============================================================ */
const state3 = { traceId: null, busy: false };

function setBusyHybrid(busy) {
    state3.busy = busy;
    const btn = $('btnHybrid');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '检索中…' : '开始混合检索';
}

async function doHybrid() {
    const query = $('hybridQuery').value.trim();
    if (!query) {
        $('hybridQuery').focus();
        return;
    }
    const payload = {
        query,
        vecTopN: Number($('vecTopN').value) || 20,
        bm25TopN: Number($('bm25TopN').value) || 20,
        finalTopK: Number($('hybridTopK').value) || 5,
        rrfK: Number($('rrfK').value) || 60
    };

    setBusyHybrid(true);
    clearLog();
    $('hybridTraceCard').style.display = '';
    $('hybridStatus').textContent = 'RUNNING';
    $('hybridStatus').className = 'badge-status';
    $('hyTokens').textContent = '';
    $('hybridHitPane').innerHTML = '<div class="empty">正在跑两路召回…</div>';
    $('hybridHitCount').textContent = '检索中';

    try {
        const res = await fetch('/api/hybrid', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyHybrid(false);
            return;
        }
        const traceId = res.data.traceId;
        state3.traceId = traceId;
        $('logTaskId').textContent = 'hybrid ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyHybrid(false);
            loadHybrid(traceId);
        }, `/api/hybrid/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyHybrid(false);
    }
}

async function loadHybrid(traceId) {
    try {
        const res = await fetch(`/api/hybrid/${traceId}`).then(r => r.json());
        if (!res.ok) {
            $('hybridHitPane').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        renderHybridTrace(res.data.trace, res.data.result);
        renderHybridHits(res.data.result.fused || []);
    } catch (e) {
        console.warn('读取混合检索结果失败', e);
    }
}

function renderHybridTrace(t, r) {
    const badge = $('hybridStatus');
    badge.textContent = t.status;
    badge.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    const vecCount = (r.vectorHits || []).length;
    const bm25Count = (r.bm25Hits || []).length;
    const vecIds = new Set((r.vectorHits || []).map(c => c.chromaId));
    const overlap = (r.bm25Hits || []).filter(c => vecIds.has(c.chromaId)).length;

    $('hyVecCount').textContent = vecCount || '—';
    $('hyBm25Count').textContent = bm25Count || '—';
    $('hyOverlap').textContent = overlap || '—';
    $('hyCost').textContent = t.costMs ? t.costMs + 'ms' : '—';

    $('hyTokens').innerHTML =
        `RRF 参数：k=<b>${t.rrfK}</b>　向量 Top-${t.vecTopn}　BM25 Top-${t.bm25Topn}　最终 Top-${t.finalTopk}`;

    if (t.status === 'FAILED') {
        $('hybridHitPane').innerHTML = `<div class="empty">${escapeHtml(t.errorMsg || '检索失败')}</div>`;
        $('hybridHitCount').textContent = '失败';
    }
}

function renderHybridHits(fused) {
    const pane = $('hybridHitPane');
    if (!fused.length) {
        $('hybridHitCount').textContent = '无命中';
        pane.innerHTML = '<div class="empty">没有召回任何内容。检查阶段一是否已经入库文档。</div>';
        return;
    }
    $('hybridHitCount').textContent = `融合后 ${fused.length} 条`;
    pane.innerHTML = '';

    fused.forEach(h => {
        const srcMap = {
            BOTH: { text: '双路', cls: 'both' },
            VECTOR_ONLY: { text: '仅向量', cls: 'vec' },
            BM25_ONLY: { text: '仅 BM25', cls: 'bm25' }
        };
        const src = srcMap[h.source] || srcMap.BOTH;

        // 融合后在向量路里升/降了多少名
        let move = '';
        if (h.vecRank > 0) {
            const d = h.vecRank - h.rank;
            if (d > 0) move = `<span class="move up">较向量 ↑${d}</span>`;
            else if (d < 0) move = `<span class="move down">较向量 ↓${-d}</span>`;
            else move = `<span class="move same">同向量排名</span>`;
        }

        const div = document.createElement('div');
        div.className = 'hit-item hybrid-hit';
        div.innerHTML = `
            <div class="hy-head">
                <span class="hit-rank">#${h.rank}</span>
                <span class="src-badge ${src.cls}">${src.text}</span>
                <span class="rrf-val">RRF ${Number(h.rrfScore).toFixed(6)}</span>
                ${move}
            </div>
            <div class="rank-pair">
                <span class="rp ${h.vecRank > 0 ? '' : 'miss'}">向量 ${h.vecRank > 0 ? '#' + h.vecRank + ' · ' + Number(h.vecScore).toFixed(4) : '未召回'}</span>
                <i>·</i>
                <span class="rp ${h.bm25Rank > 0 ? '' : 'miss'}">BM25 ${h.bm25Rank > 0 ? '#' + h.bm25Rank + ' · ' + Number(h.bm25Score).toFixed(2) : '未召回'}</span>
            </div>
            <div class="hit-title">${escapeHtml(h.heading || '（无标题段落）')} · #${h.chunkIndex}</div>
            <div class="hit-body">${escapeHtml(h.content)}</div>
            <div class="hit-foot">${escapeHtml(h.docName || '')} · ${escapeHtml(h.chromaId)} · ${h.charCount} 字 · 点击查看切片解剖</div>`;

        div.addEventListener('click', () => {
            if (h.docId) {
                openChunkDetail(h.docId, h.chunkIndex);
            }
        });
        pane.appendChild(div);
    });
}

/* 阶段三事件绑定 */
$('btnHybrid').addEventListener('click', doHybrid);

$('hybridQuery').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doHybrid();
    }
});

/* 注：?stage=2/3/4 的深链逻辑统一放在文件最末尾，见文末注释 */

/* ============================================================
   运行记录（历史日志入口）
   ------------------------------------------------------------
   日志一直都在写 kb_ingest_log，这里只是给它一个「能翻」的入口。
   列表接口不查 detail 字段（那是 MEDIUMTEXT，占表体积 1/3），
   点进某条才回放完整日志。
   ============================================================ */
const runState = { type: 'ALL', onlyFailed: false, loaded: false };

function openRuns() {
    $('runDrawer').classList.remove('hidden');
    $('drawerMask').classList.remove('hidden');
    loadRuns();
}

function closeRuns() {
    const d = $('runDrawer');
    if (!d) return;
    d.classList.add('hidden');
    // 切片抽屉也关着的时候才隐藏遮罩
    if ($('drawer').classList.contains('hidden')) {
        $('drawerMask').classList.add('hidden');
    }
}

async function loadRuns() {
    const qs = new URLSearchParams({
        limit: '50',
        type: runState.type,
        onlyFailed: String(runState.onlyFailed)
    });
    try {
        const res = await fetch('/api/runs?' + qs.toString()).then(r => r.json());
        if (!res.ok) {
            $('runBody').innerHTML = `<div class="empty">${escapeHtml(res.message || '加载失败')}</div>`;
            return;
        }
        renderRuns(res.data);
    } catch (e) {
        $('runBody').innerHTML = `<div class="empty">请求失败：${escapeHtml(e.message)}</div>`;
    }
}

function renderRuns(data) {
    $('runSummary').textContent =
        `${data.records.length} 条记录 · 日志共 ${data.totalLogRows} 行 · ${data.note}`;

    const body = $('runBody');
    if (!data.records.length) {
        body.innerHTML = '<div class="empty">没有符合条件的运行记录</div>';
        return;
    }
    body.innerHTML = '';

    const TYPE_LABEL = {
        INGEST: '入库', RETRIEVE: '向量检索', HYBRID: '混合检索',
        RERANK: '重排序', CONTEXT: '上下文', EVAL: '评测', GENERATE: '生成', REWRITE: '查询重写'
    };

    data.records.forEach(r => {
        const label = TYPE_LABEL[r.type] || r.type;
        const typeCls = String(r.type).toLowerCase();
        const statusCls = r.status === 'SUCCESS' ? 'ok' : r.status === 'FAILED' ? 'fail' : 'run';
        const time = String(r.ts || '').replace('T', ' ').slice(5, 19);

        const div = document.createElement('div');
        div.className = 'run-item';
        div.innerHTML = `
            <div class="ri-top">
                <span class="ri-type ${typeCls}">${label}</span>
                <span class="ri-title" title="${escapeAttr(r.title || '')}">${escapeHtml(r.title || '(无标题)')}</span>
                <span class="ri-status ${statusCls}">${escapeHtml(r.status)}</span>
            </div>
            <div class="ri-meta">
                <span>${time}</span>
                <span>${r.costMs}ms</span>
                <span>${r.logCount} 条日志</span>
                ${r.errorCount > 0 ? `<span class="ri-err">${r.errorCount} 错误</span>` : ''}
                <span class="ri-summary">${escapeHtml(r.summary || '')}</span>
            </div>
            ${r.errorMsg ? `<div class="ri-msg">${escapeHtml(r.errorMsg)}</div>` : ''}`;
        div.addEventListener('click', () => jumpToRun(r));
        body.appendChild(div);
    });
}

/** 跳到对应阶段回放这次运行的完整日志 */
function jumpToRun(r) {
    if (r.type === 'INGEST') {
        location.href = '/?task=' + r.id;
    } else if (r.type === 'RETRIEVE') {
        location.href = '/?stage=2&trace=' + r.id;
    } else if (r.type === 'HYBRID') {
        location.href = '/?stage=3&trace=' + r.id;
    } else if (r.type === 'RERANK') {
        location.href = '/?stage=4&trace=' + r.id;
    } else if (r.type === 'CONTEXT') {
        location.href = '/?stage=5&trace=' + r.id;
    } else if (r.type === 'EVAL') {
        location.href = '/?stage=6&run=' + r.id;
    } else if (r.type === 'GENERATE') {
        location.href = '/?stage=7&trace=' + r.id;
    } else if (r.type === 'REWRITE') {
        location.href = '/?stage=8&trace=' + r.id;
    }
}

/* 事件绑定 */
$('btnRuns').addEventListener('click', openRuns);
$('runClose').addEventListener('click', closeRuns);

document.querySelectorAll('.rf[data-type]').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.rf[data-type]').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        runState.type = btn.dataset.type;
        loadRuns();
    });
});

$('runOnlyFailed').addEventListener('change', e => {
    runState.onlyFailed = e.target.checked;
    loadRuns();
});

/* 深链：?runs=1 直接打开运行记录（便于分享/书签） */
if (params.get('runs') === '1') {
    openRuns();
}

/* ============================================================
   阶段四：重排序 Rerank
   ============================================================ */
const state4 = { traceId: null, busy: false };

function setBusyRerank(busy) {
    state4.busy = busy;
    const btn = $('btnRerank');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '重排中…' : '开始重排';
}

async function doRerank() {
    const query = $('rerankQuery').value.trim();
    if (!query) {
        $('rerankQuery').focus();
        return;
    }
    const payload = {
        query,
        recallTopN: Number($('recallTopN').value) || 20,
        finalTopK: Number($('rerankTopK').value) || 5
    };

    setBusyRerank(true);
    clearLog();
    $('rerankTraceCard').style.display = '';
    $('rerankStatus').textContent = 'RUNNING';
    $('rerankStatus').className = 'badge-status';
    $('rerankHitPane').innerHTML = '<div class="empty">正在召回并逐条打分…</div>';
    $('rerankHitCount').textContent = '重排中';
    $('droppedCard').style.display = 'none';

    try {
        const res = await fetch('/api/rerank', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyRerank(false);
            return;
        }
        const traceId = res.data.traceId;
        state4.traceId = traceId;
        $('logTaskId').textContent = 'rerank ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyRerank(false);
            loadRerank(traceId);
        }, `/api/rerank/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyRerank(false);
    }
}

async function loadRerank(traceId) {
    try {
        const res = await fetch(`/api/rerank/${traceId}`).then(r => r.json());
        if (!res.ok) {
            $('rerankHitPane').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        renderRerankTrace(res.data.trace, res.data.result);
        renderRerankHits(res.data.result);
    } catch (e) {
        console.warn('读取重排结果失败', e);
    }
}

function renderRerankTrace(t, r) {
    const badge = $('rerankStatus');
    badge.textContent = t.status;
    badge.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    $('rrRecall').textContent = r.recallCount || '—';
    $('rrScored').textContent = r.scoredCount || '—';
    const sel = r.selected || [];
    $('rrTop1').textContent = sel.length ? Number(sel[0].rerankScore).toFixed(4) : '—';
    $('rrCost').textContent = t.costMs ? t.costMs + 'ms' : '—';

    if (t.status === 'FAILED') {
        $('rerankHitPane').innerHTML = `<div class="empty">${escapeHtml(t.errorMsg || '重排失败')}</div>`;
        $('rerankHitCount').textContent = '失败';
    }
}

function renderRerankHits(result) {
    const selected = result.selected || [];
    const dropped = result.dropped || [];
    const pane = $('rerankHitPane');

    if (!selected.length) {
        $('rerankHitCount').textContent = '无结果';
        pane.innerHTML = '<div class="empty">没有召回任何内容，检查阶段一是否已入库文档。</div>';
        return;
    }
    $('rerankHitCount').textContent = `选中 ${selected.length} 条`;
    pane.innerHTML = '';
    selected.forEach(h => pane.appendChild(buildRerankItem(h, false)));

    const dc = $('droppedCard');
    const dp = $('droppedPane');
    if (dropped.length) {
        dc.style.display = '';
        dp.innerHTML = '';
        dropped.forEach(h => dp.appendChild(buildRerankItem(h, true)));
    } else {
        dc.style.display = 'none';
    }
}

function buildRerankItem(h, isDropped) {
    const delta = h.rankDelta || 0;
    const deltaCls = delta > 0 ? 'up' : delta < 0 ? 'down' : 'same';
    const deltaText = delta > 0 ? '↑' + delta : delta < 0 ? '↓' + (-delta) : '—';

    const div = document.createElement('div');
    div.className = 'rk-item' + (isDropped ? ' rk-dropped' : '');
    div.innerHTML = `
        <div class="rk-head">
            <span class="hit-rank">${isDropped ? '召回#' + h.recallRank : '#' + h.finalRank}</span>
            <span class="rk-score">rerank ${Number(h.rerankScore).toFixed(4)}</span>
            <span class="rk-delta ${deltaCls}" title="召回排名 ${h.recallRank} → 重排排名 ${h.finalRank}">${deltaText}</span>
            <span class="rk-recall">召回排名 #${h.recallRank}</span>
        </div>
        <div class="hit-title">${escapeHtml(h.heading || '（无标题段落）')} · #${h.chunkIndex}</div>
        <div class="hit-body">${escapeHtml(h.content)}</div>
        <div class="hit-foot">
            ${escapeHtml(h.docName || '')} · ${escapeHtml(h.chromaId)} · ${h.charCount} 字
            · RRF ${Number(h.rrfScore).toFixed(6)}
            · 向量${h.vecRank > 0 ? '#' + h.vecRank : '—'} / BM25${h.bm25Rank > 0 ? '#' + h.bm25Rank : '—'}
        </div>
        ${isDropped ? '<div class="rk-reason">↓ 召回时在 Top-K 内，重排后掉出去了 —— 它原本会被当成最相关喂给大模型</div>' : ''}`;

    div.addEventListener('click', () => {
        if (h.docId) {
            openChunkDetail(h.docId, h.chunkIndex);
        }
    });
    return div;
}

/* 阶段四事件绑定 */
$('btnRerank').addEventListener('click', doRerank);

$('rerankQuery').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doRerank();
    }
});

/* ============================================================
   阶段五：上下文控制
   ============================================================ */
const state5 = { traceId: null, busy: false };

function setBusyContext(busy) {
    state5.busy = busy;
    const btn = $('btnContext');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '生成中…' : '生成上下文';
}

async function doContext() {
    const query = $('ctxQuery').value.trim();
    if (!query) {
        $('ctxQuery').focus();
        return;
    }
    const payload = {
        query,
        recallTopN: Number($('ctxRecall').value) || 20,
        topK: Number($('ctxTopK').value) || 5,
        strategy: $('ctxStrategy').value,
        maxChars: Number($('ctxMaxChars').value) || 1200
    };

    setBusyContext(true);
    clearLog();
    $('ctxTraceCard').style.display = '';
    $('ctxStatus').textContent = 'RUNNING';
    $('ctxStatus').className = 'badge-status';
    $('ctxPreview').textContent = '生成中…';
    $('ctxSentencePane').innerHTML = '<div class="empty">正在召回、重排、拆句打分…</div>';
    $('ctxSourceCard').style.display = 'none';

    try {
        const res = await fetch('/api/context', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyContext(false);
            return;
        }
        const traceId = res.data.traceId;
        state5.traceId = traceId;
        $('logTaskId').textContent = 'context ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyContext(false);
            loadContext(traceId);
        }, `/api/context/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyContext(false);
    }
}

async function loadContext(traceId) {
    try {
        const res = await fetch(`/api/context/${traceId}`).then(r => r.json());
        if (!res.ok) {
            $('ctxPreview').textContent = res.message || '读取失败';
            return;
        }
        renderContextTrace(res.data.trace, res.data.result);
        renderContextResult(res.data.result);
    } catch (e) {
        console.warn('读取上下文结果失败', e);
    }
}

function renderContextTrace(t, r) {
    const badge = $('ctxStatus');
    badge.textContent = t.status;
    badge.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    const st = r.stats || {};
    $('cvOriginChars').textContent = (st.originChars || 0).toLocaleString();
    $('cvFinalChars').textContent = (st.finalChars || 0).toLocaleString();
    $('cvRatio').textContent = st.compressRatio != null
        ? (Number(st.compressRatio) * 100).toFixed(1) + '%' : '—';
    $('cvTokens').textContent = `${st.originTokens || 0} → ${st.finalTokens || 0}`;
    $('cvSentences').textContent = st.selectedSentences || 0;
    $('cvDedup').textContent = st.droppedDuplicates || 0;
    $('cvCost').textContent = t.costMs ? t.costMs + 'ms' : '—';
    $('piCtxStrategy').textContent = t.strategy || 'VECTOR';

    if (t.status === 'FAILED') {
        $('ctxPreview').textContent = t.errorMsg || '生成失败';
        $('ctxSentencePane').innerHTML = `<div class="empty">${escapeHtml(t.errorMsg || '生成失败')}</div>`;
    }
}

function renderContextResult(r) {
    const sentences = r.sentences || [];
    const sources = r.sources || [];

    $('ctxPreview').textContent = r.context || '（空）';

    const pane = $('ctxSentencePane');
    $('ctxSentCount').textContent = `共 ${sentences.length} 句`;
    pane.innerHTML = '';
    if (!sentences.length) {
        pane.innerHTML = '<div class="empty">没有选中任何句子</div>';
    }
    sentences.forEach(s => {
        const div = document.createElement('div');
        div.className = 'sen-item';
        div.innerHTML = `
            <span class="sen-order">#${s.order}</span>
            <div class="sen-body">
                <div class="sen-text">${escapeHtml(s.text)}</div>
                <div class="sen-meta">${escapeHtml(s.heading || '无标题')} · 切片#${s.chunkIndex} · 源片#${s.sourceRank} · ${s.charCount} 字</div>
            </div>
            <span class="sen-score">${Number(s.score).toFixed(4)}</span>`;
        div.addEventListener('click', () => {
            if (s.docId) {
                openChunkDetail(s.docId, s.chunkIndex);
            }
        });
        pane.appendChild(div);
    });

    // 源切片贡献
    if (sources.length) {
        $('ctxSourceCard').style.display = '';
        const sp = $('ctxSourcePane');
        sp.innerHTML = '';
        const maxSent = Math.max(...sources.map(s => s.sentenceCount), 1);
        sources.forEach(s => {
            const pct = Math.round((s.selectedCount / maxSent) * 100);
            const row = document.createElement('div');
            row.className = 'src-row';
            row.innerHTML = `
                <span class="src-idx">#${s.rank}</span>
                <span class="src-name" title="${escapeAttr(s.content || '')}">
                    ${escapeHtml(s.heading || '无标题')} · 切片#${s.chunkIndex} · ${s.charCount} 字
                </span>
                <div class="src-bar"><div style="width:${pct}%"></div></div>
                <span class="src-cnt">${s.selectedCount}/${s.sentenceCount} 句</span>`;
            row.title = s.content || '';
            sp.appendChild(row);
        });
    }
}

/* 阶段五事件绑定 */
$('btnContext').addEventListener('click', doContext);

$('ctxQuery').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doContext();
    }
});

$('btnCopyCtx').addEventListener('click', async () => {
    const text = $('ctxPreview').textContent;
    if (!text || text === '等待生成…' || text === '生成中…') {
        return;
    }
    try {
        await navigator.clipboard.writeText(text);
        $('btnCopyCtx').textContent = '✓ 已复制';
        setTimeout(() => $('btnCopyCtx').textContent = '复制', 1500);
    } catch (e) {
        alert('复制失败，请手动选中');
    }
});

/* ------------------------------------------------------------
   URL 深链（放在文件最末尾）
   ?stage=2/3/4/5 [+trace=xxx] 打开对应阶段并回放
   为什么必须放最后：上面的 state2~state5 都是 const，
   提前引用会触发暂时性死区（TDZ）报错，整段脚本中断 → 页面空白。
   ------------------------------------------------------------ */
const stageParam = params.get('stage');
if (['2', '3', '4', '5'].includes(stageParam)) {
    switchStage(Number(stageParam));
    const traceParam = params.get('trace');
    if (traceParam) {
        if (stageParam === '2') {
            state2.traceId = traceParam;
            $('traceCard').style.display = '';
            $('logTaskId').textContent = 'query ' + traceParam.slice(0, 8);
            subscribe(traceParam, () => loadTrace(traceParam), `/api/retrieve/${traceParam}/stream`);
            loadTrace(traceParam);
        } else if (stageParam === '3') {
            state3.traceId = traceParam;
            $('hybridTraceCard').style.display = '';
            $('logTaskId').textContent = 'hybrid ' + traceParam.slice(0, 8);
            subscribe(traceParam, () => loadHybrid(traceParam), `/api/hybrid/${traceParam}/stream`);
            loadHybrid(traceParam);
        } else if (stageParam === '4') {
            state4.traceId = traceParam;
            $('rerankTraceCard').style.display = '';
            $('logTaskId').textContent = 'rerank ' + traceParam.slice(0, 8);
            subscribe(traceParam, () => loadRerank(traceParam), `/api/rerank/${traceParam}/stream`);
            loadRerank(traceParam);
        } else {
            state5.traceId = traceParam;
            $('ctxTraceCard').style.display = '';
            $('logTaskId').textContent = 'context ' + traceParam.slice(0, 8);
            subscribe(traceParam, () => loadContext(traceParam), `/api/context/${traceParam}/stream`);
            loadContext(traceParam);
        }
    }
}

/* ============================================================
   阶段六：评测
   ------------------------------------------------------------
   这一阶段的核心不是「跑得快」，而是「可复现」：
   同一个问题、同一个 Top-K，把所有策略都跑一遍，用同一把尺子量。
   ============================================================ */
const state6 = { runId: null, busy: false, result: null };

/* ---------------- 测试集 ---------------- */

async function loadCases() {
    try {
        const res = await fetch('/api/eval/cases').then(r => r.json());
        if (!res.ok) {
            $('caseList').innerHTML = `<div class="empty">${escapeHtml(res.message || '加载失败')}</div>`;
            return;
        }
        renderCases(res.data);
    } catch (e) {
        $('caseList').innerHTML = `<div class="empty">请求失败：${escapeHtml(e.message)}</div>`;
    }
}

function renderCases(list) {
    const enabled = list.filter(c => c.enabled).length;
    $('evalCaseCount').textContent = `测试集 ${enabled}/${list.length} 条启用`;

    const pane = $('caseList');
    if (!list.length) {
        pane.innerHTML = '<div class="empty">还没有用例。点右上角「+ 添加用例」。</div>';
        return;
    }
    pane.innerHTML = '';
    list.forEach(c => {
        const div = document.createElement('div');
        div.className = 'case-item' + (c.enabled ? '' : ' disabled');
        const groups = (c.expect || []).map(g => g.join('+')).join('  |  ');
        div.innerHTML = `
            <input type="checkbox" class="case-en" ${c.enabled ? 'checked' : ''} title="启用/停用">
            <div class="case-body">
                <div class="case-query">
                    <span class="ci-name">${escapeHtml(c.name)}</span>
                    <span class="mx-diff ${c.difficulty}">${c.difficulty}</span>
                </div>
                <div class="case-query">${escapeHtml(c.query)}</div>
                <div class="case-expect">期望：${escapeHtml(groups)}</div>
            </div>
            <button class="case-del" title="删除">✕</button>`;

        div.querySelector('.case-en').addEventListener('change', async e => {
            await fetch(`/api/eval/cases/${c.id}/toggle?enabled=${e.target.checked}`, { method: 'POST' });
            loadCases();
        });
        div.querySelector('.case-del').addEventListener('click', async () => {
            if (!confirm(`删除用例「${c.name}」？`)) return;
            await fetch(`/api/eval/cases/${c.id}`, { method: 'DELETE' });
            loadCases();
        });
        pane.appendChild(div);
    });
}

async function addCase() {
    const name = $('cfName').value.trim();
    const query = $('cfQuery').value.trim();
    const expectText = $('cfExpect').value.trim();
    const difficulty = $('cfDiff').value;
    if (!name || !query || !expectText) {
        alert('用例名、问题、期望关键词都要填');
        return;
    }
    const res = await fetch('/api/eval/cases', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, query, expectText, difficulty })
    }).then(r => r.json());
    if (!res.ok) {
        alert(res.message || '保存失败');
        return;
    }
    $('cfName').value = '';
    $('cfQuery').value = '';
    $('cfExpect').value = '';
    $('caseForm').classList.add('hidden');
    loadCases();
}

/* ---------------- 运行评测 ---------------- */

function setBusyEval(busy) {
    state6.busy = busy;
    const btn = $('btnEval');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '评测中…' : '开始评测';
}

async function doEval() {
    const strategies = Array.from(document.querySelectorAll('#evalStrategies input:checked'))
        .map(i => i.value);
    if (!strategies.length) {
        alert('至少选一种策略');
        return;
    }
    const payload = {
        strategies: strategies.join(','),
        topK: Number($('evalTopK').value) || 5,
        recallTopN: Number($('evalRecall').value) || 20
    };

    setBusyEval(true);
    clearLog();
    $('evalSummaryCard').style.display = 'none';
    $('evalMatrixCard').style.display = 'none';

    try {
        const res = await fetch('/api/eval/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyEval(false);
            return;
        }
        const runId = res.data.runId;
        state6.runId = runId;
        $('logTaskId').textContent = 'eval ' + runId.slice(0, 8);

        subscribe(runId, () => {
            setBusyEval(false);
            loadEval(runId);
        }, `/api/eval/${runId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyEval(false);
    }
}

async function loadEval(runId) {
    try {
        const res = await fetch(`/api/eval/${runId}`).then(r => r.json());
        if (!res.ok) {
            $('evalTable').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        state6.result = res.data.result;
        renderEvalSummary(res.data.run, res.data.result);
        renderEvalMatrix(res.data.result);
    } catch (e) {
        console.warn('读取评测结果失败', e);
    }
}

/* ---------------- 结果渲染 ---------------- */

function evBadgeClass(strategy) {
    const s = String(strategy).toLowerCase();
    return s === 'vector' ? '' : s;
}

function pct(v) {
    return (v * 100).toFixed(1) + '%';
}

function renderEvalSummary(run, result) {
    $('evalSummaryCard').style.display = '';
    $('evalMeta').textContent =
        `${result.caseCount} 用例 · Top-${result.topK} · 召回 ${result.recallTopN} · ${run.costMs}ms · ${run.status}`;

    const sums = result.strategies || [];
    if (!sums.length) {
        $('evalTable').innerHTML = '<div class="empty">没有结果</div>';
        return;
    }

    // 每一列单独找最优值：命中率/覆盖率越高越好，耗时越低越好
    const best = {
        hit1Rate: Math.max(...sums.map(s => s.hit1Rate)),
        hit3Rate: Math.max(...sums.map(s => s.hit3Rate)),
        hitKRate: Math.max(...sums.map(s => s.hitKRate)),
        mrr: Math.max(...sums.map(s => s.mrr)),
        avgCoverage: Math.max(...sums.map(s => s.avgCoverage)),
        avgLatencyMs: Math.min(...sums.map(s => s.avgLatencyMs))
    };
    const cls = (key, v) => {
        if (sums.length < 2) return '';
        return Math.abs(v - best[key]) < 1e-9 ? 'ev-best' : '';
    };

    let html = `<table class="ev-table">
        <thead><tr>
            <th>策略</th><th>Hit@1</th><th>Hit@3</th><th>Hit@${result.topK}</th>
            <th>MRR</th><th>覆盖率</th><th>平均耗时</th><th>失败</th>
        </tr></thead><tbody>`;

    sums.forEach(s => {
        html += `<tr>
            <td><span class="ev-name"><span class="ev-badge ${evBadgeClass(s.strategy)}">${s.strategy}</span></span></td>
            <td class="${cls('hit1Rate', s.hit1Rate)}">${pct(s.hit1Rate)}</td>
            <td class="${cls('hit3Rate', s.hit3Rate)}">${pct(s.hit3Rate)}</td>
            <td class="${cls('hitKRate', s.hitKRate)}">${pct(s.hitKRate)}</td>
            <td class="${cls('mrr', s.mrr)}">${s.mrr.toFixed(3)}</td>
            <td class="${cls('avgCoverage', s.avgCoverage)}">${pct(s.avgCoverage)}</td>
            <td class="${cls('avgLatencyMs', s.avgLatencyMs)}">${Math.round(s.avgLatencyMs)}ms</td>
            <td class="${s.failures > 0 ? 'ev-worst' : ''}">${s.failures || '—'}</td>
        </tr>`;
    });
    html += '</tbody></table>';
    $('evalTable').innerHTML = html;

    // 压缩阶段：排名之外还要看「删了多少字、证据还在不在」
    const ctx = sums.find(s => s.avgContextChars > 0);
    const note = $('evalCompress');
    if (ctx) {
        const keep = ctx.avgSourceChars > 0 ? ctx.avgContextChars / ctx.avgSourceChars : 0;
        note.style.display = '';
        note.innerHTML = `${ctx.strategy} 压缩：源切片平均 ${Math.round(ctx.avgSourceChars)} 字 → 上下文平均 ${Math.round(ctx.avgContextChars)} 字`
            + `（保留 ${(keep * 100).toFixed(0)}%），<b>上下文里期望组覆盖 ${pct(ctx.avgContextCoverage)}</b>`
            + `　—　这才是压缩阶段真正该看的指标：删掉 2/3 的文字之后，证据还在不在。`;
    } else {
        note.style.display = 'none';
    }
}

function renderEvalMatrix(result) {
    const rows = result.cases || [];
    if (!rows.length) return;
    $('evalMatrixCard').style.display = '';

    const strategies = (result.strategies || []).map(s => s.strategy);
    let html = `<table class="mx-table"><thead><tr><th>用例</th>`
        + strategies.map(s => `<th>${s}</th>`).join('')
        + `</tr></thead><tbody>`;

    rows.forEach((row, idx) => {
        html += `<tr class="mx-case" data-idx="${idx}">
            <td>${escapeHtml(row.name)}<span class="mx-diff ${row.difficulty}">${row.difficulty}</span></td>`;
        strategies.forEach(s => {
            const m = (row.metrics || []).find(x => x.strategy === s);
            if (!m) {
                html += '<td><span class="mx-cell miss">—</span></td>';
                return;
            }
            if (m.error) {
                html += `<td><span class="mx-cell err" title="${escapeAttr(m.error)}">!</span></td>`;
            } else if (m.firstRank > 0) {
                const c = m.firstRank <= 3 ? 'ok' : 'mid';
                html += `<td><span class="mx-cell ${c}" title="覆盖率 ${pct(m.coverage)}">#${m.firstRank}</span></td>`;
            } else {
                html += '<td><span class="mx-cell miss">—</span></td>';
            }
        });
        html += '</tr>';

        // 展开行：每条策略命中了哪些关键词组 + Top1 内容
        html += `<tr class="mx-detail" data-detail="${idx}" style="display:none"><td colspan="${strategies.length + 1}">`;
        strategies.forEach(s => {
            const m = (row.metrics || []).find(x => x.strategy === s);
            if (!m) return;
            const matched = (m.matched || []).length
                ? m.matched.map(g => `<code>${escapeHtml(g)}</code>`).join(' ')
                : '<span style="color:#b91c1c">未命中期望关键词</span>';
            const top = (m.top || [])[0];
            html += `<div class="mx-detail-row">
                <div class="k">${s}</div>
                <div class="v">
                    排名 ${m.firstRank > 0 ? '#' + m.firstRank : '—'} · 覆盖 ${pct(m.coverage)} · ${m.latencyMs}ms
                    ${m.error ? ' · <span style="color:#b91c1c">' + escapeHtml(m.error) + '</span>' : ''}<br>
                    命中：${matched}<br>
                    ${top ? 'Top1：' + escapeHtml((top.text || '').slice(0, 160)) : '<span style="color:#9aa3af">无结果</span>'}
                    ${m.contextChars > 0 ? `<br>压缩：${m.sourceChars} 字 → <b>${m.contextChars}</b> 字，上下文覆盖 ${pct(m.contextCoverage)}` : ''}
                </div>
            </div>`;
        });
        html += '</td></tr>';
    });
    html += '</tbody></table>';
    $('evalMatrix').innerHTML = html;

    $('evalMatrix').querySelectorAll('tr.mx-case').forEach(tr => {
        tr.addEventListener('click', () => {
            const d = $('evalMatrix').querySelector(`tr[data-detail="${tr.dataset.idx}"]`);
            if (d) {
                d.style.display = d.style.display === 'none' ? '' : 'none';
            }
        });
    });
}

/* ---------------- 事件绑定 ---------------- */

$('btnEval').addEventListener('click', doEval);
$('btnAddCase').addEventListener('click', addCase);
$('btnToggleCaseForm').addEventListener('click', () => {
    $('caseForm').classList.toggle('hidden');
});

loadCases();

/* 深链：?stage=6&run=<id> 直接打开某次评测结果 */
if (params.get('stage') === '6') {
    switchStage(6);
    const runParam = params.get('run');
    if (runParam) {
        state6.runId = runParam;
        $('logTaskId').textContent = 'eval ' + runParam.slice(0, 8);
        subscribe(runParam, () => loadEval(runParam), `/api/eval/${runParam}/stream`);
        loadEval(runParam);
    }
}

/* ============================================================
   阶段七：生成
   ------------------------------------------------------------
   检索的质量在这里变成用户能读到的答案。
   这一阶段唯一不能省的是「引用」：没有引用的答案，无法判断是资料来的还是编的。
   ============================================================ */
const state7 = { traceId: null, busy: false, result: null, promptTab: 'user' };

function setBusyGen(busy) {
    state7.busy = busy;
    const btn = $('btnGenerate');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '生成中…' : '生成答案';
}

async function doGenerate() {
    const query = $('genQuery').value.trim();
    if (!query) {
        $('genQuery').focus();
        return;
    }
    const payload = {
        query,
        topK: Number($('genTopK').value) || 5,
        recallTopN: Number($('genRecall').value) || 20
    };

    setBusyGen(true);
    clearLog();
    $('genAnswerCard').style.display = '';
    $('genStatus').textContent = 'RUNNING';
    $('genStatus').className = 'badge-status';
    $('genMeta').innerHTML = '';
    $('genAnswer').innerHTML = '<div class="empty">正在检索资料、调用大模型…</div>';
    $('genCiteCard').style.display = 'none';
    $('genPromptCard').style.display = 'none';

    try {
        const res = await fetch('/api/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyGen(false);
            return;
        }
        const traceId = res.data.traceId;
        state7.traceId = traceId;
        $('logTaskId').textContent = 'gen ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyGen(false);
            loadGenerate(traceId);
        }, `/api/generate/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyGen(false);
    }
}

async function loadGenerate(traceId) {
    try {
        const res = await fetch(`/api/generate/${traceId}`).then(r => r.json());
        if (!res.ok) {
            $('genAnswer').innerHTML = `<div class="empty">${escapeHtml(res.message || '读取失败')}</div>`;
            return;
        }
        state7.result = res.data.result;
        renderGenerate(res.data.trace, res.data.result);
    } catch (e) {
        console.warn('读取生成结果失败', e);
    }
}

function renderGenerate(t, r) {
    const badge = $('genStatus');
    badge.textContent = t.status;
    badge.className = 'badge-status' + (t.status === 'SUCCESS' ? ' success' : t.status === 'FAILED' ? ' failed' : '');

    if (t.status === 'FAILED') {
        $('genAnswer').innerHTML = `<div class="empty">${escapeHtml(t.errorMsg || '生成失败')}</div>`;
        return;
    }
    if (!r) {
        $('genAnswer').innerHTML = '<div class="empty">生成中…</div>';
        return;
    }

    const citedSet = new Set(r.cited || []);
    $('genMeta').innerHTML = `
        <span>模型 <b>${escapeHtml(r.model || '')}</b></span>
        <span>${r.sources ? r.sources.length : 0} 片 → ${r.sentences ? r.sentences.length : 0} 句</span>
        <span>上下文 <b>${(r.sentences || []).reduce((a, s) => a + s.charCount, 0)}</b> 字</span>
        <span>prompt <b>${r.promptTokens || 0}</b> tokens</span>
        <span>completion <b>${r.completionTokens || 0}</b> tokens</span>
        <span>引用 <b>${citedSet.size}</b> 条</span>
        <span>耗时 ${t.costMs}ms</span>`;

    // 答案里的 [n] 渲染成徽章；越界的编号标红，说明模型引用了不存在的资料
    const maxNo = (r.sentences || []).length;
    let html = escapeHtml(r.answer || '');
    html = html.replace(/\[(\d{1,2})]/g, (m, n) => {
        const idx = Number(n);
        const ok = idx >= 1 && idx <= maxNo;
        return `<span class="cite-chip ${ok ? '' : 'orphan'}" data-cite="${idx}" title="${ok ? '点击查看这条依据' : '这条编号在资料里不存在'}">${idx}</span>`;
    });
    $('genAnswer').innerHTML = html;
    $('genAnswer').querySelectorAll('.cite-chip').forEach(chip => {
        chip.addEventListener('click', () => highlightCite(Number(chip.dataset.cite)));
    });

    renderCites(r, citedSet);
    renderPrompt(r);
}

function renderCites(r, citedSet) {
    const list = r.sentences || [];
    $('genCiteCard').style.display = '';
    $('genCiteMeta').textContent =
        `共 ${list.length} 句，被引用 ${citedSet.size} 句 · 灰掉的没被答案用到`;

    const pane = $('genCitePane');
    pane.innerHTML = '';
    if (!list.length) {
        pane.innerHTML = '<div class="empty">没有可用依据</div>';
        return;
    }
    list.forEach((s, i) => {
        const no = i + 1;
        const div = document.createElement('div');
        div.className = 'cite-item' + (citedSet.has(no) ? '' : ' unused');
        div.id = 'cite-' + no;
        div.innerHTML = `
            <span class="cite-no">${no}</span>
            <div class="cite-body">
                <div class="cite-text">${escapeHtml(s.text)}</div>
                <div class="cite-src">《${escapeHtml(s.docName || '')}》 · 切片#${s.chunkIndex}
                    ${s.heading ? ' · ' + escapeHtml(s.heading) : ''} · 来自第 ${s.sourceRank} 片</div>
            </div>
            <span class="cite-score">${Number(s.score).toFixed(3)}</span>`;
        div.addEventListener('click', () => {
            if (s.docId) {
                openChunkDetail(s.docId, s.chunkIndex);
            }
        });
        pane.appendChild(div);
    });
}

function highlightCite(no) {
    const el = $('cite-' + no);
    if (!el) return;
    document.querySelectorAll('.cite-item.hl').forEach(x => x.classList.remove('hl'));
    el.classList.add('hl');
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => el.classList.remove('hl'), 2200);
}

function renderPrompt(r) {
    $('genPromptCard').style.display = '';
    const text = state7.promptTab === 'system' ? (r.systemPrompt || '') : (r.userPrompt || '');
    $('genPrompt').textContent = text || '（空）';
}

/* 事件绑定 */
$('btnGenerate').addEventListener('click', doGenerate);

$('genQuery').addEventListener('keydown', e => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        doGenerate();
    }
});

document.querySelectorAll('.gen-tabs .tab').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.gen-tabs .tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        state7.promptTab = tab.dataset.prompt;
        if (state7.result) {
            renderPrompt(state7.result);
        }
    });
});

$('btnCopyAnswer').addEventListener('click', async () => {
    if (!state7.result) return;
    try {
        await navigator.clipboard.writeText(state7.result.answer || '');
        $('btnCopyAnswer').textContent = '✓ 已复制';
        setTimeout(() => $('btnCopyAnswer').textContent = '复制', 1500);
    } catch (e) {
        alert('复制失败，请手动选中');
    }
});

/* 显示当前生成模型 */
fetch('/api/health').then(r => r.json()).then(res => {
    const m = res && res.data && res.data.config && res.data.config.generateModel;
    if (m) $('genModel').textContent = '模型 ' + m;
}).catch(() => {});

/* 深链：?stage=7&trace=<id> 直接打开某次生成 */
if (params.get('stage') === '7') {
    switchStage(7);
    const genParam = params.get('trace');
    if (genParam) {
        state7.traceId = genParam;
        $('genAnswerCard').style.display = '';
        $('logTaskId').textContent = 'gen ' + genParam.slice(0, 8);
        subscribe(genParam, () => loadGenerate(genParam), `/api/generate/${genParam}/stream`);
        loadGenerate(genParam);
    }
}

/* ============================================================
   阶段八：查询重写
   ------------------------------------------------------------
   这一步的价值不在于「看起来更聪明」，而在于「知道它到底值不值」。
   所以页面必须同时给出：变体是什么、每个变体带来多少新召回、重写前后 Top-K 的差异。
   ============================================================ */
const state8 = { traceId: null, busy: false, result: null };

function setBusyRw(busy) {
    state8.busy = busy;
    const btn = $('btnRewrite');
    btn.disabled = busy;
    btn.querySelector('.spinner').classList.toggle('hidden', !busy);
    btn.querySelector('.btn-text').textContent = busy ? '改写中…' : '改写并检索';
}

async function doRewrite() {
    const query = $('rwQuery').value.trim();
    if (!query) {
        $('rwQuery').focus();
        return;
    }
    const payload = {
        query,
        variants: Number($('rwVariants').value) || 3,
        topK: Number($('rwTopK').value) || 5,
        recallN: 20,
        rerank: $('rwRerank').checked
    };

    setBusyRw(true);
    clearLog();
    ['rwVariantCard', 'rwCompareCard', 'rwResultCard'].forEach(id => $(id).style.display = 'none');

    try {
        const res = await fetch('/api/rewrite', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());

        if (!res.ok) {
            appendLocalError(res.message || '提交失败');
            setBusyRw(false);
            return;
        }
        const traceId = res.data.traceId;
        state8.traceId = traceId;
        $('logTaskId').textContent = 'rewrite ' + traceId.slice(0, 8);

        subscribe(traceId, () => {
            setBusyRw(false);
            loadRewrite(traceId);
        }, `/api/rewrite/${traceId}/stream`);

    } catch (e) {
        appendLocalError('请求失败：' + e.message);
        setBusyRw(false);
    }
}

async function loadRewrite(traceId) {
    try {
        const res = await fetch(`/api/rewrite/${traceId}`).then(r => r.json());
        if (!res.ok) {
            appendLocalError(res.message || '读取失败');
            return;
        }
        state8.result = res.data.result;
        renderRewrite(res.data.trace, res.data.result);
    } catch (e) {
        console.warn('读取重写结果失败', e);
    }
}

function fromBadges(from) {
    return (from || []).map(i =>
        `<span class="from-badge ${i === 0 ? 'orig' : ''}">${i === 0 ? '原' : '变' + i}</span>`
    ).join('');
}

function renderRewrite(t, r) {
    if (!r) return;

    // ---- 变体 ----
    $('rwVariantCard').style.display = '';
    $('rwVariantMeta').textContent =
        `${r.stats.variantCount} 个变体 · 候选 ${r.stats.candidates} 条 · ${t.costMs}ms · 改写 ${t.promptTokens} tokens`;

    let vHtml = `
        <div class="rw-variant original">
            <span class="rw-vno">原查询</span>
            <div class="rw-vtext">${escapeHtml(r.original)}</div>
            <div class="rw-vstat">排序基准</div>
        </div>`;
    (r.variants || []).forEach(v => {
        vHtml += `
        <div class="rw-variant">
            <span class="rw-vno">变体 ${v.index}</span>
            <div class="rw-vtext">${escapeHtml(v.text)}</div>
            <div class="rw-vstat">
                召回 ${v.recallCount}<br>
                新增 <b class="${v.newHits === 0 ? 'zero' : ''}">${v.newHits}</b>
            </div>
        </div>`;
    });
    $('rwVariantPane').innerHTML = vHtml;

    // ---- 前后对比 ----
    $('rwCompareCard').style.display = '';
    const gained = (r.merged || []).filter(h => h.baselineRank === 0);
    $('rwCompareMeta').textContent =
        `新增 ${r.stats.gainedInTopK} 条 · 移出 ${r.stats.lostFromTopK} 条 · ${r.stats.reranked ? '融合后重排' : '仅融合'}`;

    // 只有「被挤出」的才标红、「新进来」的才标绿，两边都有的保持中性
    const mergedIds = new Set((r.merged || []).map(h => h.chromaId));
    const baseIds = new Set((r.baseline || []).map(h => h.chromaId));
    const col = (title, list, cls) => `
        <div class="rw-cmp-col">
            <h5>${title}</h5>
            ${(list || []).map(h => `
                <div class="rw-cmp-item ${cls(h)}">
                    <span class="rk2">#${h.rank}</span>${escapeHtml((h.content || '').slice(0, 90))}
                </div>`).join('') || '<div class="empty">无结果</div>'}
        </div>`;
    $('rwComparePane').innerHTML = `<div class="rw-cmp">
        ${col('重写前（只用原查询）', r.baseline, h => mergedIds.has(h.chromaId) ? '' : 'lost')}
        ${col('重写后（融合 + 可选重排）', r.merged, h => baseIds.has(h.chromaId) ? '' : 'gained')}
    </div>
    <p class="hint-inline">绿色 = 改写新带进来的；红色 = 只用原查询时在 Top-K、改写后被挤出去的（不一定更差）。</p>`;

    // ---- 融合结果 ----
    $('rwResultCard').style.display = '';
    $('rwResultMeta').textContent = `${(r.merged || []).length} 条 · 每条标注被几路召回`;
    const pane = $('rwHitPane');
    pane.innerHTML = '';
    (r.merged || []).forEach(h => {
        const div = document.createElement('div');
        div.className = 'hit-item';
        div.innerHTML = `
            <div class="hit-head">
                <span class="hit-rank">#${h.rank}</span>
                <span class="hit-count ${h.hitCount > 1 ? 'multi' : 'single'}">${h.hitCount} 路</span>
                <span style="flex:1">${fromBadges(h.from)}</span>
                <span class="hit-meta">${h.rerankScore > 0 ? 'rerank ' + Number(h.rerankScore).toFixed(3) : 'fused ' + Number(h.fusedScore).toFixed(6)}</span>
            </div>
            <div class="hit-title">${escapeHtml(h.heading || '（无标题段落）')} · #${h.chunkIndex}</div>
            <div class="hit-body">${escapeHtml(h.content)}</div>
            <div class="hit-foot">${escapeHtml(h.docName || '')} · 原查询里排名：${h.baselineRank > 0 ? '#' + h.baselineRank : '未召回'} · 点击看切片解剖</div>`;
        div.addEventListener('click', () => {
            if (h.docId) {
                openChunkDetail(h.docId, h.chunkIndex);
            }
        });
        pane.appendChild(div);
    });
}

/* 事件绑定 */
$('btnRewrite').addEventListener('click', doRewrite);
$('rwQuery').addEventListener('keydown', e => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        doRewrite();
    }
});

/* 深链：?stage=8&trace=<id> */
if (params.get('stage') === '8') {
    switchStage(8);
    const rwParam = params.get('trace');
    if (rwParam) {
        state8.traceId = rwParam;
        $('logTaskId').textContent = 'rewrite ' + rwParam.slice(0, 8);
        subscribe(rwParam, () => loadRewrite(rwParam), `/api/rewrite/${rwParam}/stream`);
        loadRewrite(rwParam);
    }
}
