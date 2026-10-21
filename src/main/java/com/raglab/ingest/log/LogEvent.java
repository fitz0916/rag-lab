package com.raglab.ingest.log;

/**
 * 一条步骤日志。SSE 推送和 DB 落库用的是同一个结构，保证「看到的」和「存下来的」一致。
 *
 * @param seq    任务内自增序号（从 1 开始），前端按它排序、去重
 * @param ts     时间戳 HH:mm:ss.SSS
 * @param level  INFO / OK / WARN / ERROR
 * @param stage  步骤编码，见 {@link com.raglab.common.Stage}
 * @param message 一句话说清发生了什么
 * @param costMs 该步骤耗时（毫秒）
 * @param detail 详细上下文：请求/响应/指标/异常诊断，可能为 null
 */
public record LogEvent(
        int seq,
        String ts,
        String level,
        String stage,
        String message,
        long costMs,
        String detail
) {
    public static final String INFO = "INFO";
    public static final String OK = "OK";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";
}
