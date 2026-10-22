package com.raglab.gen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.cache.CacheService;
import com.raglab.common.Stage;
import com.raglab.config.RagLabProperties;
import com.raglab.gen.dao.GenerateTraceDao;
import com.raglab.gen.model.GenerateResult;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import com.raglab.retrieval.model.ContextResult;
import com.raglab.retrieval.service.Bm25Index;
import com.raglab.retrieval.service.ContextBuilder;
import com.raglab.retrieval.service.RerankClient;
import com.raglab.retrieval.service.SentenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成（阶段七）—— 把前面六个阶段的成果真正用起来。
 *
 * 前面做的是「找资料」，这一步做的是「用资料回答」：
 *   召回 + 重排 + 句子压缩 → 拼 Prompt → 调大模型 → 把 [n] 对回具体句子
 *
 * 三个必须坚持的原则（RAG 与「直接问大模型」的区别全在这）：
 *   1. 只给资料，不给模型的自由发挥空间。所以 system 里写死「资料里没有就说没有」。
 *   2. 每句资料带编号，要求模型引用。能引用，就说明它真的在用资料，不是凭记忆答。
 *   3. 记下真实 token。生成的成本几乎全在 prompt 长度上，而 prompt 长度
 *      直接由阶段五的压缩效果决定 —— 这就是「压缩到底省了多少钱」的答案。
 *
 * 关于代码里又写了一遍「召回+重排+选句」：
 *   和阶段四、五一样，是故意的。每个阶段单独打开一个文件就能从头读到尾，
 *   比抽一层抽象基类更适合学。代价是重复，收益是能读懂。
 */
@Service
public class GenerateRetriever {

    private static final Logger log = LoggerFactory.getLogger(GenerateRetriever.class);
    private static final int MAX_RECALL = 50;
    /** 估算口径和阶段一/五保持一致：字 × 1.5（偏保守，用来控成本是安全的） */
    private static final double TOKEN_PER_CHAR = 1.5;
    /** 引用编号：答案里的 [1] [2] */
    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");

    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final Bm25Index bm25Index;
    private final RerankClient rerankClient;
    private final SentenceExtractor sentenceExtractor;
    private final ContextBuilder contextBuilder;
    private final ChatClient chatClient;
    private final GenerateTraceDao traceDao;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;
    private final CacheService cache;

    public GenerateRetriever(QwenEmbeddingClient embeddingClient, ChromaClient chromaClient,
                             Bm25Index bm25Index, RerankClient rerankClient,
                             SentenceExtractor sentenceExtractor, ContextBuilder contextBuilder,
                             ChatClient chatClient, GenerateTraceDao traceDao,
                             IngestLogBus logBus, ObjectMapper objectMapper,
                             RagLabProperties props, CacheService cache) {
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.bm25Index = bm25Index;
        this.rerankClient = rerankClient;
        this.sentenceExtractor = sentenceExtractor;
        this.contextBuilder = contextBuilder;
        this.chatClient = chatClient;
        this.traceDao = traceDao;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
        this.props = props;
        this.cache = cache;
    }

    private record Src(int rank, String chromaId, long docId, String docName,
                       int chunkIndex, String heading, String content, double rerankScore) {
    }

