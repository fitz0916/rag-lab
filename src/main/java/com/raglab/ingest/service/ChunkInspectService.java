package com.raglab.ingest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.dao.ChunkDao;
import com.raglab.ingest.dao.DocumentDao;
import com.raglab.ingest.model.DocumentRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片「解剖台」。
 *
 * 入库日志告诉你「切了 89 片、花了 88ms」，但学习真正想知道的是：
 *   - 这一片完整的正文到底是什么
 *   - 它和上一片重复了多少字（overlap 到底怎么起作用的）
 *   - 向量确实存进去了吗？那 1024 个数字长什么样
 *
 * 这个类把这三件事查出来。向量是直接从 Chroma 里捞的，不是本地缓存 ——
 * 所以看到的等于库里真实存的。
 */
@Service
public class ChunkInspectService {

    /**
     * 向量快照。1024 维全返回太占带宽也没人看，所以给统计量 + 头尾片段。
     */
    public record VectorPreview(
            boolean stored,      // Chroma 里是否真的查到了
            int dimensions,
            double norm,         // L2 范数，text-embedding 输出通常接近 1
            double min, double max, double mean,
            List<Double> head,   // 前 16 维
            List<Double> tail,   // 后 4 维
            String note
    ) {
    }

    public record ChunkDetail(
            long docId, String docName,
            int chunkIndex, String heading,
            String content, int charCount, int tokenEst,
            String chromaId, String status, String collection,
            int overlapChars, String overlapText,
            VectorPreview vector
    ) {
    }

    private final ChunkDao chunkDao;
    private final DocumentDao documentDao;
    private final ChromaClient chromaClient;
    private final RagLabProperties props;

    public ChunkInspectService(ChunkDao chunkDao, DocumentDao documentDao,
                               ChromaClient chromaClient, RagLabProperties props) {
        this.chunkDao = chunkDao;
        this.documentDao = documentDao;
        this.chromaClient = chromaClient;
        this.props = props;
    }

    public ChunkDetail inspect(long docId, int index) {
        ChunkDao.ChunkRow cur = chunkDao.findByDocAndIndex(docId, index)
                .orElseThrow(() -> new IllegalArgumentException("切片不存在：docId=" + docId + ", index=" + index));
        DocumentRow doc = documentDao.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + docId));

        // ---- overlap：本片开头有多少字是上一片的尾巴 ----
        int overlapChars = 0;
        String overlapText = "";
        var prevOpt = chunkDao.findByDocAndIndex(docId, index - 1);
        if (prevOpt.isPresent()) {
            int len = commonOverlap(prevOpt.get().content(), cur.content(),
                    Math.max(props.chunk().overlap() * 2, 100));
            if (len > 0) {
                overlapChars = len;
                overlapText = cur.content().substring(0, len);
            }
        }

        return new ChunkDetail(
                docId, doc.docName(), index, cur.heading(),
                cur.content(), cur.charCount(), (int) Math.ceil(cur.charCount() * 1.5),
                cur.chromaId(), cur.status(), doc.collection(),
                overlapChars, overlapText,
                loadVector(doc.collection(), cur.chromaId())
        );
    }

    /**
     * 从 Chroma 取回这条切片真实存储的向量。
     * 取不到（collection 被删、id 不在）不抛异常，而是如实标记 stored=false ——
     * 这本身就是有用的信息：向量丢了。
     */
    private VectorPreview loadVector(String collectionName, String chromaId) {
        int expected = props.embedding().dimensions();
        if (collectionName == null || collectionName.isBlank()) {
            return empty(expected, "文档记录里没有 collection 名，说明还没走到建集合那一步");
        }
        try {
            String collectionId = chromaClient.collectionIdByName(collectionName);
            JsonNode node = chromaClient.getByIds(collectionId, List.of(chromaId),
                    List.of("embeddings", "documents"));
            JsonNode emb = node.path("embeddings");
            if (!emb.isArray() || emb.isEmpty() || emb.get(0) == null || !emb.get(0).isArray()) {
                return empty(expected, "Chroma 里查不到 id=" + chromaId + " 的向量，可能集合被重建过");
            }
            JsonNode arr = emb.get(0);
            int dim = arr.size();
            double[] v = new double[dim];
            double sum = 0, sq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int i = 0; i < dim; i++) {
                double d = arr.get(i).asDouble();
                v[i] = d;
                sum += d;
                sq += d * d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            List<Double> head = new ArrayList<>();
            for (int i = 0; i < Math.min(16, dim); i++) {
                head.add(round(v[i]));
            }
            List<Double> tail = new ArrayList<>();
            for (int i = Math.max(0, dim - 4); i < dim; i++) {
                tail.add(round(v[i]));
            }
            return new VectorPreview(true, dim, round(Math.sqrt(sq)), round(min), round(max),
                    round(sum / dim), head, tail,
                    "从 Chroma 的 " + collectionName + " 集合按 id 读回，不是本地缓存");
        } catch (Exception e) {
            return empty(expected, "读向量失败：" + e.getMessage());
        }
    }

    private VectorPreview empty(int expected, String note) {
        return new VectorPreview(false, expected, 0, 0, 0, 0, List.of(), List.of(), note);
    }

    /**
     * 求 prev 的结尾与 cur 的开头最长重叠长度。
     * 切片算法是 content = 上一片尾巴 + 本片正文，所以只可能出现在开头。
     */
    private int commonOverlap(String prev, String cur, int searchLimit) {
        if (prev == null || cur == null || prev.isEmpty() || cur.isEmpty()) {
            return 0;
        }
        int max = Math.min(Math.min(prev.length(), cur.length()), searchLimit);
        for (int len = max; len >= 20; len--) {
            if (prev.endsWith(cur.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }

    private double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
