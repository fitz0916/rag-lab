package com.raglab.rewrite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.service.Bm25Index;
import com.raglab.retrieval.service.RerankClient;
import com.raglab.rewrite.model.RewriteHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多查询检索 + 跨变体融合（阶段八的核心）。
 *
 * 分四步，每步都能单独讲清楚：
 *
 *   1. prepare  —— 改写 + **一次批量 embedding**
 *                  4 个查询（原 + 3 变体）只发 1 次 embedding 请求，不是 4 次。
 *                  这是很多人会写错的地方：循环里逐个 embed，平白多花 3 倍调用。
 *   2. search   —— 每个查询各跑一遍混合检索（向量 + BM25 + RRF）
 *   3. fuse     —— **跨变体再融合一次**：score = Σ weight / (k + rank)
 *                  原查询权重更高（默认 2），因为改写可能跑偏；
 *                  被多个变体同时命中的条目会自然浮上来 —— 这是「多路投票」的效果。
 *   4. rerank   —— 可选：用原问题做交叉编码器精排。
 *                  注意这里必须用**原问题**而不是变体去重排：
 *                  用户真正想知道的是原问题，变体只是为了捞得更全。
 *
 * 这个方法不带日志、不写库，纯计算 —— 所以评测（阶段六）能直接复用同一套逻辑，
 * 保证「页面看到的」和「评测量到的」是同一件事。
 */
@Component
public class MultiQueryRetriever {

    private static final int RRF_K = 60;

    private final QueryRewriter rewriter;
    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final RerankClient rerankClient;
    private final RagLabProperties props;

    public MultiQueryRetriever(QueryRewriter rewriter, QwenEmbeddingClient embeddingClient,
                               ChromaClient chromaClient, Bm25Index bm25Index,
                               RerankClient rerankClient, RagLabProperties props) {
        this.rewriter = rewriter;
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.rerankClient = rerankClient;
        this.props = props;
    }

    /** 改写结果 + 所有查询的向量（一次批量调用拿到的） */
    public record Prepared(String original, List<String> queries, List<float[]> vectors,
                           int promptTokens, int completionTokens, long genMs) {
    }

    public record VariantStat(int index, String text, int hits, int newHits) {
    }

    public record SearchOutcome(List<List<RewriteHit>> perQuery, List<VariantStat> stats,
                                int candidatePool) {
    }

    /** 一个候选在融合过程中的累加器 */
    private static final class Merged {
        final RewriteHit hit;
        final Set<Integer> from = new LinkedHashSet<>();
        double score = 0;

        Merged(RewriteHit hit) {
            this.hit = hit;
        }
    }

    // ------------------------------------------------------------------
    // 1. 改写 + 批量 embedding
    // ------------------------------------------------------------------

    public Prepared prepare(String query, int variants) {
        return prepareWith(query, variants, null);
    }

    /**
     * @param originalVector 已经有原问题的向量时传进来，省掉一次重复 embedding
     *                       （评测里四种策略共用一个原问题向量，正是用这个口子）
     */
    public Prepared prepareWith(String query, int variants, float[] originalVector) {
        long t0 = System.currentTimeMillis();
        QueryRewriter.Outcome rw = rewriter.rewrite(query, variants);
        List<String> queries = new ArrayList<>();
        queries.add(query);              // 原查询永远第一，且永远是兜底
        queries.addAll(rw.variants());

        long genMs = System.currentTimeMillis() - t0;
        List<float[]> vectors = new ArrayList<>(queries.size());
        if (originalVector != null) {
            vectors.add(originalVector);
            if (queries.size() > 1) {
                // 只给变体做 embedding，原问题复用外面算好的那份
                vectors.addAll(embeddingClient.embedBatch(queries.subList(1, queries.size())).vectors());
            }
        } else {
            // 关键：所有查询一次批量 embedding（Qwen 单次上限 10 条，这里最多 6 条）
            vectors.addAll(embeddingClient.embedBatch(queries).vectors());
        }
        return new Prepared(query, queries, vectors, rw.promptTokens(), rw.completionTokens(), genMs);
    }

