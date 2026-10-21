package com.raglab.ingest.model;

/**
 * 切片草稿：还在内存里、尚未落库的切片。
 *
 * @param index     文档内序号
 * @param heading   继承自上级 Markdown 标题，检索时可以一起带出去做上下文
 * @param content   正文
 * @param charCount 字符数
 * @param chromaId  向量库主键（写库前就已生成，保证 Chroma 与 MySQL 可对账）
 */
public record ChunkDraft(int index, String heading, String content, int charCount, String chromaId) {

    /** 中文约 1.5 字 / token，粗估用 */
    public int tokenEstimate() {
        return (int) Math.ceil(charCount * 1.5);
    }
}
