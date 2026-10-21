package com.raglab.ingest.service;

import com.raglab.ingest.model.IngestJob;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步执行器。
 *
 * 单独抽一个 Bean 是因为 @Async 靠动态代理生效：
 * 在 IngestService 里自己调自己的 @Async 方法，注解会被静默忽略（这是 Spring 的经典坑）。
 */
@Component
public class IngestRunner {

    private final IngestPipeline pipeline;

    public IngestRunner(IngestPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Async("ingestExecutor")
    public void run(IngestJob job) {
        pipeline.run(job);
    }
}