    // ------------------------------------------------------------------
    // 2. 逐查询混合召回
    // ------------------------------------------------------------------

    public SearchOutcome search(Prepared prepared, int recallN) {
        List<List<RewriteHit>> perQuery = new ArrayList<>(prepared.queries().size());
        Set<String> baselineIds = new LinkedHashSet<>();
        List<VariantStat> stats = new ArrayList<>();

        for (int i = 0; i < prepared.queries().size(); i++) {
            String q = prepared.queries().get(i);
            List<RewriteHit> hits = hybridRecall(q, prepared.vectors().get(i), recallN, i);
            perQuery.add(hits);

            if (i == 0) {
                for (RewriteHit h : hits) {
                    baselineIds.add(h.chromaId());
                }
                stats.add(new VariantStat(0, q, hits.size(), 0));
            } else {
                int fresh = 0;
                for (RewriteHit h : hits) {
                    if (!baselineIds.contains(h.chromaId())) {
                        fresh++;
                    }
                }
                stats.add(new VariantStat(i, q, hits.size(), fresh));
            }
        }

        Set<String> pool = new LinkedHashSet<>();
        for (List<RewriteHit> list : perQuery) {
            for (RewriteHit h : list) {
                pool.add(h.chromaId());
            }
        }
        return new SearchOutcome(perQuery, stats, pool.size());
    }

    // ------------------------------------------------------------------
    // 3. 跨变体 RRF 融合
    // ------------------------------------------------------------------

