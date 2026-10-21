package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.dao.RerankTraceDao;
import com.raglab.retrieval.model.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序（阶段四 / 对应文章「三、优化3」）。
 *
 * 两段式检索：
 *   召回（粗）：向量 + BM25 + RRF，选出 Top-20 —— 宁可多召回，别漏
 *   精排（细）：交叉编码器逐条打分，只留 Top-3~5
 *
 * 为什么必须两段？
 *   交叉编码器太慢，不能对全库跑。但它准 —— 因为它把 query 和 doc 拼在一起看，
 *   能看到「python」和「3.10」这两个词是怎么相互作用的。
 *   而向量检索是双塔：query 编码一次、doc 编码一次，两者从没见过面，只比向量距离。
 *
 * 实测对比（问「Python 3.10 有什么新特性」）：
 *   向量检索：Python 3.11 那段排第 1（语义太像，分不出来）
 *   重排之后：3.10 那段 0.5287，3.11 那段 0.1857 —— 分得很清楚
 */
@Service
public class RerankRetriever {

    private static final Logger log = LoggerFactory.getLogger(RerankRetriever.class);
    private static final int MAX_RECALL = 50;

    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final ChineseTokenizer tokenizer;
    private final RerankClient rerankClient;
    private final RerankTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;

    public RerankRetriever(QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                           Bm25Index bm25Index, ChineseTokenizer tokenizer, RerankClient rerankClient,
                           RerankTraceDao traceDao, IngestLogBus logBus, ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.tokenizer = tokenizer;
        this.rerankClient = rerankClient;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
    }

    /** 召回阶段的一条候选 */
    private record RecallItem(int recallRank, String chromaId, double rrfScore,
                              int vecRank, double vecScore, int bm25Rank, double bm25Score,
                              long docId, String docName, int chunkIndex,
                              String heading, String content, int charCount) {
    }

    public void run(String traceId, String rawQuery, int recallTopN, int finalTopK) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.RERANK_RECALL;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 1. 混合召回 =================
            stage = Stage.RERANK_RECALL;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            int recallN = Math.max(5, Math.min(recallTopN, MAX_RECALL));
            int keepK = Math.max(1, Math.min(finalTopK, 20));

            logger.info(stage, "问题：" + query);
            logger.info(stage, "召回 Top-%d → 重排打分 → 保留 Top-%d".formatted(recallN, keepK));

            long recallStart = System.currentTimeMillis();
            List<RecallItem> recalled = recall(query, recallN);
            if (recalled.isEmpty()) {
                throw new IllegalStateException("召回结果为空，检查阶段一是否已经入库文档");
            }
            long recallCost = System.currentTimeMillis() - recallStart;
            logger.ok(stage, "召回到 %d 条候选，耗时 %dms".formatted(recalled.size(), recallCost),
                    previewRecall(recalled), recallCost);

            // ================= 2. 重排打分 =================
            stage = Stage.RERANK_SCORE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<String> docs = recalled.stream().map(RecallItem::content).toList();
            logger.info(stage, "模型 %s，逐条给 (%s, doc) 对打相关性分"
                    .formatted(rerankClient.modelName(), "query"));
            logger.info(stage, "注意：交叉编码器把 query 和 doc 拼在一起过模型，能看到词与词的交互 —— 这是向量做不到的");

            long scoreStart = System.currentTimeMillis();
            RerankClient.Result rr = rerankClient.rerank(query, docs);
            long scoreCost = System.currentTimeMillis() - scoreStart;

            // 按 index 对齐：rerank 返回不保证顺序，也不保证全量
            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (RerankClient.Score s : rr.scores()) {
                scoreByIndex.put(s.index(), s.relevance());
            }
            double minScore = Double.MAX_VALUE;
            double maxScore = -Double.MAX_VALUE;
            double sumScore = 0;
            for (int i = 0; i < recalled.size(); i++) {
                double v = scoreByIndex.getOrDefault(i, 0.0);
                minScore = Math.min(minScore, v);
                maxScore = Math.max(maxScore, v);
                sumScore += v;
            }
            if (recalled.isEmpty()) {
                minScore = 0;
                maxScore = 0;
            }
            logger.ok(stage, "打分完成：%d 条，耗时 %dms，%d tokens"
                            .formatted(rr.scores().size(), scoreCost, rr.tokens()),
                    ("分数分布：min %.4f / max %.4f / mean %.4f\n\n" +
                            "分数区间参考：\n" +
                            "  > 0.5  高度相关（可以直接作为答案依据）\n" +
                            "  0.2~0.5 主题相关，但可能答非所问\n" +
                            "  < 0.1  基本无关\n\n" +
                            "注意：rerank 分和向量相似度不可比，只用于本批次内部排序。")
                            .formatted(minScore, maxScore, sumScore / Math.max(1, recalled.size())),
                    scoreCost);

