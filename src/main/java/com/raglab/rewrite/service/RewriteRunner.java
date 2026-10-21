package com.raglab.rewrite.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RewriteRunner {

    private final RewriteRetriever retriever;

    public RewriteRunner(RewriteRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int variants, int topK, int recallN, boolean rerank) {
        retriever.run(traceId, query, variants, topK, recallN, rerank);
    }
}
