package com.raglab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAG Lab —— 按《RAG 检索过程及优化详解》一文逐步实现。
 *
 * 阶段一：文档 → 清洗 → 切片 → 向量化 → 写入 Chroma，全过程 SSE 日志可观测。
 * 阶段二：基础向量检索（baseline）。
 * 阶段三：混合检索（向量 + BM25）+ RRF 融合。
 * 阶段四~：重排序 / 上下文控制 / 查询重写 / 缓存。
 */
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class RagLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagLabApplication.class, args);
    }
}
