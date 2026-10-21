-- ============================================================
-- RAG Lab 入库链路元数据表
-- 设计原则：观测优先。每个步骤都要能回答「做了什么、花了多久、结果如何、失败为什么」
-- ============================================================

-- 文档主表：一份原始文档一行
CREATE TABLE IF NOT EXISTS kb_document
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_name     VARCHAR(255) NOT NULL COMMENT '文档名（文件名或手工标题）',
    source_type  VARCHAR(16)  NOT NULL COMMENT '来源：FILE / TEXT',
    file_path    VARCHAR(512)          DEFAULT NULL COMMENT '落盘的原始文件路径',
    file_size    BIGINT       NOT NULL DEFAULT 0 COMMENT '原始字节数',
    content_hash CHAR(64)     NOT NULL COMMENT '正文 SHA-256，用于秒传/去重',
    char_count   INT          NOT NULL DEFAULT 0 COMMENT '清洗后字符数',
    chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '切片数',
    vector_count INT          NOT NULL DEFAULT 0 COMMENT '成功写入向量库的条数',
    status       VARCHAR(24)  NOT NULL COMMENT 'PENDING/PARSING/SPlITTING/EMBEDDING/STORING/DONE/FAILED',
    error_msg    VARCHAR(2000)         DEFAULT NULL COMMENT '失败原因（含根因）',
    collection   VARCHAR(128)          DEFAULT NULL COMMENT '写入的 Chroma collection 名',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doc_hash (content_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '原始文档';

-- 切片表：切片是向量化的最小单位，正文在这里留一份原始副本，方便排查
CREATE TABLE IF NOT EXISTS kb_chunk
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT      NOT NULL COMMENT '所属文档',
    chunk_index INT         NOT NULL COMMENT '文档内序号，从 0 开始',
    heading     VARCHAR(255)         DEFAULT NULL COMMENT '所属 Markdown 标题（切片时继承）',
    content     TEXT        NOT NULL COMMENT '切片正文',
    char_count  INT         NOT NULL COMMENT '字符数',
    token_est   INT                  DEFAULT NULL COMMENT '估算 token（中文约 1.5 字/token）',
    chroma_id   VARCHAR(80) NOT NULL COMMENT '向量库里的主键 id',
    status      VARCHAR(16) NOT NULL COMMENT 'PENDING/EMBEDDED/STORED/FAILED',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_idx (doc_id, chunk_index),
    KEY idx_chunk_doc (doc_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '文档切片';

-- 入库任务表：一次入库 = 一个任务，SSE 日志按 taskId 归集
CREATE TABLE IF NOT EXISTS kb_ingest_task
(
    id             VARCHAR(36)  NOT NULL COMMENT '任务 id（UUID）',
    doc_id         BIGINT                DEFAULT NULL,
    doc_name       VARCHAR(255) NOT NULL,
    status         VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    current_stage  VARCHAR(40)           DEFAULT NULL COMMENT '当前所处步骤',
    total_chunks   INT          NOT NULL DEFAULT 0,
    done_chunks    INT          NOT NULL DEFAULT 0 COMMENT '已完成向量化的切片数',
    stored_vectors INT          NOT NULL DEFAULT 0 COMMENT '已写入向量库条数',
    error_msg      VARCHAR(2000)         DEFAULT NULL,
    error_stage    VARCHAR(40)           DEFAULT NULL COMMENT '在哪一步炸的',
    started_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at    DATETIME              DEFAULT NULL,
    cost_ms        BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_doc (doc_id),
    KEY idx_task_started (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '入库任务';

-- 步骤日志表：SSE 推送的同时落库，断线/刷新后可以回放全过程
CREATE TABLE IF NOT EXISTS kb_ingest_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    task_id    VARCHAR(36)  NOT NULL,
    seq        INT          NOT NULL COMMENT '任务内自增序号，保证顺序',
    level      VARCHAR(8)   NOT NULL COMMENT 'INFO/OK/WARN/ERROR',
    stage      VARCHAR(40)  NOT NULL COMMENT '步骤编码',
    message    VARCHAR(1000) NOT NULL COMMENT '一句话说明发生了什么',
    detail     MEDIUMTEXT            DEFAULT NULL COMMENT '详细上下文（请求体、响应、异常栈、指标）',
    cost_ms    BIGINT       NOT NULL DEFAULT 0 COMMENT '该步骤耗时',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_log_task (task_id, seq)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '入库步骤日志';

-- 检索轨迹：阶段二的每次提问一行
-- 日志仍然复用 kb_ingest_log（通过 task_id = trace_id 关联），因为日志结构是通用的
CREATE TABLE IF NOT EXISTS kb_retrieve_trace
(
    id           VARCHAR(36)   NOT NULL COMMENT '轨迹 id，同时作为 SSE 日志的 task_id',
    query_text   VARCHAR(1000) NOT NULL COMMENT '用户原始问题',
    top_k        INT           NOT NULL DEFAULT 5 COMMENT '请求的 Top-K',
    hit_count    INT           NOT NULL DEFAULT 0 COMMENT '实际命中条数',
    top1_score   DOUBLE                 DEFAULT NULL COMMENT 'Top1 相似度（1 - cosine距离）',
    query_dim    INT                    DEFAULT NULL COMMENT '问题向量维度',
    query_norm   DOUBLE                 DEFAULT NULL COMMENT '问题向量 L2 范数',
    query_head   VARCHAR(600)           DEFAULT NULL COMMENT '问题向量前 8 维，便于回看',
    chroma_count INT                    DEFAULT NULL COMMENT '检索时 collection 总条数',
    hits_json    MEDIUMTEXT             DEFAULT NULL COMMENT '命中明细 JSON，供历史回看',
    cost_ms      BIGINT        NOT NULL DEFAULT 0,
    status       VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    error_msg    VARCHAR(2000)          DEFAULT NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_trace_time (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '检索轨迹（阶段二）';

-- 混合检索轨迹：阶段三，一次检索同时存两路召回的明细和 RRF 融合结果
CREATE TABLE IF NOT EXISTS kb_hybrid_trace
(
    id          VARCHAR(36)   NOT NULL COMMENT '轨迹 id，同时作为 SSE 日志的 task_id',
    query_text  VARCHAR(1000) NOT NULL,
    vec_topn    INT           NOT NULL DEFAULT 20 COMMENT '向量召回条数',
    bm25_topn   INT           NOT NULL DEFAULT 20 COMMENT 'BM25 召回条数',
    final_topk  INT           NOT NULL DEFAULT 5 COMMENT '融合后取几条',
    rrf_k       INT           NOT NULL DEFAULT 60 COMMENT 'RRF 平滑常数',
    hit_count   INT           NOT NULL DEFAULT 0,
    cost_ms     BIGINT        NOT NULL DEFAULT 0,
    status      VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    error_msg   VARCHAR(2000)          DEFAULT NULL,
    result_json MEDIUMTEXT             DEFAULT NULL COMMENT '两路召回 + 融合明细 JSON',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_hybrid_time (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '混合检索轨迹（阶段三）';

-- 重排序轨迹：阶段四，记录召回候选与重排后的排名变化
CREATE TABLE IF NOT EXISTS kb_rerank_trace
(
    id          VARCHAR(36)   NOT NULL COMMENT '轨迹 id，同时作为 SSE 日志的 task_id',
    query_text  VARCHAR(1000) NOT NULL,
    recall_topn INT           NOT NULL DEFAULT 20 COMMENT '召回阶段候选数',
    final_topk  INT           NOT NULL DEFAULT 5 COMMENT '重排后取几条',
    hit_count   INT           NOT NULL DEFAULT 0,
    cost_ms     BIGINT        NOT NULL DEFAULT 0,
    status      VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    error_msg   VARCHAR(2000)          DEFAULT NULL,
    result_json MEDIUMTEXT             DEFAULT NULL COMMENT '召回排名 + 重排分 + 排名变化',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rerank_time (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '重排序轨迹（阶段四）';

-- 上下文轨迹：阶段五，记录压缩前后的规模与选中的句子
CREATE TABLE IF NOT EXISTS kb_context_trace
(
    id             VARCHAR(36)   NOT NULL COMMENT '轨迹 id，同时作为 SSE 日志的 task_id',
    query_text     VARCHAR(1000) NOT NULL,
    recall_topk    INT           NOT NULL DEFAULT 5 COMMENT '重排后取几片作为句子来源',
    strategy       VARCHAR(16)   NOT NULL COMMENT 'VECTOR / KEYWORD',
    max_chars      INT           NOT NULL DEFAULT 1200 COMMENT '上下文长度上限',
    origin_chars   INT           NOT NULL DEFAULT 0 COMMENT '压缩前总字数',
    final_chars    INT           NOT NULL DEFAULT 0 COMMENT '压缩后字数',
    sentence_count INT           NOT NULL DEFAULT 0 COMMENT '选中的句子数',
    compress_ratio DOUBLE                 DEFAULT NULL COMMENT '压缩率 = final/origin',
    cost_ms        BIGINT        NOT NULL DEFAULT 0,
    status         VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    error_msg      VARCHAR(2000)          DEFAULT NULL,
    result_json    MEDIUMTEXT             DEFAULT NULL COMMENT '源切片 + 选中句子 + 最终上下文',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_context_time (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '上下文轨迹（阶段五）';

-- ============================================================
-- 评测（阶段六）：测试集 / 评测运行 / 逐用例逐策略明细
-- 为什么要单独建表而不是写死一批问题？
--   因为「优化到底有没有用」必须可复现。问题、期望关键词、每次运行的指标
--   都落库，才能回答「上周改了切分参数，命中率是涨了还是跌了」。
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_eval_case
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    case_name   VARCHAR(120) NOT NULL COMMENT '用例名，如「RRF 融合公式」',
    query_text  VARCHAR(500) NOT NULL COMMENT '测试问题',
    expect_json VARCHAR(1000) NOT NULL COMMENT '期望关键词组 JSON：[[\"a\",\"b\"],[\"c\"]]，命中任一组的切片算相关',
    difficulty  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'EASY/MEDIUM/HARD，用来观察不同难度下的差异',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_eval_case_enabled (enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评测测试集（阶段六）';

CREATE TABLE IF NOT EXISTS kb_eval_run
(
    id          VARCHAR(36)   NOT NULL COMMENT '运行 id，同时作为 SSE 日志的 task_id',
    strategies  VARCHAR(120)  NOT NULL COMMENT '本次评测了哪几种策略，逗号分隔',
    top_k       INT           NOT NULL DEFAULT 5,
    recall_topn INT           NOT NULL DEFAULT 20,
    case_count  INT           NOT NULL DEFAULT 0,
    status      VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    cost_ms     BIGINT        NOT NULL DEFAULT 0,
    error_msg   VARCHAR(2000)          DEFAULT NULL,
    summary_json MEDIUMTEXT            DEFAULT NULL COMMENT '完整评测结果（含逐用例明细与 Top 命中）',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_eval_run_time (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评测运行（阶段六）';

CREATE TABLE IF NOT EXISTS kb_eval_detail
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    run_id     VARCHAR(36) NOT NULL,
    case_id    BIGINT      NOT NULL,
    strategy   VARCHAR(16) NOT NULL,
    hit1       TINYINT     NOT NULL DEFAULT 0,
    hit3       TINYINT     NOT NULL DEFAULT 0,
    hit_k      TINYINT     NOT NULL DEFAULT 0,
    first_rank INT         NOT NULL DEFAULT -1 COMMENT '第一条相关结果的排名，-1 表示没命中',
    mrr        DOUBLE      NOT NULL DEFAULT 0,
    coverage   DOUBLE      NOT NULL DEFAULT 0 COMMENT 'Top-K 内命中的期望关键词组比例',
    matched    VARCHAR(500)         DEFAULT NULL COMMENT '命中的关键词组，便于回看为什么算命中',
    latency_ms BIGINT      NOT NULL DEFAULT 0,
    error_msg  VARCHAR(1000)        DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_eval_detail_run (run_id, strategy),
    KEY idx_eval_detail_case (case_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评测明细（阶段六）';
