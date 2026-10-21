package com.raglab.retrieval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RerankRunner {

    private final RerankRetriever retriever;

    public RerankRunner(RerankRetriever retriever) {
        this.retriever = retriever;
    }

    @Async("ingestExecutor")
    public void run(String traceId, String query, int recallTopN, int finalTopK) {
        retriever.run(traceId, query, recallTopN, finalTopK);
    }
}
