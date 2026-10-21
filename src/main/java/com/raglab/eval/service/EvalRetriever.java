package com.raglab.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.raglab.config.RagLabProperties;
import com.raglab.eval.model.EvalUnit;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.retrieval.service.Bm25Index;
import com.raglab.retrieval.service.ContextBuilder;
import com.raglab.retrieval.service.RerankClient;
import com.raglab.retrieval.service.SentenceExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 评测用检索器（阶段六）。
 *
 * 和阶段二~五的 Retriever 区别在哪？
 *   那边每次检索都写轨迹表、推 SSE 日志 —— 那是为了「看清楚一次检索」。
 *   这里一次要跑几十次，不写轨迹、不推日志，只返回「排好序的结果」，
 *   由 EvalPipeline 统一汇总。速度优先。
 *
 * 四种策略，同一个问题各跑一遍：
 *   VECTOR   纯向量               —— 阶段二的 baseline
 *   HYBRID   向量 + BM25 + RRF    —— 阶段三
 *   RERANK   混合召回 + 交叉编码器 —— 阶段四
 *   CONTEXT  重排 + 句子级压缩     —— 阶段五（单元是句子，不是切片）
 *
 * 只有「同一个问题、同一个 Top-K」下比较，指标才有意义。
 */
@Component
public class EvalRetriever {

    public enum Strategy {
        VECTOR, HYBRID, RERANK, CONTEXT
    }

    private static final int RRF_K = 60;
    /** CONTEXT 策略最多用几片作为句子来源，和阶段五页面默认值保持一致 */
    private static final int CONTEXT_SOURCE_CHUNKS = 5;

    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final RerankClient rerankClient;
    private final SentenceExtractor sentenceExtractor;
    private final ContextBuilder contextBuilder;
    private final RagLabProperties props;

    public EvalRetriever(ChromaClient chromaClient, Bm25Index bm25Index,
                         RerankClient rerankClient, SentenceExtractor sentenceExtractor,
                         ContextBuilder contextBuilder, RagLabProperties props) {
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.rerankClient = rerankClient;
        this.sentenceExtractor = sentenceExtractor;
        this.contextBuilder = contextBuilder;
        this.props = props;
    }

    /** 内部候选，字段可变（要分阶段往里填向量排名/BM25 排名/重排分） */
    private static final class Cand {
        final String chromaId;
        final long docId;
        final String docName;
        final int chunkIndex;
        final String heading;
        final String content;

        int vecRank = -1;
        double vecScore = 0;
        int bm25Rank = -1;
        double bm25Score = 0;
        double rrf = 0;
        double rerankScore = 0;

        Cand(String chromaId, long docId, String docName, int chunkIndex,
             String heading, String content) {
            this.chromaId = chromaId;
            this.docId = docId;
            this.docName = docName;
            this.chunkIndex = chunkIndex;
            this.heading = heading;
            this.content = content;
        }
    }

    public List<EvalUnit> retrieve(Strategy strategy, String query, float[] queryVector,
                                   int recallTopN, int topK) {
        if (strategy == Strategy.CONTEXT) {
            return retrieveContext(query, queryVector, recallTopN, topK, 0).chunkUnits();
        }
        return switch (strategy) {
            case VECTOR -> toUnits(vectorTop(queryVector, topK), "CHUNK", c -> c.vecScore);
            case HYBRID -> toUnits(hybridRecall(query, queryVector, recallTopN, topK), "CHUNK", c -> c.rrf);
            case RERANK -> {
                List<Cand> cands = hybridRecall(query, queryVector, recallTopN, recallTopN);
                rerank(query, cands);
                yield toUnits(top(cands, topK), "CHUNK", c -> c.rerankScore);
            }
            default -> List.of();
        };
    }

    /**
     * 压缩阶段的结果：既给出「切片级排名」，也给出「真正拼出来的上下文」。
     *
     * 为什么要给切片级排名？
     *   因为只有切片级才能和 VECTOR / HYBRID / RERANK 直接比 —— 单元一致，指标才可比。
     *   一开始我返回的是句子，结果 CONTEXT 的 Hit@1 只有 16.7%，看着像实现错了。
     *   其实是因为「一组关键词很难同时出现在同一句话里」：句子是更细的单元，
     *   拿它去和整片比命中，本身就是错的口径。
     *   排序方式：一片的分数 = 这片里最相关那句的分数（证据在哪句，这片就值多少）。
     *
     * 为什么还要给上下文？
     *   因为压缩阶段真正要回答的是「砍掉 70% 文字之后，证据还在不在」。
     *   这个只能对着最终上下文算覆盖率，不能靠排名。
     */
    public record ContextOutcome(String context, List<EvalUnit> chunkUnits,
                                 int sourceChars, int contextChars, int sentences) {
    }