    public void run(String traceId, String rawQuery, int recallTopN, int topK, int maxChars) {
        TaskLogger logger = new TaskLogger(traceId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.CACHE_LOOKUP;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 0. 查缓存 =================
            stage = Stage.CACHE_LOOKUP;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            String query = rawQuery.strip();
            int recallN = Math.max(5, Math.min(recallTopN, MAX_RECALL));
            int keepK = Math.max(1, Math.min(topK, 10));
            int charLimit = maxChars > 0 ? maxChars : props.context().maxChars();
            CacheService.Params cacheParams = new CacheService.Params(keepK, chatClient.modelName());

            logger.info(stage, "问题：" + query);
            logger.info(stage, "缓存键 = 归一化问题 + Top-K(%d) + 模型(%s)".formatted(keepK, chatClient.modelName()));
            logger.info(stage, "顺序：L1 内存 → L2 Redis → L3 语义（L3 需要先算问题向量，所以放在最后）");

            // 语义层需要问题向量：只在 exact 都没命中时才算，省掉一次 embedding
            CacheService.Lookup look = cache.lookup("gen", query, cacheParams,
                    () -> embeddingClient.embedBatch(List.of(query)).vectors().get(0));

            if (look.hit()) {
                GenerateResult cached = objectMapper.readValue(look.payload(), GenerateResult.class);
                traceDao.markCacheHit(traceId, look.layer(), look.similarity());
                traceDao.updateResult(traceId, cached.sources() == null ? 0 : cached.sources().size(),
                        cached.sentences() == null ? 0 : cached.sentences().size(),
                        cached.stats() == null ? 0 : cached.stats().finalChars(),
                        (cached.systemPrompt() == null ? 0 : cached.systemPrompt().length())
                                + (cached.userPrompt() == null ? 0 : cached.userPrompt().length()),
                        cached.answer() == null ? 0 : cached.answer().length(),
                        cached.promptTokens(), cached.completionTokens(),
                        cached.cited() == null ? 0 : cached.cited().size(),
                        look.payload());
                long totalCost = System.currentTimeMillis() - start;
                traceDao.finish(traceId, "SUCCESS", null, totalCost);

                String sim = look.layer().equals("L3")
                        ? "，相似度 %.4f".formatted(look.similarity()) : "";
                logger.ok(stage, "缓存命中：%s%s，耗时 %dms".formatted(look.layer(), sim, look.costMs()),
                        ("命中的是「%s」这条历史问题的结果。\n\n" +
                                "省掉的东西：问题向量化、混合检索、重排、句子压缩、大模型生成。\n" +
                                "正常情况下这条链路要 4~6 秒，现在 %d 毫秒。\n\n" +
                                "注意：命中缓存意味着**这次没有读知识库**。\n" +
                                "所以入库新文档后必须清缓存，否则会拿到旧答案 —— 这是缓存最隐蔽的坑。")
                                .formatted(query, look.costMs()),
                        look.costMs());
                logger.ok(Stage.GEN_DONE, "完成（缓存命中，未调用大模型）",
                        "本次 prompt/completion token 均为历史值，不代表新开销。",
                        totalCost);
                success = true;
                return;
            }

            logger.ok(stage, "未命中（%s），走完整链路".formatted(look.layer()),
                    ("L1/L2 是精确匹配，都没命中。\n" +
                            "L3 找到的最相似历史问题是 %.4f，阈值 %.2f —— 差 %.4f，所以这次没复用。\n\n" +
                            "阈值调低能提高命中率，但会开始出现「问 A 答 B」：\n" +
                            "这正是缓存最需要评测的地方，不能凭感觉调。")
                            .formatted(look.similarity(), props.cache().semanticThreshold(),
                                    Math.max(0, props.cache().semanticThreshold() - look.similarity())),
                    look.costMs());

            // ================= 1. 准备上下文 =================
            stage = Stage.GEN_CONTEXT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "召回 Top-%d → 重排 Top-%d → 句子压缩（上限 %d 字）"
                    .formatted(recallN, keepK, charLimit));

            long recallStart = System.currentTimeMillis();
            // 语义层可能已经把问题向量算好了，复用，别重复调 embedding
            float[] qv = look.queryVector() != null
                    ? look.queryVector()
                    : embeddingClient.embedBatch(List.of(query)).vectors().get(0);
            List<Src> sources = recallAndRerank(query, qv, recallN, keepK);
            if (sources.isEmpty()) {
                throw new IllegalStateException("召回为空，检查阶段一是否已入库文档");
            }
            long recallCost = System.currentTimeMillis() - recallStart;
            logger.ok(stage, "拿到 %d 片源切片，耗时 %dms".formatted(sources.size(), recallCost),
                    previewChunks(sources), recallCost);

            List<SentenceExtractor.ChunkRef> refs = new ArrayList<>(sources.size());
            int rank = 1;
            for (Src s : sources) {
                refs.add(new SentenceExtractor.ChunkRef(rank++, s.chromaId(), s.docId(), s.docName(),
                        s.chunkIndex(), s.heading(), s.rerankScore(), s.content()));
            }
            List<SentenceExtractor.ScoredSentence> scored =
                    sentenceExtractor.extract(query, qv, refs, SentenceExtractor.Strategy.VECTOR);
            ContextBuilder.Outcome outcome = contextBuilder.build(scored,
                    new ContextBuilder.Config(props.context().maxSentencesPerChunk(), charLimit,
                            props.context().dedupThreshold()));
            if (outcome.selected().isEmpty()) {
                throw new IllegalStateException("压缩后没有任何可用句子，检查切片内容是否为空");
            }

            int originChars = sources.stream().mapToInt(s -> s.content().length()).sum();
            int contextChars = outcome.selected().stream().mapToInt(s -> s.text().length()).sum();

            List<ContextResult.Sentence> sentences = new ArrayList<>();
            int order = 1;
            for (SentenceExtractor.ScoredSentence s : outcome.selected()) {
                sentences.add(new ContextResult.Sentence(order++, s.text(), s.chromaId(), s.docId(),
                        s.docName(), s.chunkIndex(), s.heading(), s.sourceRank(), s.score(),
                        s.text().length()));
            }
            List<ContextResult.SourceChunk> sourceModels = new ArrayList<>();
            Map<String, Integer> perChunk = new HashMap<>();
            Map<String, Integer> selectedPerChunk = new HashMap<>();
            for (SentenceExtractor.ScoredSentence s : scored) {
                perChunk.merge(s.chromaId(), 1, Integer::sum);
            }
            for (ContextResult.Sentence s : sentences) {
                selectedPerChunk.merge(s.chromaId(), 1, Integer::sum);
            }
            for (Src s : sources) {
                sourceModels.add(new ContextResult.SourceChunk(s.rank(), s.chromaId(), s.docId(),
                        s.docName(), s.chunkIndex(), s.heading(), s.rerankScore(), s.content(),
                        s.content().length(), perChunk.getOrDefault(s.chromaId(), 0),
                        selectedPerChunk.getOrDefault(s.chromaId(), 0)));
            }
            ContextResult.Stats stats = new ContextResult.Stats(originChars, contextChars,
                    (int) Math.ceil(originChars * TOKEN_PER_CHAR), (int) Math.ceil(contextChars * TOKEN_PER_CHAR),
                    originChars == 0 ? 0 : Math.round((double) contextChars / originChars * 1000) / 1000.0,
                    scored.size(), sentences.size(),
                    outcome.droppedDuplicates(), outcome.droppedByLimit());

            logger.ok(stage, "上下文就绪：%d 片 %d 字 → %d 句 %d 字（保留 %.1f%%）"
                            .formatted(sources.size(), originChars, sentences.size(), contextChars,
                                    stats.compressRatio() * 100),
                    "去重丢弃 %d 句，超长丢弃 %d 句\n"
                            .formatted(outcome.droppedDuplicates(), outcome.droppedByLimit())
                            + "这一步决定了后面要花多少 token —— 上下文越长，生成越贵、越容易被无关内容带偏。",
                    System.currentTimeMillis() - stepStart);

            // ================= 2. 拼装 Prompt =================
            stage = Stage.GEN_PROMPT;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(query, sentences);
            int promptChars = systemPrompt.length() + userPrompt.length();

            logger.info(stage, "系统提示 %d 字 + 用户消息 %d 字 = %d 字"
                    .formatted(systemPrompt.length(), userPrompt.length(), promptChars));
            logger.ok(stage, "Prompt 拼装完成",
                    "【系统提示】\n" + systemPrompt + "\n\n【用户消息（节选前 900 字）】\n"
                            + truncate(userPrompt, 900),
                    System.currentTimeMillis() - stepStart);

            // ================= 3. 调用大模型 =================
            stage = Stage.GEN_CALL;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "模型 %s，temperature %.2f，max_tokens %d"
                    .formatted(chatClient.modelName(), props.generate().temperature(),
                            props.generate().maxTokens()));

