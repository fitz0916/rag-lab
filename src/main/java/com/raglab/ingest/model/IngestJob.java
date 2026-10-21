package com.raglab.ingest.model;

/**
 * 一次入库任务的输入。
 *
 * 文件先读成 byte[]，因为异步线程里再去读 MultipartFile 已经晚了（临时文件已被清理）。
 *
 * @param text        纯文本入库时使用；文件入库时 bytes 非空、text 为 null
 * @param chunkSize   手动覆盖切片长度，null 表示用 application.yml 里的配置
 * @param chunkOverlap 手动覆盖重叠长度，null 表示用配置
 */
public record IngestJob(
        String taskId,
        String docName,
        String sourceType,
        String fileName,
        byte[] bytes,
        String text,
        Integer chunkSize,
        Integer chunkOverlap
) {
    public static IngestJob ofFile(String taskId, String fileName, String docName, byte[] bytes,
                                   Integer chunkSize, Integer chunkOverlap) {
        return new IngestJob(taskId, docName, "FILE", fileName, bytes, null, chunkSize, chunkOverlap);
    }

    public static IngestJob ofText(String taskId, String docName, String text,
                                   Integer chunkSize, Integer chunkOverlap) {
        return new IngestJob(taskId, docName, "TEXT", null, null, text, chunkSize, chunkOverlap);
    }

    public int rawSize() {
        return bytes != null ? bytes.length
                : (text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