            // ================= 3. 按分重排 =================
            stage = Stage.RERANK_REORDER;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            List<RerankResult.Hit> all = new ArrayList<>(recalled.size());
            for (int i = 0; i < recalled.size(); i++) {
                RecallItem c = recalled.get(i);
                double score = scoreByIndex.getOrDefault(i, 0.0);
                all.add(new RerankResult.Hit(0, c.recallRank(), 0, round4(score), c.rrfScore(),
                        c.vecRank(), c.bm25Rank(), c.chromaId(), c.docId(), c.docName(),
                        c.chunkIndex(), c.heading(), c.content(), c.charCount()));
            }
            all.sort(Comparator.comparingDouble(RerankResult.Hit::rerankScore).reversed());

            List<RerankResult.Hit> ordered = new ArrayList<>(all.size());
            for (int i = 0; i < all.size(); i++) {
                RerankResult.Hit h = all.get(i);
                int finalRank = i + 1;
                ordered.add(new RerankResult.Hit(finalRank, h.recallRank(),
                        h.recallRank() - finalRank, h.rerankScore(), h.rrfScore(),
                        h.vecRank(), h.bm25Rank(), h.chromaId(), h.docId(), h.docName(),
                        h.chunkIndex(), h.heading(), h.content(), h.charCount()));
            }

            // 逐条打印排名变化 —— 这是阶段四最有信息量的部分
            int up = 0;
            int down = 0;
            for (RerankResult.Hit h : ordered) {
                if (h.rankDelta() > 0) {
                    up++;
                } else if (h.rankDelta() < 0) {
                    down++;
                }
                if (h.finalRank() <= keepK || h.recallRank() <= keepK) {
                    String arrow = h.rankDelta() > 0 ? "↑" + h.rankDelta()
                            : h.rankDelta() < 0 ? "↓" + (-h.rankDelta()) : "—";
                    logger.info(stage, "#%-2d rerank %.4f  %-4s 召回#%d  %s  %s".formatted(
                            h.finalRank(), h.rerankScore(), arrow, h.recallRank(),
                            abbreviate(h.heading().isBlank() ? "（无标题）" : h.heading(), 20),
                            abbreviate(h.content(), 40)),
                            ("召回时排名 : #%d（RRF %.6f，向量%s / BM25%s）\n" +
                                    "重排后排名 : #%d（rerank %.4f）\n" +
                                    "排名变化   : %s")
                                    .formatted(h.recallRank(), h.rrfScore(),
                                            h.vecRank() > 0 ? "#" + h.vecRank() : "未召回",
                                            h.bm25Rank() > 0 ? "#" + h.bm25Rank() : "未召回",
                                            h.finalRank(), h.rerankScore(),
                                            h.rankDelta() > 0 ? "上升 " + h.rankDelta() + " 位（重排模型认为它更相关）"
                                                    : h.rankDelta() < 0 ? "下降 " + (-h.rankDelta()) + " 位（重排模型认为它没那么相关）"
                                                    : "不变"));
                }
            }
            logger.ok(stage, "重排完成：上升 %d 条，下降 %d 条".formatted(up, down),
                    System.currentTimeMillis() - stepStart);

            // ================= 4. 输出结果 =================
            stage = Stage.RERANK_RESULT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<RerankResult.Hit> selected = ordered.stream().limit(keepK).toList();
            List<RerankResult.Hit> dropped = ordered.stream()
                    .filter(h -> h.recallRank() <= keepK && h.finalRank() > keepK)
                    .toList();

            logger.ok(stage, "选中 Top-%d，被挤出 %d 条".formatted(selected.size(), dropped.size()),
                    droppedDetail(dropped),
                    System.currentTimeMillis() - stepStart);

