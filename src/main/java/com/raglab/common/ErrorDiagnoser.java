package com.raglab.common;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把异常翻译成「人能直接照着排查」的诊断文本。
 *
 * 只抛一句 "Connection refused" 是没用的，必须告诉使用者：
 * 哪个环节、连的哪个地址、最常见的原因是什么、下一步该敲什么命令。
 * 这个类就是右侧黑窗口「报错详细展示」的底气。
 */
public final class ErrorDiagnoser {

    private ErrorDiagnoser() {
    }

    public enum Kind {
        NETWORK("网络连接失败"),
        TIMEOUT("请求超时"),
        AUTH("鉴权失败"),
        RATE_LIMIT("触发限流/配额不足"),
        CLIENT("请求被对方拒绝（4xx）"),
        SERVER("对方服务内部错误（5xx）"),
        DATABASE("数据库异常"),
        IO("文件读写异常"),
        STATE("状态/参数不合法"),
        UNKNOWN("未归类异常");

        private final String desc;

        Kind(String desc) {
            this.desc = desc;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * @param t      抛出的异常
     * @param target 出问题的目标组件：Chroma / Qwen / MySQL / File ...
     * @param hint   调用方补充的上下文，可为 null
     */
    public static String diagnose(Throwable t, String target, String hint) {
        Kind kind = classify(t);
        StringBuilder sb = new StringBuilder();

        sb.append("【分类】").append(kind.name()).append(" — ").append(kind.getDesc()).append('\n');
        sb.append("【目标】").append(target).append('\n');
        if (hint != null && !hint.isBlank()) {
            sb.append("【上下文】").append(hint).append('\n');
        }

        // ---- 异常本体 ----
        sb.append("【异常】").append(t.getClass().getName()).append('\n');
        sb.append("【信息】").append(nullToEmpty(t.getMessage())).append('\n');

        // ---- HTTP 响应体（RestClient 特有，最有价值）----
        // 业务代码通常会包一层 ExternalServiceException，所以要在整条 cause 链里找
        RestClientResponseException httpEx = findByType(t, RestClientResponseException.class);
        if (httpEx != null) {
            sb.append("【HTTP 状态】").append(httpEx.getStatusCode().value())
                    .append(' ').append(httpEx.getStatusText()).append('\n');
            String body = httpEx.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                sb.append("【响应体】").append(truncate(body, 1500)).append('\n');
            }
        }

        // ---- 根因链 ----
        List<String> chain = new ArrayList<>();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            String msg = cur.getMessage();
            chain.add(cur.getClass().getSimpleName() + (msg == null ? "" : ": " + truncate(msg, 200)));
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        if (chain.size() > 1) {
            sb.append("【根因链】").append(String.join("  →  ", chain)).append('\n');
        }

        // ---- 定位 ----
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) {
            sb.append("【抛出位置】").append(st[0]).append('\n');
        }
        sb.append("【业务调用点】").append(firstBusinessFrame(st)).append('\n');

        // ---- 可能原因与建议 ----
        sb.append("【可能原因】\n");
        for (String reason : reasons(kind, target)) {
            sb.append("  - ").append(reason).append('\n');
        }
        sb.append("【下一步】\n");
        for (String action : actions(kind, target)) {
            sb.append("  > ").append(action).append('\n');
        }