            ChatClient.Result answer = chatClient.chat(systemPrompt, userPrompt);
            long callCost = System.currentTimeMillis() - stepStart;
            logger.ok(stage, "生成完成：%d 字，耗时 %dms，prompt %d tokens / completion %d tokens"
                            .formatted(answer.content().length(), callCost,
                                    answer.usage().promptTokens(), answer.usage().completionTokens()),
                    "finish_reason = %s\n\n答案（前 400 字）：\n%s"
                            .formatted(answer.finishReason(), truncate(answer.content(), 400)),
                    callCost);

            // ================= 4. 标注依据 =================
            stage = Stage.GEN_CITE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            List<Integer> cited = parseCitations(answer.content(), sentences.size());
            if (cited.isEmpty()) {
                logger.warn(stage, "答案里没有任何 [编号] 引用 —— 说明模型没按格式走，或者漏了引用",
                        "这种情况不能当成「答案正确」：它可能是凭记忆编的。\n" +
                                "常见原因：① 提示词里没强调格式 ② 资料太长模型忽略了 ③ 资料确实不含答案。");
            } else {
                StringBuilder detail = new StringBuilder("答案引用到的依据：\n");
                for (int idx : cited) {
                    ContextResult.Sentence s = sentences.get(idx - 1);
                    detail.append("[").append(idx).append("] 《").append(s.docName()).append("》 切片#")
                            .append(s.chunkIndex()).append(" · 分数 ").append(s.score())
                            .append("\n    ").append(abbreviate(s.text(), 90)).append("\n");
                }
                logger.ok(stage, "引用 %d/%d 条依据，覆盖 %d/%d 片源切片"
                                .formatted(cited.size(), sentences.size(),
                                        countDistinctChunks(cited, sentences), sources.size()),
                        detail.toString(), System.currentTimeMillis() - stepStart);
            }

