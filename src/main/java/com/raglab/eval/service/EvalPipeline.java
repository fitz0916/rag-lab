package com.raglab.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.Stage;
import com.raglab.eval.dao.EvalCaseDao;
import com.raglab.eval.dao.EvalRunDao;
import com.raglab.eval.model.EvalCase;
import com.raglab.eval.model.EvalResult;
import com.raglab.eval.model.EvalScore;
import com.raglab.eval.model.EvalUnit;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.log.TaskLogger;
import com.raglab.ingest.service.ChromaClient;
import com.raglab.ingest.service.QwenEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测流水线（阶段六）。
 *
 * 一句话：**同一批问题，把每个策略都跑一遍，用同一把尺子量。**
 *
 * 为什么这件事比再加一个优化更重要？
 *   前面五个阶段的「效果」都是靠单个案例的观感判断的 ——
 *   「看，3.10 排到第一了」。但换一个问题呢？换个切片参数呢？
 *   没有这套东西，你无法回答「这次改动到底变好了还是变差了」，
 *   只能凭印象说「好像好一点」。
 *
 * 执行顺序刻意设计成「先全部检索、再统一打分、最后汇总」，
 * 而不是「查一次就算一次分」：
 *   因为检索是慢的、可能失败的部分，打分是纯 CPU 的。
 *   分开之后，某个策略失败不会影响其他策略的样本量。
 */
