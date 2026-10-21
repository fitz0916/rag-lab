package com.raglab.retrieval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 检索异步执行器。同样单独抽 Bean，避免 @Async 自调用失效。
 */
@Component
public class RetrieveRunner {

    private final VectorRetriever retriever;

    public RetrieveRunner(VectorRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int topK) {
        retriever.run(traceId, query, topK);
    }
}