            // ================= 5. 落库 =================
            stage = Stage.GEN_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            GenerateResult result = new GenerateResult(answer.content(), chatClient.modelName(),
                    systemPrompt, userPrompt, sentences, sourceModels, stats, cited,
                    answer.usage().promptTokens(), answer.usage().completionTokens(),
                    answer.finishReason());
            traceDao.updateResult(traceId, sources.size(), sentences.size(), contextChars, promptChars,
                    answer.content().length(), answer.usage().promptTokens(),
                    answer.usage().completionTokens(), cited.size(),
                    objectMapper.writeValueAsString(result));

            // ================= 5. 写缓存 =================
            stage = Stage.CACHE_STORE;
            long storeStart = System.currentTimeMillis();
            logger.enter(stage);
            cache.store("gen", query, cacheParams, qv, objectMapper.writeValueAsString(result));
            logger.ok(stage, "已写入 L1 / L2 / L3（TTL %d 秒）".formatted(props.cache().ttlSeconds()),
                    ("下次问同样的问题（或语义相似度 ≥ %.2f 的问题），会直接返回这份答案。\n" +
                            "入库新文档时会自动清空缓存 —— 否则会拿到旧知识库的答案。")
                            .formatted(props.cache().semanticThreshold()),
                    System.currentTimeMillis() - storeStart);

            stage = Stage.GEN_DONE;
            long totalCost = System.currentTimeMillis() - start;
            traceDao.finish(traceId, "SUCCESS", null, totalCost);