            // ================= 5. 完成 =================
            stage = Stage.RERANK_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            RerankResult result = new RerankResult(selected, dropped, recalled.size(), rr.scores().size());
            traceDao.updateResult(traceId, selected.size(), objectMapper.writeValueAsString(result));
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);

            String top1 = selected.isEmpty() ? "—"
                    : "rerank %.4f（召回时第 %d 名）".formatted(selected.get(0).rerankScore(),
                    selected.get(0).recallRank());
            logger.ok(stage, "重排完成：%d 条结果，总耗时 %dms（召回 %dms + 重排 %dms）"
                            .formatted(selected.size(), totalCost, recallCost, scoreCost),
                    "Top1 " + top1 + "\n\n对比阶段三：同样的候选集，RRF 排出来的第 1 名不一定是重排后的第 1 名。",
                    totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("重排失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    /**
     * 混合召回：向量 Top-N + BM25 Top-N → RRF 融合 → 取前 recallN。
     * 和阶段三同一套逻辑，这里内联实现是为了让阶段四的代码能独立阅读。
     */
    private List<RecallItem> recall(String query, int recallN) {
        // 向量召回
        float[] qv = embeddingClient.embedBatch(List.of(query)).vectors().get(0);
        String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
        if (chromaClient.count(collectionId) == 0) {
            return List.of();
        }
        JsonNode vecRes = chromaClient.query(collectionId, qv, recallN);
        JsonNode ids = vecRes.path("ids").path(0);
        JsonNode dists = vecRes.path("distances").path(0);
        JsonNode docs = vecRes.path("documents").path(0);
        JsonNode metas = vecRes.path("metadatas").path(0);

        Map<String, double[]> fuse = new LinkedHashMap<>();   // chromaId → [rrf, vecRank, vecScore, bm25Rank, bm25Score]
        Map<String, RecallItem> metaPool = new HashMap<>();
        int rrfK = 60;

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).asText();
            JsonNode meta = metas.get(i);
            double sim = 1 - dists.get(i).asDouble();
            fuse.computeIfAbsent(id, k -> new double[]{0, -1, 0, -1, 0});
            double[] v = fuse.get(id);
            v[0] += 1.0 / (rrfK + i + 1);
            v[1] = i + 1;
            v[2] = sim;
            metaPool.putIfAbsent(id, new RecallItem(0, id, 0,
                    i + 1, sim, -1, 0,
                    parseLong(meta, "docId"), textOf(meta, "docName"),
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    textOf(meta, "heading"), docs.get(i).asText(""),
                    meta == null ? 0 : meta.path("charCount").asInt(0)));
        }

        // BM25 召回
        Bm25Index.Freshness fresh = bm25Index.ensureFresh();
        List<Bm25Index.Hit> bm25 = bm25Index.search(fresh.snapshot(), query, recallN);
        for (int i = 0; i < bm25.size(); i++) {
            Bm25Index.DocMeta d = fresh.snapshot().docs().get(bm25.get(i).chunkId());
            if (d == null) {
                continue;
            }
            fuse.computeIfAbsent(d.chromaId(), k -> new double[]{0, -1, 0, -1, 0});
            double[] v = fuse.get(d.chromaId());
            v[0] += 1.0 / (rrfK + i + 1);
            v[3] = i + 1;
            v[4] = bm25.get(i).score();
            metaPool.putIfAbsent(d.chromaId(), new RecallItem(0, d.chromaId(), 0,
                    -1, 0, i + 1, bm25.get(i).score(),
                    d.docId(), d.docName(), d.chunkIndex(), d.heading(), d.content(), d.charCount()));
        }

        List<Map.Entry<String, double[]>> ranked = fuse.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(recallN)
                .toList();

        List<RecallItem> out = new ArrayList<>(ranked.size());
        int rank = 1;
        for (Map.Entry<String, double[]> e : ranked) {
            double[] v = e.getValue();
            RecallItem m = metaPool.get(e.getKey());
            if (m == null) {
                continue;
            }
            out.add(new RecallItem(rank++, e.getKey(), round6(v[0]),
                    (int) v[1], round4(v[2]), (int) v[3], round4(v[4]),
                    m.docId(), m.docName(), m.chunkIndex(), m.heading(), m.content(), m.charCount()));
        }
        return out;
    }

    private String previewRecall(List<RecallItem> list) {
        StringBuilder sb = new StringBuilder("召回前 5 条（这就是重排的输入，注意此时它还是按 RRF 排的）：\n");
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            RecallItem c = list.get(i);
            sb.append("#").append(c.recallRank())
                    .append("  RRF ").append(String.format("%.6f", c.rrfScore()))
                    .append("  ·  ").append(c.heading().isBlank() ? "（无标题）" : c.heading())
                    .append("  ·  ").append(abbreviate(c.content(), 46))
                    .append("\n");
        }
        return sb.toString();
    }

    private String droppedDetail(List<RerankResult.Hit> dropped) {
        if (dropped.isEmpty()) {
            return "没有候选被挤出 —— 说明重排对 Top-K 的成员没有改变，只是顺序变了。";
        }
        StringBuilder sb = new StringBuilder("被挤出 Top-K 的（召回时它们在，重排后掉出去了）：\n");
        for (RerankResult.Hit h : dropped) {
            sb.append("召回#").append(h.recallRank())
                    .append(" → 重排#").append(h.finalRank())
                    .append("（rerank ").append(String.format("%.4f", h.rerankScore())).append("）")
                    .append("  ").append(abbreviate(h.content(), 50))
                    .append("\n");
        }
        sb.append("\n这就是重排的价值：只看 RRF 排名的话，这些内容会被当成「最相关」喂给大模型。");
        return sb.toString();
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case RERANK_RECALL -> "Qwen/Chroma";
            case RERANK_SCORE -> "DashScope-Rerank";
            default -> "Rerank";
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

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static double round6(double d) {
        return Math.round(d * 1000000.0) / 1000000.0;
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