    public ContextOutcome retrieveContext(String query, float[] queryVector, int recallTopN,
                                          int topK, int maxChars) {
        List<Cand> cands = hybridRecall(query, queryVector, recallTopN, recallTopN);
        rerank(query, cands);
        List<Cand> sources = top(cands, Math.min(topK, CONTEXT_SOURCE_CHUNKS));

        List<SentenceExtractor.ChunkRef> refs = new ArrayList<>(sources.size());
        int rank = 1;
        for (Cand c : sources) {
            refs.add(new SentenceExtractor.ChunkRef(rank++, c.chromaId, c.docId, c.docName,
                    c.chunkIndex, c.heading, c.rerankScore, c.content));
        }
        List<SentenceExtractor.ScoredSentence> scored = sentenceExtractor.extract(
                query, queryVector, refs, SentenceExtractor.Strategy.VECTOR);
        if (scored.isEmpty()) {
            return new ContextOutcome("", List.of(), 0, 0, 0);
        }

        // 1) 切片级排名：每片取「片内最相关那句」的分数
        Map<String, Double> bestByChunk = new HashMap<>();
        for (SentenceExtractor.ScoredSentence s : scored) {
            bestByChunk.merge(s.chromaId(), s.score(), Math::max);
        }
        List<Cand> bySentence = new ArrayList<>(sources);
        bySentence.sort(Comparator.comparingDouble(
                (Cand c) -> bestByChunk.getOrDefault(c.chromaId, 0.0)).reversed());

        List<EvalUnit> chunkUnits = new ArrayList<>(bySentence.size());
        int r = 1;
        for (Cand c : bySentence) {
            chunkUnits.add(new EvalUnit(r++, "CHUNK", c.content, c.docId, c.docName, c.chunkIndex,
                    c.heading, round4(bestByChunk.getOrDefault(c.chromaId, 0.0))));
        }

        // 2) 真正的上下文：同一套去重 + 限额选句
        ContextBuilder.Config cfg = new ContextBuilder.Config(
                props.context().maxSentencesPerChunk(),
                maxChars > 0 ? maxChars : props.context().maxChars(),
                props.context().dedupThreshold());
        ContextBuilder.Outcome outcome = contextBuilder.build(scored, cfg);
        StringBuilder sb = new StringBuilder();
        for (SentenceExtractor.ScoredSentence s : outcome.selected()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s.text());
        }
        int sourceChars = sources.stream().mapToInt(c -> c.content.length()).sum();
        return new ContextOutcome(sb.toString(), chunkUnits, sourceChars, sb.length(), scored.size());
    }

    // ------------------------------------------------------------------
    // 三种召回
    // ------------------------------------------------------------------

    private List<Cand> vectorTop(float[] queryVector, int n) {
        String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
        if (chromaClient.count(collectionId) == 0) {
            return List.of();
        }
        JsonNode res = chromaClient.query(collectionId, queryVector, n);
        JsonNode ids = res.path("ids").path(0);
        JsonNode dists = res.path("distances").path(0);
        JsonNode docs = res.path("documents").path(0);
        JsonNode metas = res.path("metadatas").path(0);

        List<Cand> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            JsonNode meta = metas.get(i);
            Cand c = new Cand(ids.get(i).asText(), parseLong(meta, "docId"), textOf(meta, "docName"),
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    textOf(meta, "heading"), docs.get(i).asText(""));
            c.vecRank = i + 1;
            c.vecScore = round4(1 - dists.get(i).asDouble());
            out.add(c);
        }
        return out;
    }

    /** 向量 + BM25 → RRF 融合。和阶段三同一套逻辑，只是不写日志。 */
    private List<Cand> hybridRecall(String query, float[] queryVector, int recallN, int keep) {
        Map<String, Cand> pool = new LinkedHashMap<>();
        Map<String, Double> rrf = new HashMap<>();

        for (Cand c : vectorTop(queryVector, recallN)) {
            pool.putIfAbsent(c.chromaId, c);
            Cand p = pool.get(c.chromaId);
            p.vecRank = c.vecRank;
            p.vecScore = c.vecScore;
            rrf.merge(c.chromaId, 1.0 / (RRF_K + c.vecRank), Double::sum);
        }

        Bm25Index.Freshness fresh = bm25Index.ensureFresh();
        List<Bm25Index.Hit> hits = bm25Index.search(fresh.snapshot(), query, recallN);
        for (int i = 0; i < hits.size(); i++) {
            Bm25Index.DocMeta d = fresh.snapshot().docs().get(hits.get(i).chunkId());
            if (d == null) {
                continue;
            }
            pool.computeIfAbsent(d.chromaId(), k -> new Cand(d.chromaId(), d.docId(), d.docName(),
                    d.chunkIndex(), d.heading(), d.content()));
            Cand p = pool.get(d.chromaId());
            p.bm25Rank = i + 1;
            p.bm25Score = round4(hits.get(i).score());
            rrf.merge(d.chromaId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        List<Cand> out = new ArrayList<>(pool.values());
        for (Cand c : out) {
            c.rrf = rrf.getOrDefault(c.chromaId, 0.0);
        }
        out.sort(Comparator.comparingDouble((Cand c) -> c.rrf).reversed());
        return out.size() <= keep ? out : new ArrayList<>(out.subList(0, keep));
    }

    private void rerank(String query, List<Cand> cands) {
        if (cands.isEmpty()) {
            return;
        }
        List<String> texts = cands.stream().map(c -> c.content).toList();
        RerankClient.Result rr = rerankClient.rerank(query, texts);
        Map<Integer, Double> byIndex = new HashMap<>();
        for (RerankClient.Score s : rr.scores()) {
            byIndex.put(s.index(), s.relevance());
        }
        for (int i = 0; i < cands.size(); i++) {
            cands.get(i).rerankScore = byIndex.getOrDefault(i, 0.0);
        }
        cands.sort(Comparator.comparingDouble((Cand c) -> c.rerankScore).reversed());
    }

    private List<Cand> top(List<Cand> cands, int k) {
        return cands.size() <= k ? cands : new ArrayList<>(cands.subList(0, Math.max(0, k)));
    }

    // ------------------------------------------------------------------
    // 组装成统一的「单元」
    // ------------------------------------------------------------------

    private List<EvalUnit> toUnits(List<Cand> cands, String kind, ToDoubleFunction<Cand> scoreFn) {
        List<EvalUnit> out = new ArrayList<>(cands.size());
        int rank = 1;
        for (Cand c : cands) {
            out.add(new EvalUnit(rank++, kind, c.content, c.docId, c.docName, c.chunkIndex,
                    c.heading, round4(scoreFn.applyAsDouble(c))));
        }
        return out;
    }

    // ------------------------------------------------------------------

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
}
