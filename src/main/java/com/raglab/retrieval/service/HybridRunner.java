package com.raglab.retrieval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class HybridRunner {

    private final HybridRetriever retriever;

    public HybridRunner(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int vecTopN, int bm25TopN, int finalTopK, int rrfK) {
        retriever.run(traceId, query, vecTopN, bm25TopN, finalTopK, rrfK);
    }
}
