package com.raglab.retrieval.service;

import com.raglab.ingest.service.QwenEmbeddingClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 句子打分（阶段五核心）。
 *
 * 两种策略，对应文章里推荐的方案 1 和方案 2：
 *
 *   VECTOR  句子级向量检索 —— 把每句单独向量化，和问题算余弦相似度。
 *           准，但每 10 句要多一次 embedding 请求（50 句 = 5 次）。
 *
 *   KEYWORD 关键词密度 —— 用分词结果算「查询词在这句里出现了多少」。
 *           不花钱、毫秒级，但分不清同义词和语序。
 *
 * 两条都能用，页面可以切着对比 —— 这本身就是一个很好的观察点：
 * 便宜的方案到底差多少？
 */
@Component
public class SentenceExtractor {

    public enum Strategy {
        VECTOR, KEYWORD
    }

    /** 源切片（重排后的 Top-K） */
    public record ChunkRef(int rank, String chromaId, long docId, String docName,
                           int chunkIndex, String heading, double rerankScore, String content) {
    }

    /**
     * @param orderInChunk 在源切片内的第几句（用于最后按原文顺序排）
     */
    public record ScoredSentence(
            String text,
            String chromaId,
            long docId,
            String docName,
            int chunkIndex,
            String heading,
            int sourceRank,
            int orderInChunk,
            double score
    ) {
        public ScoredSentence withScore(double s) {
            return new ScoredSentence(text, chromaId, docId, docName, chunkIndex, heading,
                    sourceRank, orderInChunk, s);
        }
    }

    /** 单次 embedding 的批量上限（Qwen 限制） */
    private static final int EMBED_BATCH = 10;
    /** 每个切片最多拆多少句参与打分，防止某个超长切片拖垮整个流程 */
    private static final int MAX_SENTENCES_PER_CHUNK = 20;
    /**
     * VECTOR 策略只对「粗筛后的前 N 句」做 embedding。
     *
     * 为什么？5 片 × 15 句 = 80 句，全做要 8 批请求、十几秒。
     * 先用关键词分粗筛出 30 句（覆盖率达到标准的基本都在里面），
     * 再对这 30 句做向量精排 —— 3 批请求，4 秒内完成。
     *
     * 代价：如果某句关键词完全不沾边但对向量模型很相关，它会漏掉。
     * 实践上这种情况很少，而 10 秒的等待是真实成本。
     */
    private static final int VECTOR_CANDIDATES = 30;

    private final SentenceSplitter splitter;
    private final QwenEmbeddingClient embeddingClient;
    private final ChineseTokenizer tokenizer;

    public SentenceExtractor(SentenceSplitter splitter, QwenEmbeddingClient embeddingClient,
                             ChineseTokenizer tokenizer) {
        this.splitter = splitter;
        this.embeddingClient = embeddingClient;
        this.tokenizer = tokenizer;
    }

    public List<ScoredSentence> extract(String query, float[] queryVector,
                                        List<ChunkRef> chunks, Strategy strategy) {
        List<ScoredSentence> all = new ArrayList<>();
        for (ChunkRef c : chunks) {
            List<String> sentences = splitter.split(c.content());
            int limit = Math.min(sentences.size(), MAX_SENTENCES_PER_CHUNK);
            for (int i = 0; i < limit; i++) {
                all.add(new ScoredSentence(sentences.get(i), c.chromaId(), c.docId(), c.docName(),
                        c.chunkIndex(), c.heading(), c.rank(), i, 0));
            }
        }
        if (all.isEmpty()) {
            return all;
        }
        if (strategy == Strategy.KEYWORD) {
            scoreByKeyword(query, all);
        } else {
            scoreByVector(query, queryVector, all);
        }
        return all;
    }

    /**
     * 句子级向量检索：先关键词粗筛出候选，再对候选逐句 embedding 算余弦。
     *
     * 两段式的思路和整个 RAG 链路是一致的：粗筛负责「不漏」，精排负责「准」。
     * 只不过这里粗筛用的是免费的关键词分，精排才是真花钱的 embedding。
     */
    private void scoreByVector(String query, float[] queryVector, List<ScoredSentence> all) {
        // 1. 粗筛：先用关键词分排序，取前 VECTOR_CANDIDATES 句
        scoreByKeyword(query, all);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> Double.compare(all.get(b).score(), all.get(a).score()));
        List<Integer> candidates = new ArrayList<>(
                idx.subList(0, Math.min(all.size(), VECTOR_CANDIDATES)));

        // 2. 精排：只对候选做 embedding
        for (int from = 0; from < candidates.size(); from += EMBED_BATCH) {
            int to = Math.min(from + EMBED_BATCH, candidates.size());
            List<String> texts = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                texts.add(all.get(candidates.get(i)).text());
            }
            QwenEmbeddingClient.BatchResult br = embeddingClient.embedBatch(texts);
            for (int i = from; i < to; i++) {
                int realIdx = candidates.get(i);
                float[] v = br.vectors().get(i - from);
                double dot = 0;
                int n = Math.min(queryVector.length, v.length);
                for (int k = 0; k < n; k++) {
                    dot += (double) queryVector[k] * v[k];
                }
                all.set(realIdx, all.get(realIdx).withScore(Math.round(dot * 10000.0) / 10000.0));
            }
        }

        // 3. 没进候选的句子分数归零，避免它们靠关键词分混进来
        java.util.Set<Integer> candSet = new HashSet<>(candidates);
        for (int i = 0; i < all.size(); i++) {
            if (!candSet.contains(i)) {
                all.set(i, all.get(i).withScore(0));
            }
        }
    }

    /**
     * 关键词密度：覆盖率（查询词命中比例）为主，密度（每 20 字命中几个）为辅。
     *
     * 只用覆盖率会让「短句但包含全部查询词」的句子占优（往往就是标题）；
     * 只用密度会让长句刷分。加权一下比较稳。
     */
    private void scoreByKeyword(String query, List<ScoredSentence> all) {
        List<String> terms = tokenizer.distinctTerms(query);
        if (terms.isEmpty()) {
            return;
        }
        Set<String> termSet = new HashSet<>(terms);
        for (int i = 0; i < all.size(); i++) {
            ScoredSentence s = all.get(i);
            List<String> tokens = tokenizer.tokenize(s.text());
            if (tokens.isEmpty()) {
                continue;
            }
            Set<String> hit = new HashSet<>();
            int hitCount = 0;
            for (String t : tokens) {
                if (termSet.contains(t)) {
                    hit.add(t);
                    hitCount++;
                }
            }
            double coverage = (double) hit.size() / terms.size();
            double density = (double) hitCount / Math.max(1.0, s.text().length() / 20.0);
            double score = coverage * 0.75 + Math.min(1.0, density) * 0.25;
            all.set(i, s.withScore(Math.round(score * 10000.0) / 10000.0));
        }
    }
}
