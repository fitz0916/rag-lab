package com.raglab.eval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EvalRunner {

    private final EvalPipeline pipeline;

    public EvalRunner(EvalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Async("ingestExecutor")
    public void run(String runId, List<Long> caseIds, List<EvalRetriever.Strategy> strategies,
                    int topK, int recallTopN) {
        pipeline.run(runId, caseIds, strategies, topK, recallTopN);
    }
}