        // ---- 栈（截断）----
        sb.append("【栈顶 8 层】\n");
        for (int i = 0; i < Math.min(8, st.length); i++) {
            sb.append("  at ").append(st[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * 沿 cause 链分类：外层往往是我们自己包的 ExternalServiceException，
     * 真正能说明问题的是里面那层 RestClientResponseException / ConnectException。
     */
    public static Kind classify(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            Kind k = classifySingle(cur);
            if (k != Kind.UNKNOWN) {
                return k;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return Kind.UNKNOWN;
    }

    private static Kind classifySingle(Throwable t) {
        if (t instanceof RestClientResponseException http) {
            int code = http.getStatusCode().value();
            if (code == 401 || code == 403) {
                return Kind.AUTH;
            }
            if (code == 429) {
                return Kind.RATE_LIMIT;
            }
            if (code >= 500) {
                return Kind.SERVER;
            }
            return Kind.CLIENT;
        }
        if (t instanceof SocketTimeoutException) {
            return Kind.TIMEOUT;
        }
        if (t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof ResourceAccessException) {
            return Kind.NETWORK;
        }
        if (t instanceof SQLException) {
            return Kind.DATABASE;
        }
        if (t instanceof IOException) {
            return Kind.IO;
        }
        if (t instanceof IllegalArgumentException || t instanceof IllegalStateException) {
            return Kind.STATE;
        }
        return Kind.UNKNOWN;
    }

    private static <T extends Throwable> T findByType(Throwable t, Class<T> type) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (type.isInstance(cur)) {
                return type.cast(cur);
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static List<String> reasons(Kind kind, String target) {
        List<String> list = new ArrayList<>();
        switch (kind) {
            case NETWORK -> {
                list.add("目标服务没启动，或者端口映射跟配置不一致");
                list.add("容器起来了但只监听了容器内 8000，宿主机映射端口被占用后换了端口");
                list.add("被本机防火墙/代理拦截（尤其公司网络的 HTTP_PROXY）");
            }
            case TIMEOUT -> {
                list.add("网络慢，或者对方处理时间超过了配置的 timeout-seconds");
                list.add("批量过大：一次请求塞了太多文本，Qwen 处理不过来");
            }
            case AUTH -> {
                list.add("API Key 无效、过期，或者复制时带了空格/换行");
                list.add("Key 没有该模型的权限，或账号欠费被停用");
            }
            case RATE_LIMIT -> {
                list.add("QPS/并发超限，DashScope 免费额度通常限制较严");
                list.add("单次批量超过模型上限（text-embedding 系列单次最多 10 条）");
            }
            case CLIENT -> {
                list.add("请求体字段名或类型不对（Chroma 对 metadatas 的 value 类型很挑：只接受 string/int/float/bool）");
                list.add("向量维度与 collection 创建时的维度不一致");
            }
            case SERVER -> list.add("对方服务内部故障，通常是临时的，可重试");
            case DATABASE -> {
                list.add("MySQL 没启动、密码错、或 rag_lab 库不存在");
                list.add("表结构缺失 —— schema.sql 没执行成功");
            }
            case IO -> list.add("文件被占用/没有读权限/编码不是 UTF-8");
            case STATE -> list.add("入参不合法，或当前状态不允许这个操作（比如空文档、空切片）");
            default -> list.add("未知异常，请结合栈信息判断");
        }
        return list;
    }

    private static List<String> actions(Kind kind, String target) {
        List<String> list = new ArrayList<>();
        boolean chroma = target != null && target.toLowerCase().contains("chroma");
        boolean qwen = target != null && (target.toLowerCase().contains("qwen") || target.contains("DashScope"));
        switch (kind) {
            case NETWORK -> {
                if (chroma) {
                    list.add("docker ps --format \"table {{.Names}}\\t{{.Ports}}\" | grep chroma");
                    list.add("curl http://127.0.0.1:58019/api/v2/heartbeat   # 换成你实际的映射端口");
                    list.add("改 application.yml → raglab.chroma.base-url 后重启");
                } else if (qwen) {
                    list.add("curl -I https://dashscope.aliyuncs.com   # 确认外网可达");
                } else {
                    list.add("确认目标服务的地址与端口");
                }
            }
            case TIMEOUT -> list.add("调大 raglab.embedding.timeout-seconds 或调小 batch-size");
            case AUTH -> {
                list.add("echo $DASHSCOPE_API_KEY   # 确认环境变量非空、无空格");
                list.add("到 https://bailian.console.aliyun.com 检查 Key 状态与额度");
            }
            case RATE_LIMIT -> list.add("把 raglab.embedding.batch-size 调到 10 以内，并在批之间加 sleep");
            case CLIENT -> {
                list.add("对照响应体里的 message 字段定位是哪个字段不合法");
                list.add("确认 embedding 维度 = collection 维度 = 1024");
            }
            case DATABASE -> {
                list.add("mysql -uroot -p -e \"show tables\" rag_lab");
                list.add("核对 application.yml 里的账号密码与库名");
            }
            case IO -> list.add("换 UTF-8 编码另存后再试，或确认文件没被其他程序占用");
            case STATE -> list.add("检查输入内容是否为空 / 切片参数是否合理（size > overlap）");
            default -> list.add("把这段诊断连同栈一起看，先定位是哪一个外部依赖失败");
        }
        return list;
    }

    private static String firstBusinessFrame(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            if (e.getClassName().startsWith("com.raglab")) {
                return e.toString();
            }
        }
        return "（栈里没有业务代码帧）";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(共 " + s.length() + " 字符)";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
