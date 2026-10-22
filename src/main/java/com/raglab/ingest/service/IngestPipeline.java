package com.raglab.ingest.service;

import com.raglab.cache.CacheService;
import com.raglab.common.Stage;
import com.raglab.config.RagLabProperties;
import com.raglab.ingest.dao.ChunkDao;
import com.raglab.ingest.dao.DocumentDao;
import com.raglab.ingest.dao.TaskDao;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.model.ChunkDraft;
import com.raglab.ingest.model.DocumentRow;
import com.raglab.ingest.model.IngestJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 入库流水线：文档 → 清洗 → 切片 → 向量化 → 写入 Chroma。
 *
 * 设计上刻意把「每一步」摊平写出来，而不是抽成一堆抽象方法：
 * 因为这是学习项目，代码本身就要能当流程说明书看。
 * 每个步骤都是 enter → 干活 → ok/fail 三段，日志、耗时、错误诊断都由 TaskLogger 统一产出。
 */
@Service
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final DocumentParser parser;
    private final TextCleaner cleaner;
    private final SmartTextSplitter splitter;
    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final TaskDao taskDao;
    private final IngestLogBus bus;
    private final RagLabProperties props;
    private final CacheService cache;

    public IngestPipeline(DocumentParser parser, TextCleaner cleaner, SmartTextSplitter splitter,
                          QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                          DocumentDao documentDao, ChunkDao chunkDao, TaskDao taskDao,
                          IngestLogBus bus, RagLabProperties props, CacheService cache) {
        this.parser = parser;
        this.cleaner = cleaner;
        this.splitter = splitter;
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.taskDao = taskDao;
        this.bus = bus;
        this.props = props;
        this.cache = cache;
    }

    public void run(IngestJob job) {
        String taskId = job.taskId();
        TaskLogger logger = new TaskLogger(taskId, bus, taskDao);
        long taskStart = System.currentTimeMillis();

        Stage stage = Stage.RECEIVE;
        String target = "Ingest";
        long stepStart = taskStart;
        Long docId = null;
        int storedTotal = 0;
        boolean success = false;

        try {
            // ================= 1. 接收 =================
            stage = Stage.RECEIVE;
            target = "Ingest";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            int rawSize = job.rawSize();
            if (rawSize <= 0) {
                throw new IllegalArgumentException("输入内容为空（0 字节），没有可入库的内容");
            }
            logger.info(stage, ("来源=%s，文档名=%s，原始大小=%s")
                    .formatted("FILE".equals(job.sourceType()) ? "文件上传" : "文本粘贴",
                            job.docName(), humanSize(rawSize)));
            logger.ok(stage, "输入校验通过", System.currentTimeMillis() - stepStart);

            // ================= 2. 内容指纹 =================
            stage = Stage.HASH;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            String rawTextForHash = job.text() != null ? job.text()
                    : new String(job.bytes(), StandardCharsets.UTF_8);
            String hash = sha256(rawTextForHash);
            logger.ok(stage, "SHA-256 = " + hash.substring(0, 16) + "…（全长 " + hash.length() + " 位）",
                    System.currentTimeMillis() - stepStart);

            // ================= 3. 落库元数据 =================
            stage = Stage.SAVE_DOC;
            target = "MySQL";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            docId = documentDao.insert(job.docName(), job.sourceType(), null, rawSize, hash);
            taskDao.bindDoc(taskId, docId);
            logger.ok(stage, "kb_document 插入成功，id=" + docId + "，初始状态 PENDING",
                    System.currentTimeMillis() - stepStart);

            Optional<DocumentRow> dup = documentDao.findDoneByHash(hash);
            if (dup.isPresent()) {
                logger.warn(stage, "检测到相同内容已入库过（文档 #" + dup.get().id() + "）",
                        "继续执行会产生一份重复向量。做去重的话，这里应该直接秒传返回已存在的文档 id。");
            }

            // ================= 4. 解析 =================
            stage = Stage.PARSE;
            target = "File";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            documentDao.updateStatus(docId, "PARSING", null);
            String rawText;
            if (job.text() != null) {
                rawText = job.text();
                logger.info(stage, "文本直接入库，跳过解析器");
            } else {
                DocumentParser.ParseResult pr = parser.parse(job.fileName(), job.bytes());
                rawText = pr.text();
                logger.info(stage, "解析器=%s，%s".formatted(pr.parser(), pr.note()));
            }
            if (rawText == null || rawText.isBlank()) {
                throw new IllegalStateException("解析结果为空。PDF 若是扫描件（图片），需要先做 OCR 才能抽出文字");
            }
            logger.ok(stage, "解析出 " + fmt(rawText.length()) + " 字符 / " + countLines(rawText) + " 行",
                    System.currentTimeMillis() - stepStart);

            // ================= 5. 清洗 =================
            stage = Stage.CLEAN;
            target = "Ingest";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            CleanResult cleaned = cleaner.clean(rawText);
            documentDao.updateTextStats(docId, cleaned.afterChars());
            logger.ok(stage, ("%s → %s 字符（净减 %s，%.1f%%），折叠空行 %d 行")
                            .formatted(fmt(cleaned.beforeChars()), fmt(cleaned.afterChars()),
                                    fmt(cleaned.removedChars()), cleaned.removedRatio(), cleaned.removedLines()),
                    "清洗内容：统一换行符、删零宽字符、去控制字符、折叠连续空行",
                    System.currentTimeMillis() - stepStart);
            if (cleaned.afterChars() < 50) {
                logger.warn(stage, "清洗后只剩 " + cleaned.afterChars() + " 字符，文档可能几乎是空的", null);
            }
            String text = cleaned.text();

            // ================= 6. 切片 =================
            stage = Stage.SPLIT;
            target = "Ingest";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            documentDao.updateStatus(docId, "SPLITTING", null);
            SmartTextSplitter.Outcome outcome = splitter.split(text, job.chunkSize(), job.chunkOverlap(), null);
            List<SmartTextSplitter.RawChunk> raws = outcome.chunks();
            if (raws.isEmpty()) {
                throw new IllegalStateException("切片结果为空：清洗后的正文里没有可切分的内容");
            }
            List<ChunkDraft> chunks = new ArrayList<>(raws.size());
            for (SmartTextSplitter.RawChunk rc : raws) {
                chunks.add(new ChunkDraft(rc.index(), rc.heading(), rc.content(), rc.content().length(),
                        "d" + docId + "_c" + rc.index()));
            }
            chunkDao.batchInsert(docId, chunks);
            documentDao.updateChunkStats(docId, chunks.size(), 0);
            taskDao.updateTotal(taskId, chunks.size());

            SmartTextSplitter.SplitStats st = outcome.stats();
            int effSize = job.chunkSize() != null && job.chunkSize() > 0 ? job.chunkSize() : props.chunk().size();
            int effOverlap = job.chunkOverlap() != null && job.chunkOverlap() >= 0 ? job.chunkOverlap() : props.chunk().overlap();
            logger.ok(stage, ("切成 %d 片：平均 %d 字，最长 %d / 最短 %d，硬切 %d 次，尾块合并 %d 次（size=%d, overlap=%d）")
                            .formatted(st.total(), st.avgChars(), st.maxChars(), st.minChars(),
                                    st.hardCut(), st.mergedTail(),
                                    effSize, effOverlap),
                    previewDetail(chunks),
                    System.currentTimeMillis() - stepStart);

            // ================= 7. 准备向量集合 =================
            stage = Stage.CHROMA_INIT;
            target = "Chroma";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            documentDao.updateStatus(docId, "EMBEDDING", null);
            logger.info(stage, "GET %s/collections/%s".formatted(chromaClient.baseUrl(), chromaClient.collectionName()));
            ChromaClient.CollectionInfo info = chromaClient.ensureCollection(embeddingClient.dimensions());
            documentDao.updateCollection(docId, chromaClient.collectionName());
            if (info.createdNow()) {
                logger.ok(stage, "集合不存在，已新建：name=%s, id=%s, 距离度量=cosine, 维度=%d"
                                .formatted(info.name(), info.id(), embeddingClient.dimensions()),
                        System.currentTimeMillis() - stepStart);
            } else {
                logger.ok(stage, "集合已存在：name=%s, id=%s, 维度=%s"
                                .formatted(info.name(), info.id(),
                                        info.dimension() == null ? "未声明" : String.valueOf(info.dimension())),
                        System.currentTimeMillis() - stepStart);
            }

            // ================= 8. 向量化 =================
            stage = Stage.EMBED;
            target = "Qwen";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, ("模型=%s，维度=%d，单批 %d 条，共 %d 批（Qwen 单次最多 10 条，超了会被 400 拒绝）")
                    .formatted(embeddingClient.modelName(), embeddingClient.dimensions(),
                            props.embedding().batchSize(),
                            (chunks.size() + props.embedding().batchSize() - 1) / props.embedding().batchSize()));

            List<float[]> vectors = new ArrayList<>(chunks.size());
            int batchSize = Math.min(props.embedding().batchSize(), 10);
            int totalBatches = (chunks.size() + batchSize - 1) / batchSize;
            long totalTokens = 0;
            long embedStart = System.currentTimeMillis();

            for (int b = 0; b < totalBatches; b++) {
                int from = b * batchSize;
                int to = Math.min(from + batchSize, chunks.size());
                List<String> texts = new ArrayList<>(to - from);
                for (int i = from; i < to; i++) {
                    texts.add(chunks.get(i).content());
                }
                long bt = System.currentTimeMillis();
                try {
                    QwenEmbeddingClient.BatchResult r = embeddingClient.embedBatch(texts);
                    vectors.addAll(r.vectors());
                    totalTokens += r.promptTokens();
                    chunkDao.updateStatusByChromaIds(
                            chunks.subList(from, to).stream().map(ChunkDraft::chromaId).toList(), "EMBEDDED");
                    taskDao.updateProgress(taskId, to, 0);
                    logger.ok(stage, ("批次 %d/%d：切片 #%d~#%d 共 %d 条 → %d 维，耗时 %dms，本批 %d tokens（累计 %d）")
                                    .formatted(b + 1, totalBatches, from, to - 1, to - from,
                                            r.vectors().get(0).length, System.currentTimeMillis() - bt,
                                            r.promptTokens(), totalTokens),
                            System.currentTimeMillis() - bt);
                } catch (Exception e) {
                    logger.fail(stage, "批次 %d/%d 失败（切片 #%d~#%d）".formatted(b + 1, totalBatches, from, to - 1),
                            e, "Qwen", System.currentTimeMillis() - bt);
                    throw e;
                }
            }
            logger.ok(stage, ("全部 %d 条向量化完成，共 %d 批，累计 %d tokens，总耗时 %dms")
                            .formatted(chunks.size(), totalBatches, totalTokens, System.currentTimeMillis() - embedStart),
                    System.currentTimeMillis() - embedStart);

            // ================= 9. 写入向量库 =================
            stage = Stage.CHROMA_WRITE;
            target = "Chroma";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            documentDao.updateStatus(docId, "STORING", null);
            int writeBatch = Math.max(1, chromaClient.writeBatchSize());
            int writeBatches = (chunks.size() + writeBatch - 1) / writeBatch;

            for (int b = 0; b < writeBatches; b++) {
                int from = b * writeBatch;
                int to = Math.min(from + writeBatch, chunks.size());
                List<String> ids = new ArrayList<>(to - from);
                List<String> documents = new ArrayList<>(to - from);
                List<Map<String, Object>> metadatas = new ArrayList<>(to - from);
                List<float[]> vecs = new ArrayList<>(to - from);

                for (int i = from; i < to; i++) {
                    ChunkDraft c = chunks.get(i);
                    ids.add(c.chromaId());
                    documents.add(c.content());
                    vecs.add(vectors.get(i));
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("docId", String.valueOf(docId));
                    meta.put("docName", job.docName());
                    meta.put("chunkIndex", c.index());
                    meta.put("charCount", c.charCount());
                    if (c.heading() != null && !c.heading().isBlank()) {
                        meta.put("heading", c.heading());
                    }
                    metadatas.add(meta);
                }

                long bt = System.currentTimeMillis();
                try {
                    chromaClient.upsert(info.id(), ids, vecs, documents, metadatas);
                    chunkDao.updateStatusByChromaIds(ids, "STORED");
                    storedTotal += ids.size();
                    taskDao.updateProgress(taskId, chunks.size(), storedTotal);
                    logger.ok(stage, ("批次 %d/%d：写入 %d 条（#%d~#%d），耗时 %dms")
                                    .formatted(b + 1, writeBatches, ids.size(), from, to - 1,
                                            System.currentTimeMillis() - bt),
                            System.currentTimeMillis() - bt);
                } catch (Exception e) {
                    logger.fail(stage, "批次 %d/%d 写入失败（切片 #%d~#%d）".formatted(b + 1, writeBatches, from, to - 1),
                            e, "Chroma", System.currentTimeMillis() - bt);
                    throw e;
                }
            }
            documentDao.updateChunkStats(docId, chunks.size(), storedTotal);

            // ================= 10. 校验 =================
            stage = Stage.VERIFY;
            target = "Chroma";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            int dbChunks = chunkDao.countByDoc(docId);
            int dbStored = chunkDao.countStoredByDoc(docId);
            int chromaTotal = chromaClient.count(info.id());
            boolean consistent = dbChunks == dbStored && storedTotal == dbChunks;
            String detail = ("MySQL 切片数        : %d\n" +
                    "MySQL 状态=STORED   : %d\n" +
                    "本次写入向量数      : %d\n" +
                    "Chroma 集合总条数   : %d（含历史文档的向量）\n" +
                    "collection          : %s (%s)")
                    .formatted(dbChunks, dbStored, storedTotal, chromaTotal,
                            info.name(), info.id());
            if (consistent) {
                logger.ok(stage, "三方对账一致 ✅ 切片 " + dbChunks + " 条全部落库", detail,
                        System.currentTimeMillis() - stepStart);
            } else {
                logger.warn(stage, "对账不一致：切片 " + dbChunks + " 条，但只有 " + dbStored + " 条标记为 STORED", detail);
            }

            // ================= 11. 完成 =================
            stage = Stage.DONE;
            target = "Ingest";
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            documentDao.updateStatus(docId, "DONE", null);
            long total = System.currentTimeMillis() - taskStart;
            taskDao.finish(taskId, "SUCCESS", null, null, total);
            logger.ok(stage, ("入库完成：文档 #%d，%d 片，%d 条向量，总耗时 %dms（%.1fs）")
                            .formatted(docId, chunks.size(), storedTotal, total, total / 1000.0),
                    total);

            // ================= 12. 让缓存失效 =================
            // 知识库变了，之前基于旧内容生成的答案就必须作废。
            // 不做这一步的后果：用户问同样的问题，会拿到「按旧文档生成的答案」，
            // 而且页面上一切正常 —— 这是缓存最难发现的问题。
            int cleared = cache.clearAll("文档入库成功 #" + docId);
            logger.info(stage, "已清空缓存（%d 条），避免返回旧知识库的答案".formatted(cleared));
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败，流程中止", e, target, stepCost);
            if (docId != null) {
                documentDao.updateStatus(docId, "FAILED", truncate(e.getMessage(), 1900));
            }
            long total = System.currentTimeMillis() - taskStart;
            taskDao.finish(taskId, "FAILED", truncate(e.getMessage(), 1900), stage.name(), total);
            logger.error(stage, "任务失败，已写入 kb_ingest_task（error_stage=" + stage.name() + "）",
                    "整条链路总耗时 " + total + "ms。用 taskId=" + taskId + " 可以回放本次全部日志。", total);
            log.error("入库失败 taskId={} stage={}", taskId, stage.name(), e);
        } finally {
            bus.close(taskId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    private String previewDetail(List<ChunkDraft> chunks) {
        StringBuilder sb = new StringBuilder("前 2 片预览：\n");
        for (int i = 0; i < Math.min(2, chunks.size()); i++) {
            ChunkDraft c = chunks.get(i);
            sb.append("── #").append(c.index())
                    .append(c.heading() == null ? "" : "  [" + c.heading() + "]")
                    .append("  ").append(c.charCount()).append(" 字")
                    .append("  id=").append(c.chromaId())
                    .append(" ──\n")
                    .append(abbreviate(c.content(), 220))
                    .append("\n\n");
        }
        if (chunks.size() > 2) {
            sb.append("…… 其余 ").append(chunks.size() - 2).append(" 片省略");
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\n", " ⏎ ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
