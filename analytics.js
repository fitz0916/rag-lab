/* ============================================================
   访问统计（GoatCounter）
   ------------------------------------------------------------
   为什么单独放一个文件：
     · 统计配置只在一个地方，改一行就生效，不用翻 index.html
     · 本地开发不统计 —— 否则你自己反复刷新会把数据刷脏
     · 脚本加载失败不影响页面 —— 它是 async 注入的，挂了就只是少记一次

   接入三步：
     1. 注册 https://www.goatcounter.com/signup
        填的 "code" 就是你的子域名前缀，比如填 ragscope
     2. 拿到 code 后，把下面 CODE 改成它
     3. 重新构建发布（版本号记得 +1）

   如果想在页面上显示「已有 N 人访问」，见文件末尾的 showCounter()。
   ============================================================ */
(function () {
    'use strict';

    /* ↓↓↓ 只有这一行需要改 ↓↓↓ */
    var CODE = 'ragscope';   // GoatCounter 的子域名前缀 → https://ragscope.goatcounter.com
    /* ↑↑↑ 只有这一行需要改 ↑↑↑ */

    // 没填就别加载，避免请求一个不存在的域名
    if (!CODE) {
        return;
    }

    // 本地调试不统计：localhost / 127.0.0.1 / 局域网 IP / file://
    var host = location.hostname;
    var isLocal = location.protocol === 'file:'
        || /^(localhost|127\.0\.0\.1|\[::1\])$/.test(host)
        || /^192\.168\./.test(host)
        || /^10\./.test(host)
        || /^172\.(1[6-9]|2\d|3[01])\./.test(host);
    if (isLocal) {
        return;
    }

    var s = document.createElement('script');
    s.async = true;
    s.dataset.goatcounter = 'https://' + CODE + '.goatcounter.com/count';
    // 加个来源标记，方便在仪表板里区分「从静态站来的」和「从别处来的」
    s.src = 'https://gc.zgo.at/count.js';
    s.onerror = function () {
        // 常见于国内网络：gc.zgo.at 被拦。页面不受影响，只是这次访问记不上。
        console.warn('[analytics] GoatCounter 脚本加载失败，本次访问未统计');
    };
    document.head.appendChild(s);

    /* ------------------------------------------------------------
       可选：在页面上显示总访问量
       ------------------------------------------------------------
       GoatCounter 提供一个只读的 JSON 接口：
         https://YOURCODE.goatcounter.com/counter/TOTAL.json
       它需要在后台 Settings 里打开
       "Allow adding visitor counts on your website"。

       打开后把下面这段加进 index.html 里想显示的位置：

         <div id="visitCount" class="visit-count" style="display:none"></div>

       再取消本函数最后一行的注释即可。
       ------------------------------------------------------------ */
    window.showCounter = function (targetId) {
        var el = document.getElementById(targetId || 'visitCount');
        if (!el) return;
        fetch('https://' + CODE + '.goatcounter.com/counter/TOTAL.json')
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (!d || !d.count) return;
                el.textContent = '已有 ' + d.count + ' 次访问';
                el.style.display = '';
            })
            .catch(function () { /* 静默失败，不打扰访客 */ });
    };

    // 需要显示访问量就取消下面这行的注释
    // window.showCounter('visitCount');
})();