    public List<RewriteHit> fuse(SearchOutcome outcome, int limit) {
        Map<String, Merged> pool = new LinkedHashMap<>();
        int weightOriginal = Math.max(1, props.rewrite().originalWeight());

        for (int i = 0; i < outcome.perQuery().size(); i++) {
            int weight = i == 0 ? weightOriginal : 1;
            List<RewriteHit> list = outcome.perQuery().get(i);
            for (int rank = 0; rank < list.size(); rank++) {
                RewriteHit h = list.get(rank);
                Merged m = pool.computeIfAbsent(h.chromaId(), k -> new Merged(h));
                m.from.add(i);
                m.score += (double) weight / (RRF_K + rank + 1);
            }
        }

        // 原查询单路里的排名，用于「这条原本排第几」的对照
        Map<String, Integer> baselineRank = new HashMap<>();
        if (!outcome.perQuery().isEmpty()) {
            List<RewriteHit> base = outcome.perQuery().get(0);
            for (int i = 0; i < base.size(); i++) {
                baselineRank.put(base.get(i).chromaId(), i + 1);
            }
        }

        List<Merged> sorted = new ArrayList<>(pool.values());
        // 分数相同按 chromaId 排，保证同样输入两次运行结果一致（可复现比「随机公平」重要）
        sorted.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            return c != 0 ? c : a.hit.chromaId().compareTo(b.hit.chromaId());
        });

        List<RewriteHit> out = new ArrayList<>();
        int rank = 1;
        for (Merged m : sorted) {
            if (out.size() >= limit) {
                break;
            }
            // 注意：这里必须用 m.from（累加后的多路来源），不能用 m.hit.from（只有单路）
            // —— 一开始写成 withRank 就是踩了这个坑：分数是 4 路累加的，from 却只显示 [0]，
            //    页面看起来「变体完全没起作用」，实际是显示错了。
            RewriteHit h = m.hit;
            List<Integer> from = new ArrayList<>(m.from);
            out.add(new RewriteHit(rank++, h.chromaId(), h.docId(), h.docName(), h.chunkIndex(),
                    h.heading(), h.content(), h.charCount(), from, from.size(),
                    round6(m.score), 0, baselineRank.getOrDefault(h.chromaId(), 0)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 4. 可选的融合后重排
    // ------------------------------------------------------------------

    public List<RewriteHit> rerank(String originalQuery, List<RewriteHit> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        RerankClient.Result rr = rerankClient.rerank(originalQuery,
                candidates.stream().map(RewriteHit::content).toList());
        Map<Integer, Double> byIndex = new HashMap<>();
        for (RerankClient.Score s : rr.scores()) {
            byIndex.put(s.index(), s.relevance());
        }
        List<RewriteHit> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RewriteHit h = candidates.get(i);
            double score = byIndex.getOrDefault(i, 0.0);
            scored.add(new RewriteHit(h.rank(), h.chromaId(), h.docId(), h.docName(), h.chunkIndex(),
                    h.heading(), h.content(), h.charCount(), h.from(), h.hitCount(),
                    h.fusedScore(), round4(score), h.baselineRank()));
        }
        scored.sort(Comparator.comparingDouble(RewriteHit::rerankScore).reversed());
        List<RewriteHit> out = new ArrayList<>();
        int rank = 1;
        for (RewriteHit h : scored) {
            if (out.size() >= topK) {
                break;
            }
            out.add(h.withRank(rank++, h.fusedScore(), h.rerankScore(), h.baselineRank()));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** 单查询的混合召回：向量 + BM25 + RRF，和阶段三同一套逻辑 */
    private List<RewriteHit> hybridRecall(String query, float[] qv, int n, int queryIndex) {
        String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
        if (chromaClient.count(collectionId) == 0) {
            return List.of();
        }
        JsonNode res = chromaClient.query(collectionId, qv, n);
        JsonNode ids = res.path("ids").path(0);
        JsonNode dists = res.path("distances").path(0);
        JsonNode docs = res.path("documents").path(0);
        JsonNode metas = res.path("metadatas").path(0);

        Map<String, double[]> fuse = new LinkedHashMap<>();
        Map<String, RewriteHit> pool = new HashMap<>();

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).asText();
            JsonNode meta = metas.get(i);
            fuse.computeIfAbsent(id, k -> new double[]{0});
            fuse.get(id)[0] += 1.0 / (RRF_K + i + 1);
            pool.putIfAbsent(id, new RewriteHit(0, id, parseLong(meta, "docId"),
                    textOf(meta, "docName"),
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    textOf(meta, "heading"), docs.get(i).asText(""),
                    meta == null ? 0 : meta.path("charCount").asInt(0),
                    List.of(queryIndex), 1, 0, 0, 0));
        }

        Bm25Index.Freshness fresh = bm25Index.ensureFresh();
        List<Bm25Index.Hit> bm25 = bm25Index.search(fresh.snapshot(), query, n);
        for (int i = 0; i < bm25.size(); i++) {
            Bm25Index.DocMeta d = fresh.snapshot().docs().get(bm25.get(i).chunkId());
            if (d == null) {
                continue;
            }
            fuse.computeIfAbsent(d.chromaId(), k -> new double[]{0});
            fuse.get(d.chromaId())[0] += 1.0 / (RRF_K + i + 1);
            pool.putIfAbsent(d.chromaId(), new RewriteHit(0, d.chromaId(), d.docId(), d.docName(),
                    d.chunkIndex(), d.heading(), d.content(), d.charCount(),
                    List.of(queryIndex), 1, 0, 0, 0));
        }

        List<Map.Entry<String, double[]>> ranked = fuse.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(n)
                .toList();

        List<RewriteHit> out = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            RewriteHit h = pool.get(ranked.get(i).getKey());
            if (h == null) {
                continue;
            }
            out.add(h.withRank(i + 1, round6(ranked.get(i).getValue()[0]), 0, 0));
        }
        return out;
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
        return meta == null || meta.isNull() ? "" : meta.path(key).asText("");
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static double round6(double d) {
        return Math.round(d * 1000000.0) / 1000000.0;
    }
}
