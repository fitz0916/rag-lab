package com.raglab.gen.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class GenerateRunner {

    private final GenerateRetriever retriever;

    public GenerateRunner(GenerateRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int recallTopN, int topK, int maxChars) {
        retriever.run(traceId, query, recallTopN, topK, maxChars);
    }
}
