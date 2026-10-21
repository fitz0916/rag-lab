package com.raglab.eval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.config.RagLabProperties;
import com.raglab.eval.dao.EvalCaseDao;
import com.raglab.eval.dao.EvalRunDao;
import com.raglab.eval.model.EvalCase;
import com.raglab.eval.model.EvalResult;
import com.raglab.eval.model.EvalRun;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class EvalService {

    public record EvalDetail(EvalRun run, EvalResult result) {
    }

    private final EvalCaseDao caseDao;
    private final EvalRunDao runDao;
    private final EvalRunner runner;
    private final ObjectMapper objectMapper;
    private final RagLabProperties props;

    public EvalService(EvalCaseDao caseDao, EvalRunDao runDao, EvalRunner runner,
                       ObjectMapper objectMapper, RagLabProperties props) {
        this.caseDao = caseDao;
        this.runDao = runDao;
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public String submit(List<Long> caseIds, String strategiesRaw, Integer topK, Integer recallTopN) {
        List<EvalRetriever.Strategy> strategies = parseStrategies(strategiesRaw);

        int k = (topK == null || topK <= 0) ? props.eval().topK() : Math.min(topK, 20);
        int rn = (recallTopN == null || recallTopN <= 0) ? props.eval().recallTopN() : Math.min(recallTopN, 50);

        List<EvalCase> cases = caseDao.listByIds(caseIds, true);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "没有可评测的用例：检查用例是否被停用，或者先在页面上添加一条");
        }

        String strategiesText = String.join(",", strategies.stream().map(Enum::name).toList());
        String runId = UUID.randomUUID().toString();
        runDao.insert(runId, strategiesText, k, rn, cases.size());
        runner.run(runId, caseIds, strategies, k, rn);
        return runId;
    }

    /**
     * 解析策略列表。非法名字直接报错（而不是静默忽略）——
     * 静默忽略会让你以为测了 4 种，其实只测了 3 种，然后拿错数据下结论。
     */
    private List<EvalRetriever.Strategy> parseStrategies(String raw) {
        String text = (raw == null || raw.isBlank()) ? props.eval().strategies() : raw;
        LinkedHashSet<EvalRetriever.Strategy> set = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String p = part.strip().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            try {
                set.add(EvalRetriever.Strategy.valueOf(p));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不认识的策略：" + p
                        + "，可选 " + List.of(EvalRetriever.Strategy.values()));
            }
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("至少要选一种策略");
        }
        return new ArrayList<>(set);
    }

    public EvalDetail detail(String runId) {
        EvalRun run = runDao.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测记录不存在：" + runId));
        String json = runDao.findSummaryJson(runId);
        EvalResult result = null;
        if (json != null && !json.isBlank()) {
            try {
                result = objectMapper.readValue(json, EvalResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("评测结果反序列化失败", e);
            }
        }
        if (result == null) {
            result = new EvalResult(run.caseCount(), run.topK(), run.recallTopN(), List.of(), List.of());
        }
        return new EvalDetail(run, result);
    }

    public List<EvalCase> listCases(boolean onlyEnabled) {
        return caseDao.listAll(onlyEnabled);
    }

    /**
     * 新增用例。expectText 用最简单的格式，方便在页面上手输：
     *   "采集深度,E.12 | 采集深度,Ⅱ级"
     * 「|」分隔组，「,」分隔组内关键词（全都要出现）。
     */
    public long addCase(String name, String query, String expectText, String difficulty) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("用例名不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("测试问题不能为空");
        }
        List<List<String>> groups = parseExpect(expectText);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("至少要写一组期望关键词，否则这条用例永远不可能命中");
        }
        String diff = difficulty == null || difficulty.isBlank()
                ? "MEDIUM" : difficulty.strip().toUpperCase(Locale.ROOT);
        if (!List.of("EASY", "MEDIUM", "HARD").contains(diff)) {
            throw new IllegalArgumentException("难度只能是 EASY / MEDIUM / HARD");
        }
        try {
            return caseDao.insert(name.strip(), query.strip(),
                    objectMapper.writeValueAsString(groups), diff);
        } catch (Exception e) {
            throw new IllegalStateException("用例保存失败", e);
        }
    }

    private List<List<String>> parseExpect(String expectText) {
        List<List<String>> groups = new ArrayList<>();
        if (expectText == null || expectText.isBlank()) {
            return groups;
        }
        for (String groupRaw : expectText.split("\\|")) {
            List<String> kws = new ArrayList<>();
            for (String kw : groupRaw.split(",")) {
                String v = kw.strip();
                if (!v.isEmpty()) {
                    kws.add(v);
                }
            }
            if (!kws.isEmpty()) {
                groups.add(kws);
            }
        }
        return groups;
    }

    public void toggleCase(long id, boolean enabled) {
        if (caseDao.findById(id).isEmpty()) {
            throw new IllegalArgumentException("用例不存在：" + id);
        }
        caseDao.setEnabled(id, enabled);
    }

    public void deleteCase(long id) {
        caseDao.delete(id);
    }

    /** 供 controller 解析请求里可能出现的 JSON 关键词组 */
    public List<List<String>> parseGroupsJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<String>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
