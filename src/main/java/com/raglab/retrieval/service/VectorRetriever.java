package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.dao.RetrieveTraceDao;
import com.raglab.retrieval.model.RetrieveHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 基础向量检索（阶段二 / 对应文章「二、基础检索流程」）。
 *
 * 四步：
 *   问题 → 向量化 → Chroma 最近邻 → 距离转相似度 → Top-K
 *
 * 这一步故意只做最朴素的事，不加查询重写、不加混合检索、不加重排。
 * 因为它是**对照组**：后面所有优化的效果，都要拿这里的召回结果比。
 *
 * 日志写法和阶段一一致，所以右侧同一个黑窗口就能看全过程。
 */
@Service
public class VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);
    private static final int MAX_TOP_K = 20;

    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final RetrieveTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public VectorRetriever(QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                           RetrieveTraceDao traceDao, IngestLogBus logBus,
                           ObjectMapper objectMapper, RagLabProperties props) {
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public void run(String traceId, String rawQuery, int requestedTopK) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.QUERY_RECEIVE;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 1. 接收问题 =================
            stage = Stage.QUERY_RECEIVE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            if (query.length() > 500) {
                throw new IllegalArgumentException("问题过长（" + query.length() + " 字），请精简到 500 字以内");
            }
            int topK = requestedTopK <= 0 ? 5 : Math.min(requestedTopK, MAX_TOP_K);
            logger.info(stage, "问题：" + query);
            logger.info(stage, "Top-K = %d（上限 %d），集合 = %s"
                    .formatted(topK, MAX_TOP_K, chromaClient.collectionName()));
            logger.ok(stage, "问题校验通过", System.currentTimeMillis() - stepStart);

            // ================= 2. 问题向量化 =================
            stage = Stage.QUERY_EMBED;
            long embedStart = System.currentTimeMillis();
            stepStart = embedStart;
            logger.enter(stage);
            QwenEmbeddingClient.BatchResult br = embeddingClient.embedBatch(List.of(query));
            float[] qv = br.vectors().get(0);

            double sumSq = 0;
            for (float f : qv) {
                sumSq += (double) f * f;
            }
            double norm = Math.sqrt(sumSq);
            StringBuilder headStr = new StringBuilder("[");
            for (int i = 0; i < Math.min(8, qv.length); i++) {
                if (i > 0) {
                    headStr.append(", ");
                }
                headStr.append(String.format("%.4f", qv[i]));
            }
            headStr.append(qv.length > 8 ? ", …]" : "]");

            traceDao.updateQueryVector(traceId, qv.length, norm, headStr.toString());
            logger.ok(stage, "问题 → %d 维向量，耗时 %dms，%d tokens"
                            .formatted(qv.length, System.currentTimeMillis() - embedStart, br.promptTokens()),
                    ("模型      : %s\n" +
                            "维度      : %d\n" +
                            "L2 范数   : %.4f\n" +
                            "前 8 维   : %s\n\n" +
                            "注意：问题必须用和文档【同一个】embedding 模型。\n" +
                            "换了模型，两个向量就不在同一个语义空间里，算出来的距离没有任何意义。")
                            .formatted(embeddingClient.modelName(), qv.length, norm, headStr),
                    System.currentTimeMillis() - stepStart);

            // ================= 3. 向量检索 =================
            stage = Stage.VECTOR_SEARCH;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
            int total = chromaClient.count(collectionId);
            if (total == 0) {
                throw new IllegalStateException(
                        "集合 " + chromaClient.collectionName() + " 里一条向量都没有。先去「阶段一」入库一份文档再回来检索。");
            }
            logger.info(stage, "集合 %s 共 %d 条向量，开始找最近的 %d 条"
                    .formatted(chromaClient.collectionName(), total, topK));
            JsonNode res = chromaClient.query(collectionId, qv, topK);
            JsonNode idsArr = res.path("ids").path(0);
            JsonNode distArr = res.path("distances").path(0);
            JsonNode docsArr = res.path("documents").path(0);
            JsonNode metaArr = res.path("metadatas").path(0);
            int n = idsArr.size();
            logger.ok(stage, "Chroma 返回 %d 条候选，耗时 %dms"
                            .formatted(n, System.currentTimeMillis() - stepStart),
                    "请求体：query_embeddings=[1×%d 维], n_results=%d, include=[documents, metadatas, distances]"
                            .formatted(qv.length, topK),
                    System.currentTimeMillis() - stepStart);

            // ================= 4. 打分排序 =================
            stage = Stage.SCORE_RANK;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "换算公式：similarity = 1 - distance（cosine 距离越小 = 越像）");

            List<RetrieveHit> hits = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double distance = distArr.get(i).asDouble();
                JsonNode meta = metaArr.get(i);
                String id = idsArr.get(i).asText();
                String content = docsArr.get(i).asText("");
                long docId = parseLong(meta, "docId");
                String docName = textOf(meta, "docName");
                int chunkIndex = meta == null ? -1 : meta.path("chunkIndex").asInt(-1);
                String heading = textOf(meta, "heading");
                int charCount = meta == null ? 0 : meta.path("charCount").asInt(0);

                hits.add(new RetrieveHit(i + 1, round(distance), round(1 - distance),
                        id, docId, docName, chunkIndex, heading, content, charCount));
            }
            // Chroma 已按距离升序返回，这里再排一次是为了「不依赖对方的行为」——换向量库也不会错
            hits.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
            List<RetrieveHit> ranked = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                RetrieveHit h = hits.get(i);
                ranked.add(new RetrieveHit(i + 1, h.distance(), h.similarity(), h.chromaId(),
                        h.docId(), h.docName(), h.chunkIndex(), h.heading(), h.content(), h.charCount()));
            }
            logger.ok(stage, ranked.isEmpty() ? "没有命中"
                            : "按相似度降序排列，Top1 = " + ranked.get(0).similarity(),
                    System.currentTimeMillis() - stepStart);

            // ================= 5. 命中明细 =================
            stage = Stage.HIT_LIST;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            for (RetrieveHit h : ranked) {
                logger.info(stage, "#%d  相似度 %.4f  (距离 %.4f)  ·  %s  ·  #%d  ·  %s"
                        .formatted(h.rank(), h.similarity(), h.distance(),
                                h.heading().isBlank() ? "（无标题）" : h.heading(),
                                h.chunkIndex(), abbreviate(h.content(), 56)));
            }
            logger.ok(stage, "共 %d 条命中".formatted(ranked.size()), System.currentTimeMillis() - stepStart);

            // ================= 6. 完成 =================
            stage = Stage.QUERY_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            String hitsJson = objectMapper.writeValueAsString(ranked);
            Double top1 = ranked.isEmpty() ? null : ranked.get(0).similarity();
            traceDao.updateResult(traceId, ranked.size(), top1, total, hitsJson);
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);
            logger.ok(stage, "检索完成：命中 %d 条，Top1 相似度 %s，总耗时 %dms"
                            .formatted(ranked.size(), top1 == null ? "—" : String.format("%.4f", top1), totalCost),
                    "这条 baseline 就是后面做混合检索 / 重排 / 查询重写时的对照基准。", totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("检索失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case QUERY_EMBED -> "Qwen";
            case VECTOR_SEARCH -> "Chroma";
            default -> "Retrieve";
        };
    }

    private long parseLong(JsonNode meta, String key) {
        if (meta == null || meta.isNull()) {
            return 0;
        }
        try {
            return Long.parseLong(meta.path(key).asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String textOf(JsonNode meta, String key) {
        if (meta == null || meta.isNull()) {
            return "";
        }
        return meta.path(key).asText("");
    }

    private static double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\n", " ⏎ ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
