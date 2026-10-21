package com.raglab.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.dao.HybridTraceDao;
import com.raglab.retrieval.model.HybridResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 + RRF 融合（阶段三 / 对应文章「三、优化2」）。
 *
 * 为什么要有这一路 BM25？
 *   向量检索算的是「语义相近」，它分不清 Python 3.10 和 3.11 —— 因为这两句话
 *   在语义空间里几乎重合。而 BM25 算的是「这个词出现了没有」，能精确匹配 "3.10"。
 *
 * 为什么用 RRF 而不是加权求和？
 *   余弦相似度 0.62 和 BM25 分数 18.42 是两个不同量纲的东西，直接相加没有意义
 *   （你没法说 0.1 的余弦差等于多少 BM25 分）。RRF 只看**排名**不看分数，
 *   把「第几名」换算成 1/(k+rank)，天然规避了量纲问题。
 *
 *   score(d) = Σ_i 1 / (k + rank_i(d))
 *   k 取 60（原论文推荐值），作用是压平头部差距：第 1 名和第 2 名的贡献不会差一倍。
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private static final int MAX_N = 50;
    private static final int DEFAULT_RRF_K = 60;

    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final ChineseTokenizer tokenizer;
    private final HybridTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;

    public HybridRetriever(QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                           Bm25Index bm25Index, ChineseTokenizer tokenizer,
                           HybridTraceDao traceDao, IngestLogBus logBus, ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.tokenizer = tokenizer;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
    }

    public void run(String traceId, String rawQuery, int vecN, int bm25N, int finalK, int rawRrfK) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.HYBRID_RECEIVE;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 1. 接收 =================
            stage = Stage.HYBRID_RECEIVE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            if (query.length() > 500) {
                throw new IllegalArgumentException("问题过长（" + query.length() + " 字），请精简到 500 字以内");
            }
            int vecTopN = clamp(vecN, 5, MAX_N, 20);
            int bm25TopN = clamp(bm25N, 5, MAX_N, 20);
            int finalTopK = clamp(finalK, 1, 20, 5);
            int rrfK = clamp(rawRrfK, 1, 1000, DEFAULT_RRF_K);

            logger.info(stage, "问题：" + query);
            logger.info(stage, "向量召回 Top-%d + BM25 召回 Top-%d → RRF(k=%d) 融合 → 取 Top-%d"
                    .formatted(vecTopN, bm25TopN, rrfK, finalTopK));
            logger.ok(stage, "参数校验通过", System.currentTimeMillis() - stepStart);

            // ================= 2. 向量召回 =================
            stage = Stage.VEC_RECALL;
            long vecStart = System.currentTimeMillis();
            stepStart = vecStart;
            logger.enter(stage);
            QwenEmbeddingClient.BatchResult br = embeddingClient.embedBatch(List.of(query));
            float[] qv = br.vectors().get(0);
            String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
            int total = chromaClient.count(collectionId);
            if (total == 0) {
                throw new IllegalStateException(
                        "集合 " + chromaClient.collectionName() + " 是空的，先去阶段一入库文档");
            }
            JsonNode vecRes = chromaClient.query(collectionId, qv, vecTopN);
            List<HybridResult.Candidate> vecCandidates = parseVectorCandidates(vecRes);
            long vecCost = System.currentTimeMillis() - vecStart;
            logger.ok(stage, "向量召回 %d 条，耗时 %dms".formatted(vecCandidates.size(), vecCost),
                    previewCandidates(vecCandidates, "相似度"), vecCost);

            // ================= 3. BM25 召回 =================
            stage = Stage.BM25_RECALL;
            long bm25Start = System.currentTimeMillis();
            stepStart = bm25Start;
            logger.enter(stage);
            List<String> terms = tokenizer.distinctTerms(query);
            logger.info(stage, "分词结果（%d 个词元）：%s".formatted(terms.size(), String.join(" ", terms)));
            if (terms.isEmpty()) {
                logger.warn(stage, "分词结果为空，BM25 这一路不会有任何召回", "检查问题里是否有可索引的字符");
            }

            Bm25Index.Freshness fresh = bm25Index.ensureFresh();
            if (fresh.rebuilt()) {
                logger.info(stage, "BM25 索引已重建：%d 篇文档，平均长度 %.1f 词元，耗时 %dms"
                        .formatted(fresh.docCount(), fresh.snapshot().avgdl(), fresh.buildMs()));
            } else {
                logger.info(stage, "BM25 索引已就绪：%d 篇文档（无需重建）".formatted(fresh.docCount()));
            }

            List<String> matched = bm25Index.matchedTerms(fresh.snapshot(), query);
            logger.info(stage, "命中的词元（%d/%d）：%s".formatted(
                    matched.size(), terms.size(),
                    matched.isEmpty() ? "（一个都没命中，全是新词）" : String.join(" ", matched)));

            List<Bm25Index.Hit> rawBm25 = bm25Index.search(fresh.snapshot(), query, bm25TopN);
            List<HybridResult.Candidate> bm25Candidates = toCandidates(rawBm25, fresh.snapshot());
            long bm25Cost = System.currentTimeMillis() - bm25Start;
            logger.ok(stage, "BM25 召回 %d 条，耗时 %dms".formatted(bm25Candidates.size(), bm25Cost),
                    previewCandidates(bm25Candidates, "BM25分"), bm25Cost);

            // ================= 4. RRF 融合 =================
            stage = Stage.RRF_FUSE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "融合公式：score(d) = Σ 1/(k + rank)，k=%d".formatted(rrfK));
            logger.info(stage, "注意：两路分数不可比（余弦 0.62 vs BM25 18.42），所以只看排名不看分数");

            // 先建候选池：同一 chromaId 在两路里指同一条切片
            Map<String, HybridResult.Candidate> pool = new LinkedHashMap<>();
            for (HybridResult.Candidate c : vecCandidates) {
                pool.putIfAbsent(c.chromaId(), c);
            }
            for (HybridResult.Candidate c : bm25Candidates) {
                pool.putIfAbsent(c.chromaId(), c);
            }
            int overlap = countOverlap(vecCandidates, bm25Candidates);
            logger.info(stage, "候选池 %d 条（向量 %d + BM25 %d，重叠 %d 条）"
                    .formatted(pool.size(), vecCandidates.size(), bm25Candidates.size(), overlap));

            Map<String, double[]> rrfScore = new HashMap<>();   // chromaId → [rrf, vecRank, vecScore, bm25Rank, bm25Score]
            for (String id : pool.keySet()) {
                rrfScore.put(id, new double[]{0, -1, 0, -1, 0});
            }
            for (int i = 0; i < vecCandidates.size(); i++) {
                HybridResult.Candidate c = vecCandidates.get(i);
                double[] v = rrfScore.get(c.chromaId());
                v[0] += 1.0 / (rrfK + i + 1);
                v[1] = i + 1;
                v[2] = c.score();
            }
            for (int i = 0; i < bm25Candidates.size(); i++) {
                HybridResult.Candidate c = bm25Candidates.get(i);
                double[] v = rrfScore.get(c.chromaId());
                v[0] += 1.0 / (rrfK + i + 1);
                v[3] = i + 1;
                v[4] = c.score();
            }

            List<Map.Entry<String, double[]>> ranked = rrfScore.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                    .toList();
            logger.ok(stage, "融合完成，共 %d 个候选，Top1 RRF=%.6f"
                            .formatted(ranked.size(), ranked.isEmpty() ? 0 : ranked.get(0).getValue()[0]),
                    System.currentTimeMillis() - stepStart);

            // ================= 5. 融合结果 =================
            stage = Stage.HYBRID_RESULT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<HybridResult.FusedHit> fused = new ArrayList<>();
            int rank = 1;
            for (Map.Entry<String, double[]> e : ranked) {
                if (fused.size() >= finalTopK) {
                    break;
                }
                String chromaId = e.getKey();
                double[] v = e.getValue();
                HybridResult.Candidate c = pool.get(chromaId);
                int vecRank = (int) v[1];
                int bmRank = (int) v[3];
                String source = vecRank > 0 && bmRank > 0 ? "BOTH"
                        : vecRank > 0 ? "VECTOR_ONLY" : "BM25_ONLY";

                HybridResult.FusedHit hit = new HybridResult.FusedHit(
                        rank, chromaId, round6(v[0]),
                        vecRank, round4(v[2]), bmRank, round4(v[4]),
                        source,
                        c.docId(), c.docName(), c.chunkIndex(), c.heading(), c.content(), c.charCount());
                fused.add(hit);

                logger.info(stage, "#%d  RRF=%.6f  ·  向量#%s%s + BM25#%s%s  ·  %s  ·  %s".formatted(
                                rank, v[0],
                                vecRank > 0 ? String.valueOf(vecRank) : "—",
                                vecRank > 0 ? "(%.4f)".formatted(v[2]) : "",
                                bmRank > 0 ? String.valueOf(bmRank) : "—",
                                bmRank > 0 ? "(%.4f)".formatted(v[4]) : "",
                                c.heading().isBlank() ? "（无标题）" : c.heading(),
                                abbreviate(c.content(), 40)),
                        rrfDetail(rrfK, vecRank, v[2], bmRank, v[4], v[0], source));
                rank++;
            }
            logger.ok(stage, "最终 %d 条：双路命中 %d 条，仅向量 %d 条，仅 BM25 %d 条".formatted(
                            fused.size(),
                            fused.stream().filter(f -> "BOTH".equals(f.source())).count(),
                            fused.stream().filter(f -> "VECTOR_ONLY".equals(f.source())).count(),
                            fused.stream().filter(f -> "BM25_ONLY".equals(f.source())).count()),
                    System.currentTimeMillis() - stepStart);

            // 教学提示：如果 Top1 在两路里都排得不靠前，说明这条不是被强力召回的，只是「两路都不太差」
            if (!fused.isEmpty()) {
                HybridResult.FusedHit top = fused.get(0);
                if (top.vecRank() > 3 && top.bm25Rank() > 3) {
                    logger.warn(stage,
                            "Top1 在两路里都不靠前（向量#%d，BM25#%d）→ 说明没有一条被强力召回"
                                    .formatted(top.vecRank(), top.bm25Rank()),
                            """
                                    这是 RRF 的固有特性：它给「两路都出现」的候选加分，
                                    所以偶尔会把两条「中游」的顶上来。
                                    常见原因：
                                      ① 知识库里确实没有确切答案
                                      ② 切片粒度不合适，答案被切碎或和其他内容混在一起
                                      ③ 查询里的关键限定词（如"深度""要求"）在库里是高频词，区分度不足
                                    这正是阶段四「重排序」要解决的问题：用一个真正的相关性模型重新判断。""");
                }
            }

            // ================= 6. 完成 =================
            stage = Stage.HYBRID_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            HybridResult result = new HybridResult(vecCandidates, bm25Candidates, fused);
            traceDao.updateResult(traceId, fused.size(), objectMapper.writeValueAsString(result));
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);
            logger.ok(stage, "混合检索完成：%d 条结果，总耗时 %dms（向量 %dms + BM25 %dms）"
                            .formatted(fused.size(), totalCost, vecCost, bm25Cost),
                    "对比一下阶段二：同一条切片在纯向量里可能排第 5，加了 BM25 之后不一定还是第 5。", totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("混合检索失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    private List<HybridResult.Candidate> parseVectorCandidates(JsonNode res) {
        JsonNode ids = res.path("ids").path(0);
        JsonNode dists = res.path("distances").path(0);
        JsonNode docs = res.path("documents").path(0);
        JsonNode metas = res.path("metadatas").path(0);
        List<HybridResult.Candidate> list = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            JsonNode meta = metas.get(i);
            double distance = dists.get(i).asDouble();
            list.add(new HybridResult.Candidate(
                    i + 1,
                    ids.get(i).asText(),
                    round4(1 - distance),
                    parseLong(meta, "docId"),
                    textOf(meta, "docName"),
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    textOf(meta, "heading"),
                    docs.get(i).asText(""),
                    meta == null ? 0 : meta.path("charCount").asInt(0)
            ));
        }
        return list;
    }

    private List<HybridResult.Candidate> toCandidates(List<Bm25Index.Hit> raw, Bm25Index.Snapshot snapshot) {
        List<HybridResult.Candidate> list = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Bm25Index.Hit h = raw.get(i);
            Bm25Index.DocMeta d = snapshot.docs().get(h.chunkId());
            if (d == null) {
                continue;
            }
            list.add(new HybridResult.Candidate(i + 1, d.chromaId(), h.score(),
                    d.docId(), d.docName(), d.chunkIndex(), d.heading(), d.content(), d.charCount()));
        }
        return list;
    }

    private int countOverlap(List<HybridResult.Candidate> a, List<HybridResult.Candidate> b) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (HybridResult.Candidate c : a) {
            set.add(c.chromaId());
        }
        int n = 0;
        for (HybridResult.Candidate c : b) {
            if (set.contains(c.chromaId())) {
                n++;
            }
        }
        return n;
    }

    private String previewCandidates(List<HybridResult.Candidate> list, String scoreLabel) {
        if (list.isEmpty()) {
            return "（无命中）";
        }
        StringBuilder sb = new StringBuilder("前 5 条：\n");
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            HybridResult.Candidate c = list.get(i);
            sb.append("#").append(i + 1)
                    .append("  ").append(scoreLabel).append(" ").append(c.score())
                    .append("  ·  ").append(c.heading().isBlank() ? "（无标题）" : c.heading())
                    .append("  ·  #").append(c.chunkIndex())
                    .append("  ·  ").append(abbreviate(c.content(), 50))
                    .append("\n");
        }
        if (list.size() > 5) {
            sb.append("…… 其余 ").append(list.size() - 5).append(" 条省略");
        }
        return sb.toString();
    }

    private String rrfDetail(int k, int vecRank, double vecScore, int bmRank, double bmScore,
                             double rrf, String source) {
        StringBuilder sb = new StringBuilder("RRF 计算过程：\n");
        if (vecRank > 0) {
            sb.append("  向量路 rank=%d → 1/(%d+%d) = %.6f   （相似度 %.4f）\n"
                    .formatted(vecRank, k, vecRank, 1.0 / (k + vecRank), vecScore));
        } else {
            sb.append("  向量路 未召回\n");
        }
        if (bmRank > 0) {
            sb.append("  BM25路 rank=%d → 1/(%d+%d) = %.6f   （BM25 %.4f）\n"
                    .formatted(bmRank, k, bmRank, 1.0 / (k + bmRank), bmScore));
        } else {
            sb.append("  BM25路 未召回\n");
        }
        sb.append("  ─────────────────────\n");
        sb.append("  合计 RRF = %.6f\n\n".formatted(rrf));
        sb.append(switch (source) {
            case "BOTH" -> "两路都召回 → 这是最可靠的候选：语义相关 + 字面匹配";
            case "VECTOR_ONLY" -> "只有向量召回 → 语义相关，但字面可能对不上（改写、同义词）";
            default -> "只有 BM25 召回 → 字面精确匹配，语义模型没抓到（数字、版本号、专有名词常见）";
        });
        return sb.toString();
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case VEC_RECALL -> "Qwen";
            case BM25_RECALL -> "BM25";
            default -> "Hybrid";
        };
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v <= 0) {
            return def;
        }
        return Math.max(min, Math.min(max, v));
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static double round6(double d) {
        return Math.round(d * 1000000.0) / 1000000.0;
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
