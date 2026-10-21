package com.raglab.retrieval.service;

import com.raglab.ingest.dao.ChunkDao;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 BM25 索引。
 *
 * 为什么自己写而不用 Lucene？因为只有一两千条切片，Lucene 的倒排索引、段合并、
 * 文件锁这一套全用不上；而自己写能把 BM25 的公式原样摊开 —— 这是学习项目要的东西。
 *
 * 索引结构：
 *   postings: term → [(chunkId, tf), ...]
 *   docs    : chunkId → 切片元信息（含文档长度，BM25 要用来做长度归一化）
 *
 * 只有 status='STORED' 的切片进索引 —— 没写进向量库的切片如果参与 BM25，
 * 两路召回的 id 会对不上，融合就乱套了。
 */
@Component
public class Bm25Index {

    /** BM25 调参：k1 控制词频饱和速度，b 控制文档长度归一化的强度 */
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** 标题重复计入 2 次，等效于给标题加权重（BM25 本身不支持字段权重） */
    private static final int HEADING_BOOST = 2;

    public record DocMeta(long chunkId, long docId, String docName, int chunkIndex,
                          String heading, String content, int charCount, String chromaId,
                          int length) {
    }

    public record Posting(long chunkId, int tf) {
    }

    public record Hit(long chunkId, double score) {
    }

    public record Snapshot(Map<Long, DocMeta> docs,
                           Map<String, List<Posting>> postings,
                           double avgdl,
                           int totalDocs) {
    }

    /** rebuilt=true 时说明这次检索触发了一次全量重建，耗时可观测 */
    public record Freshness(Snapshot snapshot, boolean rebuilt, long buildMs, int docCount) {
    }

    private final ChunkDao chunkDao;
    private final ChineseTokenizer tokenizer;

    private volatile Snapshot snapshot;
    private volatile int indexedCount = -1;

    public Bm25Index(ChunkDao chunkDao, ChineseTokenizer tokenizer) {
        this.chunkDao = chunkDao;
        this.tokenizer = tokenizer;
    }

    /**
     * 确保索引和 DB 一致。用「切片数量」做版本号：
     * 数量没变就不重建（入库新文档会让数量变化，从而触发重建）。
     */
    public Freshness ensureFresh() {
        int current = chunkDao.countStored();
        Snapshot s = snapshot;
        if (s != null && indexedCount == current) {
            return new Freshness(s, false, 0, current);
        }
        synchronized (this) {
            if (snapshot != null && indexedCount == current) {
                return new Freshness(snapshot, false, 0, current);
            }
            long t = System.currentTimeMillis();
            Snapshot built = build();
            snapshot = built;
            indexedCount = current;
            return new Freshness(built, true, System.currentTimeMillis() - t, current);
        }
    }

    private Snapshot build() {
        List<ChunkDao.IndexRow> rows = chunkDao.loadAllForIndex();
        Map<Long, DocMeta> docs = new HashMap<>(rows.size() * 2);
        Map<String, List<Posting>> postings = new HashMap<>(rows.size() * 32);
        long totalLength = 0;

        for (ChunkDao.IndexRow row : rows) {
            List<String> tokens = new ArrayList<>();
            String heading = row.heading() == null ? "" : row.heading();
            // 标题进索引，且重复 HEADING_BOOST 次以提升权重
            for (int i = 0; i < HEADING_BOOST; i++) {
                tokens.addAll(tokenizer.tokenize(heading));
            }
            tokens.addAll(tokenizer.tokenize(row.content()));

            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Posting(row.chunkId(), e.getValue()));
            }

            docs.put(row.chunkId(), new DocMeta(
                    row.chunkId(), row.docId(), row.docName(), row.chunkIndex(),
                    heading, row.content(), row.charCount(), row.chromaId(), tokens.size()));
            totalLength += tokens.size();
        }

        double avgdl = docs.isEmpty() ? 1.0 : (double) totalLength / docs.size();
        return new Snapshot(docs, postings, avgdl, docs.size());
    }

    /**
     * BM25 打分。
     *
     * idf(q) = ln( 1 + (N - df + 0.5) / (df + 0.5) )
     * score  = Σ idf(q) * tf*(k1+1) / ( tf + k1*(1 - b + b*|D|/avgdl) )
     *
     * 直觉：
     *   - df 越小（越罕见的词）→ idf 越大 → 权重越高。所以「3.10」这种词比「模型」值钱。
     *   - tf 越大分数越高，但 (k1+1) 让它饱和，避免一个词刷屏就把整篇拉满。
     *   - |D| 越长，同样出现次数得分越低（b 控制这个惩罚强度）。
     */
    public List<Hit> search(Snapshot s, String query, int topN) {
        if (s == null || s.totalDocs() == 0) {
            return List.of();
        }
        List<String> terms = tokenizer.distinctTerms(query);
        Map<Long, Double> scores = new HashMap<>();

        for (String term : terms) {
            List<Posting> ps = s.postings().get(term);
            if (ps == null || ps.isEmpty()) {
                continue;
            }
            int df = ps.size();
            double idf = Math.log(1 + (s.totalDocs() - df + 0.5) / (df + 0.5));
            for (Posting p : ps) {
                DocMeta d = s.docs().get(p.chunkId());
                if (d == null) {
                    continue;
                }
                double denom = p.tf() + K1 * (1 - B + B * d.length() / s.avgdl());
                double contribution = idf * (p.tf() * (K1 + 1)) / denom;
                scores.merge(p.chunkId(), contribution, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(e -> new Hit(e.getKey(), Math.round(e.getValue() * 10000.0) / 10000.0))
                .toList();
    }

    /** 查询词在索引里的命中情况（用于日志展示「哪些词真的生效了」） */
    public List<String> matchedTerms(Snapshot s, String query) {
        return tokenizer.distinctTerms(query).stream()
                .filter(t -> s.postings().containsKey(t))
                .toList();
    }
}
