package com.raglab.retrieval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ContextRunner {

    private final ContextRetriever retriever;

    public ContextRunner(ContextRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int recallTopN, int topK, String strategy, int maxChars) {
        retriever.run(traceId, query, recallTopN, topK, strategy, maxChars);
    }
}
