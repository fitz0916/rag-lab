package com.raglab.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.eval.dao.EvalCaseDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 首次启动把 db/eval-cases.json 里的测试用例灌进 kb_eval_case。
 *
 * 为什么种子用例放在 JSON 资源里，而不是 Java 常量里？
 *   因为测试集是要不断改的 —— 新增问题、调整关键词，改 JSON 重启即可，
 *   不需要改代码重新编译。而且它在 resources/db/ 下，和 schema.sql 放一起，
 *   看目录就知道「这个阶段的数据从哪来」。
 *
 * 只在表为空时导入，不会覆盖你自己加的用例。
 */
@Component
public class EvalCaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseSeeder.class);

    private final EvalCaseDao caseDao;
    private final ObjectMapper objectMapper;

    public EvalCaseSeeder(EvalCaseDao caseDao, ObjectMapper objectMapper) {
        this.caseDao = caseDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int existing = caseDao.count();
            if (existing > 0) {
                log.info("[eval] 测试集已有 {} 条用例，跳过导入", existing);
                return;
            }
            ClassPathResource res = new ClassPathResource("db/eval-cases.json");
            if (!res.exists()) {
                log.warn("[eval] 找不到 db/eval-cases.json，跳过导入");
                return;
            }
            JsonNode root;
            try (InputStream in = res.getInputStream()) {
                root = objectMapper.readTree(in);
            }
            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode node : root) {
                String name = node.path("name").asText("");
                String query = node.path("query").asText("");
                String difficulty = node.path("difficulty").asText("MEDIUM").toUpperCase();
                JsonNode expect = node.path("expect");
                if (name.isBlank() || query.isBlank() || !expect.isArray() || expect.isEmpty()) {
                    continue;
                }
                rows.add(List.of(name, query, objectMapper.writeValueAsString(expect), difficulty));
            }
            int n = caseDao.insertBatch(rows);
            log.info("[eval] 已导入 {} 条种子用例", n);
        } catch (Exception e) {
            // 导入失败不该让应用起不来 —— 评测是附加能力
            log.warn("[eval] 种子用例导入失败：{}", e.getMessage());
        }
    }
}