            logger.ok(stage, "完成：总耗时 %dms（检索 %dms + 生成 %dms），答案 %d 字"
                            .formatted(totalCost, recallCost, callCost, answer.content().length()),
                    "提示：如果答案不对，先看「依据」挑得对不对，再看 prompt 里资料够不够。\n" +
                            "绝大多数 RAG 的「答错」其实是「没检到」，不是模型不行。",
                    totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, targetOf(stage), stepCost);
            traceDao.finish(traceId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start);
            log.error("生成失败 traceId={} stage={}", traceId, stage.name(), e);
        } finally {
            logBus.close(traceId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    private String buildSystemPrompt() {
        return """
                你是油气田数字化交付规范与企业级 RAG 技术文档的问答助手。
                必须遵守：
                1. 只依据【参考资料】回答，不使用资料以外的知识，也不要推测。
                2. 每个结论后面用 [编号] 标注依据，编号就是资料里句子的序号；可以一句话标多个，如 [2][5]。
                3. 资料不足以回答时，直接说「资料里没有提到」，并说明缺少什么，绝不编造。
                4. 用简体中文回答，先给结论再给依据，控制在 400 字以内。
                """.strip();
    }

    private String buildUserPrompt(String query, List<ContextResult.Sentence> sentences) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < sentences.size(); i++) {
            ContextResult.Sentence s = sentences.get(i);
            sb.append('[').append(i + 1).append("] 《").append(s.docName()).append("》");
            if (s.heading() != null && !s.heading().isBlank()) {
                sb.append(" · ").append(s.heading());
            }
            sb.append(" · 切片#").append(s.chunkIndex()).append('\n');
            sb.append(s.text()).append("\n\n");
        }
        sb.append("【问题】\n").append(query).append('\n');
        return sb.toString();
    }

    /** 解析 [n]，去掉越界和重复，保持出现顺序 */
    private List<Integer> parseCitations(String answer, int max) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        Matcher m = CITE.matcher(answer == null ? "" : answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= max) {
                set.add(n);
            }
        }
        return new ArrayList<>(set);
    }

    private int countDistinctChunks(List<Integer> cited, List<ContextResult.Sentence> sentences) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (int idx : cited) {
            set.add(sentences.get(idx - 1).chromaId());
        }
        return set.size();
    }

    /** 混合召回 + 重排，和阶段四同一套逻辑 */
    private List<Src> recallAndRerank(String query, float[] qv, int recallN, int keepK) {
        String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
        if (chromaClient.count(collectionId) == 0) {
            return List.of();
        }
        JsonNode vecRes = chromaClient.query(collectionId, qv, recallN);
        JsonNode ids = vecRes.path("ids").path(0);
        JsonNode dists = vecRes.path("distances").path(0);
        JsonNode docs = vecRes.path("documents").path(0);
        JsonNode metas = vecRes.path("metadatas").path(0);

        int rrfK = 60;
        Map<String, double[]> fuse = new LinkedHashMap<>();
        Map<String, Src> pool = new HashMap<>();

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).asText();
            JsonNode meta = metas.get(i);
            fuse.computeIfAbsent(id, k -> new double[]{0});
            fuse.get(id)[0] += 1.0 / (rrfK + i + 1);
            pool.putIfAbsent(id, new Src(0, id, parseLong(meta, "docId"), textOf(meta, "docName"),
                    meta == null ? -1 : meta.path("chunkIndex").asInt(-1),
                    textOf(meta, "heading"), docs.get(i).asText(""), 0));
        }

        Bm25Index.Freshness fresh = bm25Index.ensureFresh();
        List<Bm25Index.Hit> bm25 = bm25Index.search(fresh.snapshot(), query, recallN);
        for (int i = 0; i < bm25.size(); i++) {
            Bm25Index.DocMeta d = fresh.snapshot().docs().get(bm25.get(i).chunkId());
            if (d == null) {
                continue;
            }
            fuse.computeIfAbsent(d.chromaId(), k -> new double[]{0});
            fuse.get(d.chromaId())[0] += 1.0 / (rrfK + i + 1);
            pool.putIfAbsent(d.chromaId(), new Src(0, d.chromaId(), d.docId(), d.docName(),
                    d.chunkIndex(), d.heading(), d.content(), 0));
        }

        List<String> ordered = fuse.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(recallN)
                .map(Map.Entry::getKey)
                .toList();

        List<Src> candidates = new ArrayList<>();
        for (String id : ordered) {
            Src s = pool.get(id);
            if (s != null) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        RerankClient.Result rr = rerankClient.rerank(query,
                candidates.stream().map(Src::content).toList());
        Map<Integer, Double> byIndex = new HashMap<>();
        for (RerankClient.Score s : rr.scores()) {
            byIndex.put(s.index(), s.relevance());
        }
        List<Src> reordered = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Src c = candidates.get(i);
            reordered.add(new Src(0, c.chromaId(), c.docId(), c.docName(), c.chunkIndex(),
                    c.heading(), c.content(), byIndex.getOrDefault(i, 0.0)));
        }
        reordered.sort(Comparator.comparingDouble(Src::rerankScore).reversed());

        List<Src> top = new ArrayList<>();
        int rank = 1;
        for (Src s : reordered) {
            if (top.size() >= keepK) {
                break;
            }
            top.add(new Src(rank++, s.chromaId(), s.docId(), s.docName(), s.chunkIndex(),
                    s.heading(), s.content(), s.rerankScore()));
        }
        return top;
    }

    private String previewChunks(List<Src> list) {
        StringBuilder sb = new StringBuilder("重排后的源切片：\n");
        for (Src s : list) {
            sb.append("#").append(s.rank())
                    .append("  rerank ").append(String.format("%.4f", s.rerankScore()))
                    .append("  ").append(s.content().length()).append(" 字")
                    .append("  ·  ").append(abbreviate(s.content(), 46)).append("\n");
        }
        return sb.toString();
    }

    private String targetOf(Stage stage) {
        return switch (stage) {
            case CACHE_LOOKUP, CACHE_STORE -> "Cache";
            case GEN_CONTEXT -> "Qwen/Chroma/Rerank";
            case GEN_CALL -> "DashScope-Chat";
            default -> "Generate";
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
        return meta == null || meta.isNull() ? "" : meta.path(key).asText("");
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
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