@Service
public class EvalPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvalPipeline.class);

    private final EvalCaseDao caseDao;
    private final EvalRunDao runDao;
    private final EvalRetriever retriever;
    private final EvalScorer scorer;
    private final QwenEmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final IngestLogBus logBus;
    private final ObjectMapper objectMapper;

    public EvalPipeline(EvalCaseDao caseDao, EvalRunDao runDao, EvalRetriever retriever,
                        EvalScorer scorer, QwenEmbeddingClient embeddingClient,
                        ChromaClient chromaClient, IngestLogBus logBus, ObjectMapper objectMapper) {
        this.caseDao = caseDao;
        this.runDao = runDao;
        this.retriever = retriever;
        this.scorer = scorer;
        this.embeddingClient = embeddingClient;
        this.chromaClient = chromaClient;
        this.logBus = logBus;
        this.objectMapper = objectMapper;
    }

    /** 一个「用例 × 策略」的原始输出 */
    private record Cell(String strategy, List<EvalUnit> units, long latencyMs, String error,
                        int sourceChars, int contextChars, String contextText) {
    }

    /** 一个用例跑完所有策略 */
    private record CaseRun(EvalCase evalCase, List<Cell> cells) {
    }

    public void run(String runId, List<Long> caseIds, List<EvalRetriever.Strategy> strategies,
                    int topK, int recallTopN) {
        TaskLogger logger = new TaskLogger(runId, logBus);
        long start = System.currentTimeMillis();
        Stage stage = Stage.EVAL_LOAD;
        long stepStart = start;
        boolean success = false;

        try {
            // ================= 1. 载入测试集 =================
            stage = Stage.EVAL_LOAD;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            List<EvalCase> cases = caseDao.listByIds(caseIds, true);
            if (cases.isEmpty()) {
                throw new IllegalStateException("测试集为空：先在「阶段六」页面添加用例，或检查用例是否被停用");
            }
            int noGroup = 0;
            for (EvalCase c : cases) {
                if (c.groupCount() == 0) {
                    noGroup++;
                }
            }
            if (noGroup > 0) {
                logger.warn(stage, "%d 条用例没有配置期望关键词，它们只会拉低所有策略的分数".formatted(noGroup),
                        "期望关键词就是这个阶段的「标准答案」。没有它，任何策略都不可能命中。");
            }

            logger.info(stage, "载入 %d 条用例，%d 种策略：%s"
                    .formatted(cases.size(), strategies.size(), strategies));
            logger.info(stage, "统一口径：Top-K = %d，召回 N = %d".formatted(topK, recallTopN));
            for (EvalCase c : cases) {
                logger.info(stage, "  #%d %s · %s · %d 组期望".formatted(
                        c.id(), pad(c.name(), 20), c.difficulty(), c.groupCount()));
            }

            String collectionId = chromaClient.collectionIdByName(chromaClient.collectionName());
            int total = chromaClient.count(collectionId);
            if (total == 0) {
                throw new IllegalStateException("向量库里一条数据都没有。先去「阶段一」入库文档，再回来评测。");
            }

            logger.ok(stage, "测试集就绪，向量库共 %d 条".formatted(total),
                    ("判定规则（这一条必须明确，否则指标没法解释）：\n" +
                            "  结果文本（小写、空白归一）包含某个期望组的【全部】关键词 → 这条结果对该组算相关\n" +
                            "  一个用例命中任意一组即算相关；coverage 才是「命中了几组」\n\n" +
                            "指标：\n" +
                            "  Hit@1 / Hit@3 / Hit@K  第一条相关结果出现在第 1 / 前 3 / 前 K 名\n" +
                            "  MRR                    1 / 第一条相关结果的排名，衡量「排得够不够靠前」\n" +
                            "  coverage               Top-K 内命中的期望组比例，衡量「答案是否完整」\n\n" +
                            "注意：关键词只能验证字面命中的东西，语义正确性它管不了。\n" +
                            "所以低分不等于实现错，也可能是用例写得刁，或者库里确实没有答案。"),
                    System.currentTimeMillis() - stepStart);

            // ================= 2. 逐用例检索 =================
            stage = Stage.EVAL_CASE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);
            logger.info(stage, "每个问题只做 1 次 embedding，四种策略共用同一个问题向量 —— 省掉 3/4 的 embedding 调用");
            logger.info(stage, "顺序执行（不并发）：并发会让日志交错，也不好定位是谁慢");

            List<CaseRun> runs = new ArrayList<>(cases.size());
            long embedTotal = 0;
            int caseNo = 0;
            for (EvalCase c : cases) {
                caseNo++;
                logger.info(stage, "[%d/%d] 「%s」 %s".formatted(caseNo, cases.size(), c.name(), c.query()));

                float[] qv;
                long embedStart = System.currentTimeMillis();
                try {
                    qv = embeddingClient.embedBatch(List.of(c.query())).vectors().get(0);
                    embedTotal += System.currentTimeMillis() - embedStart;
                } catch (Exception e) {
                    logger.fail(stage, "问题向量化失败，该用例所有策略记为未命中", e, "Qwen", 0);
                    List<Cell> failed = new ArrayList<>();
                    for (EvalRetriever.Strategy s : strategies) {
                        failed.add(new Cell(s.name(), List.of(), 0, "问题向量化失败：" + e.getMessage(), 0, 0, null));
                    }
                    runs.add(new CaseRun(c, failed));
                    continue;
                }

                List<Cell> cells = new ArrayList<>(strategies.size());
                for (EvalRetriever.Strategy s : strategies) {
                    long t0 = System.currentTimeMillis();
                    try {
                        Cell cell;
                        if (s == EvalRetriever.Strategy.CONTEXT) {
                            // 压缩阶段要额外拿到「真正拼出来的上下文」，才能量压缩率和证据保留率
                            EvalRetriever.ContextOutcome co = retriever.retrieveContext(
                                    c.query(), qv, recallTopN, topK, 0);
                            long cost = System.currentTimeMillis() - t0;
                            cell = new Cell(s.name(), co.chunkUnits(), cost, null,
                                    co.sourceChars(), co.contextChars(), co.context());
                        } else {
                            List<EvalUnit> units = retriever.retrieve(s, c.query(), qv, recallTopN, topK);
                            long cost = System.currentTimeMillis() - t0;
                            cell = new Cell(s.name(), units, cost, null, 0, 0, null);
                        }
                        cells.add(cell);
                        logger.info(stage, "      %-8s 返回 %d 条，%dms%s".formatted(
                                s.name(), cell.units().size(), cell.latencyMs(),
                                cell.units().isEmpty() ? "（空结果）"
                                        : "，Top1: " + abbreviate(cell.units().get(0).text(), 40)
                                        + (cell.contextChars() > 0
                                        ? "，压缩后上下文 " + cell.contextChars() + " 字" : "")));
                    } catch (Exception e) {
                        long cost = System.currentTimeMillis() - t0;
                        cells.add(new Cell(s.name(), List.of(), cost, e.getMessage(), 0, 0, null));
                        logger.fail(stage, "   %s 策略失败（继续跑其他策略，不中断整轮）".formatted(s.name()),
                                e, s.name(), cost);
                    }
                }
                runs.add(new CaseRun(c, cells));
            }
            logger.ok(stage, "全部用例检索完成：%d 用例 × %d 策略 = %d 次检索"
                            .formatted(cases.size(), strategies.size(), cases.size() * strategies.size()),
                    "问题向量化总耗时 %dms（如果每个策略各做一次，这里会是 %dms）"
                            .formatted(embedTotal, embedTotal * strategies.size()),
                    System.currentTimeMillis() - stepStart);

            // ================= 3. 打分 =================
            stage = Stage.EVAL_SCORE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            List<EvalResult.CaseRow> rows = new ArrayList<>(runs.size());
            // 保留原始 EvalScore，写明细表时要用
            Map<String, Map<Long, EvalScore>> scoreBook = new LinkedHashMap<>();

            for (CaseRun cr : runs) {
                EvalCase c = cr.evalCase();
                List<EvalResult.CaseMetric> metrics = new ArrayList<>(cr.cells().size());
                StringBuilder line = new StringBuilder();

                for (Cell cell : cr.cells()) {
                    EvalScore sc = cell.error() == null
                            ? scorer.score(cell.units(), c.expect(), topK)
                            : EvalScore.miss();
                    scoreBook.computeIfAbsent(cell.strategy(), k -> new LinkedHashMap<>())
                            .put(c.id(), sc);

                    // 压缩阶段单独量一次：把整段上下文当成一个单元，看期望组命中了多少
                    double contextCoverage = 0;
                    if (cell.contextText() != null && !cell.contextText().isBlank()) {
                        contextCoverage = scorer.score(
                                List.of(new EvalUnit(1, "CONTEXT", cell.contextText(), 0, "", -1, "", 0)),
                                c.expect(), 1).coverage();
                    }

                    metrics.add(new EvalResult.CaseMetric(cell.strategy(), sc.hit1(), sc.hit3(), sc.hitK(),
                            sc.firstRank(), sc.mrr(), sc.coverage(), cell.latencyMs(),
                            sc.matched(), cell.error(),
                            cell.sourceChars(), cell.contextChars(), contextCoverage,
                            cell.units().stream().limit(Math.min(topK, 5)).map(EvalUnit::compact).toList()));

                    String mark = cell.error() != null ? "ERR"
                            : sc.firstRank() > 0 ? "#" + sc.firstRank() : "—";
                    line.append(" ").append(cell.strategy()).append(" ").append(mark);
                }

                rows.add(new EvalResult.CaseRow(c.id(), c.name(), c.query(), c.difficulty(),
                        c.groupCount(), metrics));
                logger.info(stage, "[%s] %s%s".formatted(pad(c.difficulty(), 6), pad(c.name(), 18), line));
            }
            logger.ok(stage, "打分完成：使用 %d 条用例 × %d 策略的原始结果".formatted(rows.size(), strategies.size()),
                    "每一行是一个用例。数字是「第一条相关结果排第几」，— 表示 Top-K 里一条都没有。\n" +
                            "看着这张表，很容易看出哪个策略在哪个用例上翻车。",
                    System.currentTimeMillis() - stepStart);

            // ================= 4. 汇总 =================
            stage = Stage.EVAL_AGGREGATE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            List<EvalResult.StrategySummary> summaries = summarize(rows, strategies);
            logger.info(stage, "按策略汇总（同一批用例，只换策略）：");
            for (String l : table(summaries, topK)) {
                logger.info(stage, l);
            }
            logger.info(stage, "耗时不含「问题向量化」—— 它在每个用例只做 1 次，四种策略共用，摊给谁都不公平");

            int failedTotal = summaries.stream().mapToInt(EvalResult.StrategySummary::failures).sum();
            if (failedTotal > 0) {
                StringBuilder detail = new StringBuilder("失败的组合（已从分母排除，按 0 分算会冤枉它）：\n");
                for (EvalResult.CaseRow row : rows) {
                    for (EvalResult.CaseMetric m : row.metrics()) {
                        if (m.error() != null) {
                            detail.append("  ").append(pad(m.strategy(), 8))
                                    .append(row.name()).append("：").append(m.error()).append("\n");
                        }
                    }
                }
                detail.append("\n对策：重排/向量化这类远端调用本来就会偶发超时，\n")
                        .append("评测里失败一次就压低整列分数，很容易得出「这策略更差」的错误结论。");
                logger.warn(stage, "有 %d 次调用失败，未计入命中率".formatted(failedTotal), detail.toString());
            }

            // 压缩阶段单独看：排名指标之外，还要看「删了多少字、证据还在不在」
            for (EvalResult.StrategySummary s : summaries) {
                if (s.avgContextChars() <= 0) {
                    continue;
                }
                logger.info(stage, ("%s 压缩：源切片平均 %.0f 字 → 上下文平均 %.0f 字（保留 %.0f%%），"
                                + "上下文里期望组覆盖 %.0f%%")
                                .formatted(s.strategy(), s.avgSourceChars(), s.avgContextChars(),
                                        s.avgContextChars() / Math.max(1, s.avgSourceChars()) * 100,
                                        s.avgContextCoverage() * 100));
            }
            logger.info(stage, "口径差异：CONTEXT 的排序单元仍是切片（分数 = 片内最相关句的分数），"
                    + "这样才和前三行可比；句子级的细节看上面那行压缩指标");

            // 和第一个策略（baseline）比
            if (summaries.size() > 1) {
                EvalResult.StrategySummary base = summaries.get(0);
                StringBuilder cmp = new StringBuilder();
                cmp.append("以 %s 为基准（它是阶段二的 baseline）：\n".formatted(base.strategy()));
                for (int i = 1; i < summaries.size(); i++) {
                    EvalResult.StrategySummary s = summaries.get(i);
                    double d1 = (s.hit1Rate() - base.hit1Rate()) * 100;
                    double d3 = (s.hit3Rate() - base.hit3Rate()) * 100;
                    double dm = s.mrr() - base.mrr();
                    double speed = base.avgLatencyMs() <= 0 ? 0 : s.avgLatencyMs() / base.avgLatencyMs();
                    cmp.append("  %-8s Hit@1 %+.1f pt，Hit@3 %+.1f pt，MRR %+.3f，耗时 %.1f×\n"
                            .formatted(s.strategy(), d1, d3, dm, speed));
                }
                logger.ok(stage, "相对 baseline 的变化（pt = 百分点）", cmp.toString(),
                        System.currentTimeMillis() - stepStart);
            }

            // 逐用例冠军 + 难度分析
            Map<String, Integer> wins = new LinkedHashMap<>();
            for (EvalResult.CaseRow row : rows) {
                String best = null;
                double bestMrr = -1;
                double bestCov = -1;
                long bestLat = Long.MAX_VALUE;
                for (EvalResult.CaseMetric m : row.metrics()) {
                    if (m.error() != null) {
                        continue;
                    }
                    boolean better = m.mrr() > bestMrr
                            || (m.mrr() == bestMrr && m.coverage() > bestCov)
                            || (m.mrr() == bestMrr && m.coverage() == bestCov && m.latencyMs() < bestLat);
                    if (better) {
                        best = m.strategy();
                        bestMrr = m.mrr();
                        bestCov = m.coverage();
                        bestLat = m.latencyMs();
                    }
                }
                if (best != null) {
                    wins.merge(best, 1, Integer::sum);
                }
            }
            logger.ok(stage, "逐用例冠军次数：" + wins,
                    "同一个平均分可能由完全不同的用例支撑起来 —— 冠军分布比平均分更能说明「它擅长什么」。",
                    System.currentTimeMillis() - stepStart);

            Map<String, String> hard = new LinkedHashMap<>();
            for (EvalResult.CaseRow row : rows) {
                if (!"HARD".equalsIgnoreCase(row.difficulty())) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (EvalResult.CaseMetric m : row.metrics()) {
                    sb.append("  ").append(pad(m.strategy(), 8))
                            .append(m.firstRank() > 0 ? "命中 #" + m.firstRank() : "未命中")
                            .append("  覆盖 ").append(Math.round(m.coverage() * 100)).append("%\n");
                }
                hard.put(row.name(), sb.toString());
            }
            if (!hard.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                hard.forEach((k, v) -> sb.append(k).append("\n").append(v));
                logger.warn(stage, "困难用例明细（这些才是真正拉开差距的地方）", sb.toString());
            }

            // ================= 5. 落库 =================
            stage = Stage.EVAL_DONE;
            stepStart = System.currentTimeMillis();
            logger.enter(stage);

            for (CaseRun cr : runs) {
                for (Cell cell : cr.cells()) {
                    EvalScore sc = scoreBook.getOrDefault(cell.strategy(), Map.of())
                            .getOrDefault(cr.evalCase().id(), EvalScore.miss());
                    runDao.insertDetail(runId, cr.evalCase().id(), cell.strategy(), sc,
                            cell.latencyMs(), cell.error());
                }
            }

            EvalResult result = new EvalResult(cases.size(), topK, recallTopN, summaries, rows);
            long totalCost = System.currentTimeMillis() - start;
            runDao.finish(runId, "SUCCESS", null, totalCost, objectMapper.writeValueAsString(result));

            String best = summaries.isEmpty() ? "—"
                    : summaries.stream().max(Comparator.comparingDouble(EvalResult.StrategySummary::mrr))
                    .map(EvalResult.StrategySummary::strategy).orElse("—");
            logger.ok(stage, "评测完成：%d 用例 × %d 策略，总耗时 %dms，MRR 最高的是 %s"
                            .formatted(cases.size(), strategies.size(), totalCost, best),
                    "这条运行记录已落库（kb_eval_run + kb_eval_detail）。\n" +
                            "下次改切片参数、换 embedding 模型、调 RRF 的 k，都跑一遍同一套用例再对比 ——\n" +
                            "这才是「优化有效」的证据，而不是感觉。",
                    totalCost);
            success = true;

        } catch (Exception e) {
            long stepCost = System.currentTimeMillis() - stepStart;
            logger.fail(stage, "「" + stage.getLabel() + "」失败", e, "Eval", stepCost);
            runDao.finish(runId, "FAILED", truncate(e.getMessage(), 1900),
                    System.currentTimeMillis() - start, null);
            log.error("评测失败 runId={} stage={}", runId, stage.name(), e);
        } finally {
            logBus.close(runId, success ? "SUCCESS" : "FAILED");
        }
    }

    // ------------------------------------------------------------------

    private List<EvalResult.StrategySummary> summarize(List<EvalResult.CaseRow> rows,
                                                       List<EvalRetriever.Strategy> strategies) {
        List<EvalResult.StrategySummary> out = new ArrayList<>(strategies.size());
        for (EvalRetriever.Strategy s : strategies) {
            String name = s.name();
            int total = 0, failures = 0, h1 = 0, h3 = 0, hk = 0, ctxN = 0;
            double mrrSum = 0, covSum = 0, latSum = 0, srcSum = 0, ctxSum = 0, ctxCovSum = 0;
            for (EvalResult.CaseRow row : rows) {
                for (EvalResult.CaseMetric m : row.metrics()) {
                    if (!name.equals(m.strategy())) {
                        continue;
                    }
                    total++;
                    if (m.error() != null) {
                        // 失败 ≠ 未命中。把它算成 0 分会冤枉这个策略（尤其是偶发的接口超时），
                        // 所以从分母里排除，只在 failures 里报出来。
                        failures++;
                        continue;
                    }
                    latSum += m.latencyMs();
                    if (m.compressed()) {
                        ctxN++;
                        srcSum += m.sourceChars();
                        ctxSum += m.contextChars();
                        ctxCovSum += m.contextCoverage();
                    }
                    if (m.hit1()) {
                        h1++;
                    }
                    if (m.hit3()) {
                        h3++;
                    }
                    if (m.hitK()) {
                        hk++;
                    }
                    mrrSum += m.mrr();
                    covSum += m.coverage();
                }
            }
            int n = Math.max(1, total - failures);
            int cn = Math.max(1, ctxN);
            out.add(new EvalResult.StrategySummary(name, total, failures,
                    h1, h3, hk,
                    round4((double) h1 / n), round4((double) h3 / n), round4((double) hk / n),
                    round4(mrrSum / n), round4(covSum / n),
                    round4((double) latSum / n), (long) latSum,
                    round4(srcSum / cn), round4(ctxSum / cn), round4(ctxCovSum / cn)));
        }
        return out;
    }

    private List<String> table(List<EvalResult.StrategySummary> sums, int topK) {
        List<String> lines = new ArrayList<>();
        String header = pad("策略", 10) + pad("Hit@1", 9) + pad("Hit@3", 9) + pad("Hit@" + topK, 9)
                + pad("MRR", 9) + pad("覆盖率", 10) + pad("平均耗时", 10);
        lines.add(header);
        lines.add("─".repeat(displayWidth(header)));
        for (EvalResult.StrategySummary s : sums) {
            lines.add(pad(s.strategy(), 10)
                    + pad(pct(s.hit1Rate()), 9)
                    + pad(pct(s.hit3Rate()), 9)
                    + pad(pct(s.hitKRate()), 9)
                    + pad(String.format("%.3f", s.mrr()), 9)
                    + pad(pct(s.avgCoverage()), 10)
                    + pad(String.format("%.0fms", s.avgLatencyMs()), 10)
                    + (s.failures() > 0 ? " 失败 " + s.failures() : ""));
        }
        return lines;
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    /** 中文按 2 个字符宽度算，否则表格一定错位 */
    private static String pad(String s, int width) {
        String v = s == null ? "" : s;
        int w = displayWidth(v);
        if (w >= width) {
            return v;
        }
        return v + " ".repeat(width - w);
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c > 0x2E80 && c < 0xA4D0) || (c >= 0xFF00 && c <= 0xFF60) ? 2 : 1;
        }
        return w;
    }

    private static double round4(double d) {
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
