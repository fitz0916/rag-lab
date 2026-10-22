/* ============================================================
 * demo-boot.js —— 静态回放站的拦截层
 * ------------------------------------------------------------
 * 作用：把 window.fetch 和 window.EventSource 换成「读本地 JSON」的假实现，
 *       app.js 一行都不用改，整个 Spring Boot / MySQL / Chroma / API Key
 *       就从链路上消失了。
 *
 * 什么时候生效：
 *   · localhost:8080       → 不拦截（你本地开发，走真后端）
 *   · ?live=1              → 不拦截，强制真后端
 *   · ?demo=1              → 强制回放
 *   · 其它（github.io 等） → 回放
 * ============================================================ */
(function () {
    'use strict';

    var nativeFetch = window.fetch.bind(window);
    var NativeEventSource = window.EventSource;

    var params = new URLSearchParams(location.search);
    var isLocalDev = (location.hostname === 'localhost' || location.hostname === '127.0.0.1')
        && location.port === '8080';

    var forceLive = params.get('live') === '1';
    var forceDemo = params.get('demo') === '1';

    if (!forceDemo && (forceLive || isLocalDev)) {
        return;
    }

    var REPLAY_DIR = new URL('./replay/', document.baseURI);

    /* ---------------------------------------------------------- 数据加载 */

    var cache = Object.create(null);

    function load(name) {
        if (!cache[name]) {
            cache[name] = nativeFetch(new URL(name, REPLAY_DIR))
                .then(function (r) {
                    if (!r.ok) throw new Error('缺少回放数据：' + name);
                    return r.json();
                });
        }
        return cache[name];
    }

    var index = { trace: Object.create(null), task: Object.create(null), evalRun: Object.create(null) };
    var scenarios = null;
    var cacheCursor = 0;
    var cacheMode = false;
    var pendingNotice = null;

    function buildIndex(all) {
        scenarios = all;
        Object.keys(all).forEach(function (k) {
            all[k].forEach(function (sc) {
                index.trace[sc.traceId] = sc;
                if (k === 'eval') index.evalRun[sc.traceId] = sc;
            });
        });
    }

    var ready = Promise.all([
        load('scenarios.json'),
        load('health.json'),
        load('docs.json'),
        load('chunks.json'),
        load('chunk-details.json'),
        load('ingest-stream.json'),
        load('doc-tasks.json'),
        load('runs.json'),
        load('eval-cases.json'),
        load('eval-runs.json'),
        load('cache-stats.json')
    ]).then(function (r) {
        buildIndex(r[0].data);
        return {
            health: r[1], docs: r[2], chunks: r[3], details: r[4], ingestStream: r[5],
            docTasks: r[6], runs: r[7], evalCases: r[8], evalRuns: r[9], cacheStats: r[10]
        };
    });
    ready.catch(function (e) { console.error('[demo] 回放数据加载失败', e); });

    /* ---------------------------------------------------------- 模糊匹配 */

    function bigrams(s) {
        var t = String(s || '').toLowerCase().replace(/[\s，。、？！,.;:：；（）()【】\[\]「」『』"'‘’“”\-—…?]/g, '');
        var set = new Set();
        if (t.length < 2) { if (t) set.add(t); return set; }
        for (var i = 0; i < t.length - 1; i++) set.add(t.slice(i, i + 2));
        return set;
    }

    function similarity(a, b) {
        var A = bigrams(a), B = bigrams(b);
        if (!A.size || !B.size) return 0;
        var inter = 0;
        A.forEach(function (x) { if (B.has(x)) inter++; });
        return (2 * inter) / (A.size + B.size);
    }

    /** 在候选里找最像的一条，返回 {sc, score} */
    function pick(list, query) {
        var best = null, bestScore = 0;
        (list || []).forEach(function (sc) {
            var s = similarity(query, sc.query);
            if (s > bestScore) { bestScore = s; best = sc; }
        });
        return { sc: best, score: bestScore };
    }

    /* ---------------------------------------------------------- 假响应 */

    function reply(obj, ms) {
        return new Promise(function (resolve) {
            setTimeout(function () {
                resolve({
                    ok: obj && obj.ok !== false,
                    status: obj && obj.ok === false ? 400 : 200,
                    json: function () { return Promise.resolve(obj); },
                    text: function () { return Promise.resolve(JSON.stringify(obj)); }
                });
            }, ms || 30);
        });
    }

    function pathOf(input) {
        var raw = typeof input === 'string' ? input : (input && input.url) || '';
        try {
            var u = new URL(raw, document.baseURI);
            var m = u.pathname.match(/\/api\/.*$/);
            return m ? m[0] : u.pathname;
        } catch (e) {
            return raw;
        }
    }

    function parseBody(init) {
        if (!init || !init.body) return {};
        var b = init.body;
        if (typeof b === 'string') {
            try { return JSON.parse(b); } catch (e) { return Object.fromEntries(new URLSearchParams(b)); }
        }
        if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) {
            return Object.fromEntries(b);
        }
        return {};
    }

    /* ---------------------------------------------------------- 场景执行 */

    /**
     * 一次「提交 → 日志流 → 结果」的完整回放。
     * 提交接口返回录制时的真实 id，后续 GET 靠这个 id 找回场景。
     */
    function playScenario(sc, query) {
        if (!sc) {
            pendingNotice = '回放数据里没有可用场景。';
            return reply({ ok: false, message: '演示数据缺失' });
        }
        var score = similarity(query, sc.query);
        if (query && score < 0.32) {
            pendingNotice = '演示数据里没有完全对口的问题，已回放最接近的一条：' + sc.query;
        } else if (query && score < 0.999) {
            pendingNotice = '演示模式：已匹配到最接近的问题「' + sc.query + '」';
        } else {
            pendingNotice = null;
        }
        return reply({
            ok: true,
            data: { traceId: sc.traceId, runId: sc.traceId, taskId: sc.traceId },
            message: null
        }, 120);
    }

    function pickFrom(key, query, fallbackFirst) {
        var list = (scenarios && scenarios[key]) || [];
        var r = pick(list, query);
        if (r.sc && r.score > 0) return r.sc;
        return fallbackFirst ? list[0] : null;
    }

    function isCacheDemoQuery(q) {
        var list = (scenarios && scenarios.cache) || [];
        if (!list.length) return false;
        return similarity(q, list[0].query) > 0.55 || similarity(q, list[list.length - 1].query) > 0.55;
    }

    function nextCacheScenario() {
        var list = (scenarios && scenarios.cache) || [];
        if (cacheCursor >= list.length) return null;
        return list[cacheCursor++];
    }

    /* ---------------------------------------------------------- 路由分派 */

    async function handle(input, init) {
        var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        var p = pathOf(input);
        var body = parseBody(init);
        var d = await ready;

        /* ---- 健康检查 ---- */
        if (/^\/api\/health$/.test(p) && method === 'GET') {
            return reply(d.health.data ? d.health : { ok: true, data: {} });
        }
        if (/^\/api\/health\/qwen-probe$/.test(p)) {
            return reply({ ok: true, data: { ok: true, detail: '演示模式不消耗额度' }, message: null });
        }

        /* ---- 阶段一：入库 ---- */
        if (/^\/api\/ingest\/(text|file)$/.test(p) && method === 'POST') {
            var t = (d.docTasks.data || [])[0];
            pendingNotice = '演示模式：这次入库是回放真实运行过程，不会写入任何数据库。';
            return reply({ ok: true, data: { taskId: t ? t.id : 'demo-task' }, message: null }, 400);
        }
        if (/^\/api\/ingest\/tasks\/[^/]+\/stream$/.test(p)) {
            return reply({ ok: true });
        }
        if (/^\/api\/ingest\/tasks\/[^/]+$/.test(p)) {
            var snap = (d.docTasks.data || [])[0];
            return reply({ ok: true, data: snap, message: null });
        }
        if (/^\/api\/ingest\/tasks$/.test(p)) {
            return reply({ ok: true, data: d.docTasks.data, message: null });
        }
        if (/^\/api\/ingest\/docs$/.test(p)) {
            return reply(d.docs);
        }
        var mChunks = p.match(/^\/api\/ingest\/docs\/(\d+)\/chunks$/);
        if (mChunks) {
            var docId = Number(mChunks[1]);
            var payload = d.chunks.data;
            if (payload.docId !== docId) return reply({ ok: true, data: [], message: null });
            return reply({ ok: true, data: payload.list, message: null });
        }
        var mDetail = p.match(/^\/api\/ingest\/docs\/(\d+)\/chunks\/(\d+)$/);
        if (mDetail) {
            var item = d.details.data[mDetail[2]];
            if (!item) return reply({ ok: false, message: '这一片没有录制' });
            return reply({ ok: true, data: item, message: null });
        }

        /* ---- 运行记录 ---- */
        if (/^\/api\/runs$/.test(p)) {
            return reply(d.runs);
        }

        /* ---- 阶段二~五、七、八：统一模式 ---- */
        var stageMap = [
            { post: /^\/api\/retrieve$/, key: 'retrieve' },
            { post: /^\/api\/hybrid$/, key: 'hybrid' },
            { post: /^\/api\/rerank$/, key: 'rerank' },
            { post: /^\/api\/context$/, key: 'context' },
            { post: /^\/api\/generate$/, key: 'generate' },
            { post: /^\/api\/rewrite$/, key: 'rewrite' }
        ];

        for (var i = 0; i < stageMap.length; i++) {
            if (stageMap[i].post.test(p) && method === 'POST') {
                var q = body.query || '';

                if (stageMap[i].key === 'generate' && cacheMode && isCacheDemoQuery(q)) {
                    var csc = nextCacheScenario();
                    if (csc) {
                        if (cacheCursor >= scenarios.cache.length) cacheMode = false;
                        pendingNotice = null;
                        return reply({ ok: true, data: { traceId: csc.traceId }, message: null }, 80);
                    }
                }
                return playScenario(pickFrom(stageMap[i].key, q, true), q);
            }
        }

        /* 结果读取：/api/xxx/{id} */
        var mResult = p.match(/^\/api\/(retrieve|hybrid|rerank|context|generate|rewrite)\/([^/]+)$/);
        if (mResult && method === 'GET') {
            var sc = index.trace[mResult[2]];
            if (!sc) return reply({ ok: false, message: '演示数据里没有这个 trace' });
            return reply(sc.result, 180);
        }

        /* ---- 阶段六：评测 ---- */
        if (/^\/api\/eval\/cases$/.test(p) && method === 'GET') {
            return reply({ ok: true, data: d.evalCases.data, message: null });
        }
        if (/^\/api\/eval\/cases/.test(p)) {
            return reply({ ok: false, message: '演示模式不开放用例编辑' });
        }
        if (/^\/api\/eval\/run$/.test(p) && method === 'POST') {
            var esc = (scenarios.eval || [])[0];
            pendingNotice = '演示模式：回放一次真实的 15 用例 × 5 策略评测，不会调用任何模型。';
            return reply({ ok: true, data: { runId: esc.traceId }, message: null }, 200);
        }
        if (/^\/api\/eval\/runs$/.test(p)) {
            return reply(d.evalRuns);
        }
        var mEval = p.match(/^\/api\/eval\/([^/]+)$/);
        if (mEval && method === 'GET') {
            var es = index.evalRun[mEval[1]];
            if (!es) return reply({ ok: false, message: '演示数据里没有这次评测' });
            return reply(es.result, 200);
        }

        /* ---- 阶段九：缓存 ---- */
        if (/^\/api\/cache$/.test(p) && method === 'DELETE') {
            cacheMode = true;
            cacheCursor = 0;
            return reply({ ok: true, data: { cleared: 12 }, message: null });
        }
        if (/^\/api\/cache\/l1$/.test(p) && method === 'DELETE') {
            return reply({ ok: true, data: { cleared: 1 }, message: null });
        }
        if (/^\/api\/cache\/stats$/.test(p)) {
            return reply(d.cacheStats);
        }
        if (/^\/api\/cache\/lookup$/.test(p) && method === 'POST') {
            var lq = body.query || '';
            var list9 = (scenarios.cache || []).filter(function (x) { return x.stage === 9; });
            var hitSc = pick(list9, lq);
            var hit = hitSc.sc && hitSc.score > 0.55;
            return reply({
                ok: true,
                data: {
                    hit: !!hit,
                    layer: hit ? 'L3' : 'MISS',
                    similarity: hit ? Math.min(0.99, 0.72 + hitSc.score * 0.25) : 0.31,
                    costMs: hit ? 268 : 12,
                    answerChars: hit ? 486 : 0,
                    preview: hit ? '（演示模式）这条问题命中了语义缓存，答案正文不在这里重复展示。' : ''
                },
                message: null
            }, 260);
        }

        /* 其它接口一律兜底，避免前端抛异常 */
        console.warn('[demo] 未回放的接口：', method, p);
        return reply({ ok: false, message: '演示模式未覆盖该接口：' + p });
    }

    /* ---------------------------------------------------------- 替换 fetch */

    window.fetch = function (input, init) {
        var p = pathOf(input);
        // replay / 静态资源走真 fetch
        if (!/\/api\//.test(p)) return nativeFetch(input, init);
        return handle(input, init);
    };

    /* ------------------------------------------------- 替换 EventSource */

    function ReplayEventSource(url) {
        this.url = String(url);
        this.readyState = 0;
        this.onerror = null;
        this._handlers = Object.create(null);
        this._timers = [];
        this._closed = false;
        var self = this;
        this._boot = setTimeout(function () { self._run(); }, 40);
    }

    ReplayEventSource.prototype.addEventListener = function (type, fn) {
        (this._handlers[type] || (this._handlers[type] = [])).push(fn);
    };

    ReplayEventSource.prototype.removeEventListener = function (type, fn) {
        var arr = this._handlers[type];
        if (arr) this._handlers[type] = arr.filter(function (f) { return f !== fn; });
    };

    ReplayEventSource.prototype.close = function () {
        this._closed = true;
        this.readyState = 2;
        clearTimeout(this._boot);
        this._timers.forEach(clearTimeout);
        this._timers = [];
    };

    ReplayEventSource.prototype._emit = function (type, payload) {
        if (this._closed) return;
        if (type === 'error') {
            if (typeof this.onerror === 'function') this.onerror({ target: this });
            return;
        }
        var arr = this._handlers[type] || [];
        for (var i = 0; i < arr.length; i++) {
            try { arr[i](payload); } catch (e) { console.error('[demo] 事件处理出错', e); }
        }
    };

    ReplayEventSource.prototype._resolve = function () {
        var self = this;
        return ready.then(function (d) {
            var u = self.url;
            if (/\/api\/ingest\/tasks\/[^/]+\/stream/.test(u)) {
                return { sse: d.ingestStream.data, query: null };
            }
            var m = u.match(/\/api\/(?:\w+|eval)\/([^/]+)\/stream/);
            if (m && index.trace[m[1]]) {
                var sc = index.trace[m[1]];
                return { sse: sc.sse, query: sc.query };
            }
            return null;
        });
    };

    ReplayEventSource.prototype._run = function () {
        var self = this;
        this._resolve().then(function (found) {
            if (self._closed) return;
            if (!found || !found.sse || !found.sse.length) {
                self._emit('error', {});
                return;
            }
            var list = found.sse;
            var n = list.length;
            // 按条数自适应节奏：日志多就滚快点，否则看不过来
            var gap = n > 120 ? 24 : n > 60 ? 42 : n > 25 ? 70 : 120;
            var i = 0;

            function step() {
                if (self._closed) return;
                if (i === 0 && pendingNotice) {
                    self._emit('log', {
                        data: JSON.stringify({
                            seq: 0,
                            ts: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
                            level: 'WARN',
                            stage: 'CLIENT',
                            message: pendingNotice,
                            costMs: 0,
                            detail: '这是一份提前录好的运行记录，所以耗时数字是当时真实产生的，'
                                + '但你看到的时间是回放时间。页面上不会有任何请求发到别人的服务器。'
                        })
                    });
                    pendingNotice = null;
                }
                if (i >= n) return;
                var ev = list[i++];
                if (ev.event === 'end') {
                    self._emit('end', { data: '', type: 'end', target: self });
                    return;
                }
                var data = typeof ev.data === 'string' ? ev.data : JSON.stringify(ev.data);
                self._emit(ev.event || 'message', {
                    data: data,
                    type: ev.event || 'message',
                    lastEventId: String(i),
                    target: self
                });
                if (i < n) {
                    var jitter = gap * (0.72 + Math.random() * 0.56);
                    self._timers.push(setTimeout(step, jitter));
                }
            }
            step();
        });
    };

    window.EventSource = ReplayEventSource;
    if (NativeEventSource) {
        ReplayEventSource.CONNECTING = 0;
        ReplayEventSource.OPEN = 1;
        ReplayEventSource.CLOSED = 2;
    }

    /* ---------------------------------------------------------- 界面增强 */

    var CHIPS = [
        { input: 'queryInput', btn: 'btnRetrieve', key: 'retrieve' },
        { input: 'hybridQuery', btn: 'btnHybrid', key: 'hybrid' },
        { input: 'rerankQuery', btn: 'btnRerank', key: 'rerank' },
        { input: 'ctxQuery', btn: 'btnContext', key: 'context' },
        { input: 'genQuery', btn: 'btnGenerate', key: 'generate' },
        { input: 'rwQuery', btn: 'btnRewrite', key: 'rewrite' }
    ];

    function injectStyle() {
        var css = ''
            + '#demoBanner{display:flex;align-items:center;gap:10px;flex-wrap:wrap;'
            + 'margin:0 0 14px;padding:10px 14px;border-radius:10px;'
            + 'background:#FFF8E6;border:1px solid #F0D9A8;color:#7A5B12;font-size:13px}'
            + '#demoBanner b{font-weight:500;color:#5E460D}'
            + '#demoBanner .db-dot{width:7px;height:7px;border-radius:50%;background:#D89A28;flex:none}'
            + '#demoBanner a{margin-left:auto;color:#8A6A1E;text-decoration:none;'
            + 'border-bottom:1px dotted #C9A85E;font-size:12px}'
            + '.demo-chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;align-items:center}'
            + '.demo-chips .dc-label{font-size:12px;color:#8A8F98;margin-right:2px}'
            + '.demo-chip{font:inherit;font-size:12px;line-height:1;padding:7px 11px;border-radius:14px;'
            + 'background:#F2F5F9;border:1px solid #DCE3EC;color:#41505F;cursor:pointer;'
            + 'transition:background .15s,border-color .15s}'
            + '.demo-chip:hover{background:#E6EEF8;border-color:#B9CCE0;color:#1F3A54}'
            + '.demo-chips .dc-hint{font-size:12px;color:#A0A6B0}';
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    }

    function injectBanner() {
        var host = document.querySelector('.stage-nav');
        if (!host || document.getElementById('demoBanner')) return;
        var bar = document.createElement('div');
        bar.id = 'demoBanner';
        bar.innerHTML = '<span class="db-dot"></span>'
            + '<span><b>演示模式</b> · 页面里的每一次入库、检索、评测、生成，都是真实跑过一遍后录下来的，'
            + '点开就能看完整日志。这里没有数据库，也没有任何 API Key。</span>'
            + '<a href="?live=1">连接真实后端 →</a>';
        host.parentNode.insertBefore(bar, host.nextSibling);
    }

    function injectChips(d) {
        CHIPS.forEach(function (cfg) {
            var input = document.getElementById(cfg.input);
            if (!input || input.dataset.demoChips) return;
            var list = (scenarios && scenarios[cfg.key]) || [];
            if (!list.length) return;
            input.dataset.demoChips = '1';

            var box = document.createElement('div');
            box.className = 'demo-chips';
            var label = document.createElement('span');
            label.className = 'dc-label';
            label.textContent = '试试这些：';
            box.appendChild(label);

            var seen = Object.create(null);
            list.forEach(function (sc) {
                if (seen[sc.query]) return;
                seen[sc.query] = 1;
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'demo-chip';
                b.textContent = sc.query;
                b.addEventListener('click', function () {
                    input.value = sc.query;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    var btn = cfg.btn && document.getElementById(cfg.btn);
                    if (btn && !btn.disabled) {
                        setTimeout(function () { btn.click(); }, 80);
                    }
                });
                box.appendChild(b);
            });

            if (list.some(function (sc) { return sc.stage === 9; }) === false && cfg.key === 'generate') {
                var hint = document.createElement('span');
                hint.className = 'dc-hint';
                hint.textContent = '（回答由 qwen-plus 现场生成后录下）';
                box.appendChild(hint);
            }

            input.parentNode.insertBefore(box, input.nextSibling);
        });
    }

    function boot() {
        injectStyle();
        injectBanner();
        ready.then(function (d) {
            injectChips(d);
        }).catch(function () { });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }

    console.log('%c[demo] 静态回放模式：所有 /api 请求都不会离开浏览器',
        'color:#B8860B;font-weight:500');
})();
