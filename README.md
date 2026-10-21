# RAG Lab

按《RAG 检索过程及优化详解》一文逐步实现的 RAG 学习项目。

**当前进度：阶段一~八 全部完成**

页面顶部分阶段切换，每一阶段独立工作区：

| 阶段 | 干什么 | 对应文章 |
|------|--------|---------|
| **01 切片入库** | 文档 → 清洗 → 切片 → Qwen 向量化 → Chroma | 二.1 向量化、七.必选「片段 500 字以内」 |
| **02 向量检索** | 问题 → 向量 → 最近邻 → Top-K 相似度 | 二.基础检索流程 |
| **03 混合检索** | 向量 + BM25 双路召回 → RRF 融合 | 三.优化2 混合检索 |
| **04 重排序** | 召回 Top-N → 交叉编码器精排 Top-K | 三.优化3 重排序 |
| **05 上下文控制** | 拆句 → 打分 → 去重 → 只留关键句 | 三.优化4 上下文长度控制 |
| **06 评测** | 同一批问题跑所有策略 → Hit@K / MRR / 覆盖率 | 四、五（效果验证） |
| **07 生成** | 压缩后的上下文 → Prompt → 答案 + 引用依据 | 一、六（生成与引用） |
| **08 查询重写** | 问题 → N 个变体 → 多路召回 → 融合（可选重排） | 三.优化1 查询重写 |

右侧始终是一块**终端式日志窗口**：每一步在做什么、花了多久、失败了为什么失败，
全部实时滚出来（SSE 推送，同时落库可回放）。

---

## 分支说明（按阶段学习用）

代码按阶段分分支，**每个分支都是累进的**（包含之前所有阶段）：

| 分支 | 包含 | 说明 |
|------|------|------|
| `main` / `stage-1-3` | 阶段一~三 | 基线：入库 + 向量检索 + 混合检索 |
| `stage-4` | 阶段一~四 | 加「重排序」 |
| `stage-5` | 阶段一~五 | 再加「上下文控制」 |
| `stage-6-eval` | 阶段一~六 | 再加「评测体系」 |
| `stage-7-gen` | 阶段一~七 | 再加「生成 + 引用」 |
| `stage-8-rewrite` | 阶段一~八 | 再加「查询重写」（实测无收益，但机制值得看） |

**怎么看「这一阶段到底改了什么」**：

```bash
# 切到某个阶段，跑起来看效果
git checkout stage-5

# 对比相邻阶段，只看新增的部分
git diff stage-4 stage-5 --stat
git diff stage-4 stage-5 -- src/main/java/com/raglab/retrieval/

# 只看新增的文件（每个阶段的核心逻辑通常是新类，不改旧类）
git diff stage-4 stage-5 --name-status
```

每个阶段的增量都很干净：阶段四是 7 个新文件 + 4 处小改，
阶段五是 8 个新文件 + 4 处小改。前面阶段的代码基本不动，
这样对照着看最清楚。

---

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.4.4 | JDK 17 |
| 数据库 | MySQL 8.0 | 存文档 / 切片 / 任务 / 步骤日志，JdbcTemplate 手写 SQL（便于看清每一步） |
| 向量库 | Chroma 1.5.9 | Docker 部署，走 v2 REST 接口，不引 SDK |
| 向量模型 | Qwen `text-embedding-v4` | DashScope OpenAI 兼容模式，1024 维 |
| 前端 | 原生 HTML/CSS/JS | 无构建，SSE 用 EventSource |

---

## 快速启动

### 1. 前置条件

```bash
# Chroma（已有就跳过，注意端口）
docker ps --format "table {{.Names}}\t{{.Ports}}" | grep chroma
curl http://127.0.0.1:58019/api/v2/heartbeat

# MySQL 8
mysql -uroot -p -e "select version()"
```

### 2. 配置（项目根目录 `.env`）

```bash
cp .env.example .env      # 首次创建
```

编辑 `.env`：

```bash
# 必填：到 https://bailian.console.aliyun.com 创建 API-KEY
QWEN_API_KEY=sk-xxxxxxxxxxxxxxxx

# MySQL 密码
MYSQL_PASSWORD=Root1234

# Chroma 宿主机映射端口（不是容器内的 8000）
CHROMA_BASE_URL=http://127.0.0.1:58019
CHROMA_COLLECTION=raglab_chunks

# 切片参数
CHUNK_SIZE=500
CHUNK_OVERLAP=80
```

**加载方式**：`application.yml` 里通过
`spring.config.import: optional:file:./.env[.properties]` 读进来，
`.env` 按 properties 格式解析（`KEY=VALUE`，**不要加引号、不要写 `export`**）。

**优先级从高到低**：

```
命令行参数 --xxx=yyy
  > .env 里的 QWEN_API_KEY
  > 系统环境变量 DASHSCOPE_API_KEY
  > application.yml 里的默认值
```

**两个要点**：

1. `.env` 里 `QWEN_API_KEY` **留空会自动回退**到系统环境变量 `DASHSCOPE_API_KEY`
   （代码里做了兜底，因为 Spring 的 `${A:B}` 在 A 为空串时不会用默认值 B）
2. `.env` **依赖启动时的工作目录**，必须在项目根目录执行 `java -jar` / `mvn spring-boot:run`，
   否则读不到。放到 `target/` 里执行就找不到。

**`.env` 已在 `.gitignore` 中**，不会被提交。

**验证配置是否生效**：

```bash
curl -X POST http://localhost:8080/api/health/qwen-probe
# 成功返回 {"ok":true,"data":{"dimensions":1024,...}}
# Key 错了会返回 401 + 完整诊断
```

> 其余参数（连接池、超时、批大小等）仍在 `src/main/resources/application.yml`。

首次启动会自动执行 `db/schema.sql` 建好 4 张表（全部 `IF NOT EXISTS`，可重复执行）。

### 3. 启动

```bash
# 方式 A：Maven
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 方式 B：打包后运行（推荐）
mvn clean package -DskipTests
java -jar target/rag-lab-1.0.0.jar --server.port=8080
```

> ⚠️ **必须显式指定 `--server.port=8080`**
> 当前机器环境里存在 `SERVER__PORT=49472` 这个变量（Spring 宽松绑定等价于 `server.port`），
> 它的优先级高于 `application.yml`，会导致应用跑到 49472 端口并报「端口被占用」。
> 命令行参数优先级最高，加上它就能压住。

打开 <http://localhost:8080>

---

## 目录结构

```
rag-lab/
├── docs/
│   └── RAG实现路线图.md          ← 阶段规划（先看这个）
├── src/main/java/com/raglab/
│   ├── common/
│   │   ├── Stage.java            流水线步骤枚举（入库 + 检索，含中文标签）
│   │   ├── ErrorDiagnoser.java   ★ 异常 → 人话诊断（分类/根因/排查命令）
│   │   ├── ExternalServiceException.java
│   │   └── GlobalExceptionHandler.java
│   ├── config/
│   │   ├── RagLabProperties.java 全部参数集中绑定
│   │   └── AsyncConfig.java      任务线程池
│   ├── ingest/                   ===== 阶段一 =====
│   │   ├── controller/           REST + SSE 接口、健康检查
│   │   ├── service/
│   │   │   ├── DocumentParser.java     PDF/DOCX/TXT/MD 解析
│   │   │   ├── TextCleaner.java       清洗
│   │   │   ├── SmartTextSplitter.java ★ 分层切片 + overlap
│   │   │   ├── QwenEmbeddingClient.java  Qwen 向量化（批量/重试/顺序回填）
│   │   │   ├── ChromaClient.java      Chroma v2 REST（upsert / query / get）
│   │   │   ├── ChunkInspectService.java 切片解剖（overlap + 真实向量）
│   │   │   └── IngestPipeline.java    ★ 12 步编排，每步都有日志
│   │   ├── log/
│   │   │   ├── IngestLogBus.java  SSE 推送 + 落库 + 回放（两个阶段共用）
│   │   │   └── TaskLogger.java    任务级日志器
│   │   ├── dao/                   JdbcTemplate DAO
│   │   └── model/
│   ├── retrieval/                ===== 阶段二 / 三 =====
│   │   ├── controller/
│   │   │   ├── RetrieveController.java   阶段二：纯向量检索
│   │   │   └── HybridController.java     阶段三：混合检索
│   │   ├── service/
│   │   │   ├── VectorRetriever.java   ★ 阶段二主流程（4 步 + 逐条命中日志）
│   │   │   ├── ChineseTokenizer.java  ★ 中文 bigram + 数字整串分词
│   │   │   ├── Bm25Index.java         ★ 内存倒排索引 + BM25 打分
│   │   │   ├── HybridRetriever.java   ★ 阶段三主流程（两路召回 + RRF）
│   │   │   ├── RetrieveService.java / HybridService.java
│   │   │   └── RetrieveRunner.java / HybridRunner.java
│   │   ├── dao/                      RetrieveTraceDao / HybridTraceDao
│   │   └── model/                    RetrieveHit / HybridResult / ...
│   └── resources/
│       ├── db/schema.sql         6 张表
│       └── static/               index.html / style.css / app.js
```

---

## 已实现功能

### 阶段一：入库链路（12 步，每步一条日志）

| 步骤 | 做什么 | 日志里能看到什么 |
|------|--------|------------------|
| RECEIVE | 校验大小类型 | 原始字节数、来源 |
| HASH | SHA-256 | 指纹前 16 位、是否重复入库 |
| SAVE_DOC | 写 kb_document | 自增 id、初始状态 |
| PARSE | 多格式解析 | 用的哪个解析器、页数/段落数、字符数 |
| CLEAN | 清洗 | 前后字符数、净减比例、折叠空行数 |
| SPLIT | 分层切片 | 切片数、平均/最长/最短长度、硬切次数、前 2 片预览 |
| CHROMA_INIT | 建集合 | 新建还是复用、collection id、维度、距离度量 |
| EMBED | Qwen 向量化 | **每一批**的条数、耗时、tokens、累计 tokens |
| CHROMA_WRITE | 写 Chroma | 每一批的条数、耗时 |
| VERIFY | 三方对账 | MySQL 切片数 / STORED 数 / Chroma 总数 |
| DONE | 收尾 | 总耗时 |

### 阶段二：向量检索（4 步）

| 步骤 | 做什么 | 日志里能看到什么 |
|------|--------|------------------|
| QUERY_RECEIVE | 校验问题、确定 Top-K | 问题原文、Top-K 上限、目标集合 |
| QUERY_EMBED | 问题向量化 | 维度、L2 范数、**前 8 维实际数值**、tokens、耗时 |
| VECTOR_SEARCH | Chroma 最近邻 | 库内总条数、返回候选数、耗时 |
| SCORE_RANK | 距离转相似度 | 换算公式 `similarity = 1 - distance`、Top1 分数 |
| HIT_LIST | 逐条列命中 | **每条命中一行**：排名、相似度、距离、标题、切片号、正文摘要 |
| QUERY_DONE | 落库 | 命中数、Top1 分数、总耗时 |

页面表现：

- 「检索轨迹」卡片显示：问题向量维度 / L2 范数 / 库内向量数 / 耗时 / 前 8 维原始数值
- 「命中结果」按相似度降序排列，色条分级：**≥0.7 绿 · ≥0.5 蓝 · ≥0.35 橙 · 更低灰**
- **点任意一条命中，直接跳到阶段一的切片解剖抽屉**，看这片是怎么切的、向量长什么样、overlap 在哪
- 每次检索写入 `kb_retrieve_trace`，`?stage=2&trace=<id>` 可完整回放日志

### 阶段三：混合检索 + RRF（两路召回）

| 步骤 | 做什么 | 日志里能看到什么 |
|------|--------|------------------|
| HYBRID_RECEIVE | 校验参数 | 两路 Top-N、RRF k、最终 Top-K |
| VEC_RECALL | 向量召回 | 候选数、耗时、前 5 条及相似度 |
| BM25_RECALL | 关键词召回 | **分词结果**、命中的词元（哪些词真生效了）、索引规模、候选数 |
| RRF_FUSE | 融合 | 公式、候选池大小、两路重叠条数 |
| HYBRID_RESULT | 逐条输出 | 每条：RRF 分数 + 向量排名/分数 + BM25 排名/分数 |
| HYBRID_DONE | 落库 | 双路命中 / 仅向量 / 仅 BM25 的分布 |

页面表现：

- 「双路召回概览」：向量 N 条 / BM25 N 条 / **两路重叠数** / 总耗时
- 融合结果每条带来源徽章：**双路（绿）· 仅向量（蓝）· 仅 BM25（橙）**
- 显示「较向量 ↑4」这类排名变化，能直接看出 RRF 把谁顶上来了
- 点任意一条仍可跳到阶段一的切片解剖

**BM25 实现的三个关键点**（决定成败）：

1. **数字/英文整串保留** —— `3.10` 必须是一个 token，切成 `3` + `10` 就废了
2. **中文 bigram** —— `大模型的幻觉` → 大模/模型/型的/的幻/幻觉，30 行代码，不需要词典
3. **索引只含 `STORED` 的切片** —— 否则两路召回的 id 对不上，融合会错位

**为什么用 RRF 而不是加权求和**：

```
余弦相似度 0.62 和 BM25 分数 18.42 是两个量纲，不能相加
（0.1 的余弦差等于多少 BM25 分？没人说得清）

RRF 只看排名：score(d) = Σ 1/(k + rank)，k=60
把「第几名」换算成分数，天然绕开量纲问题
```

**RRF 的副作用**（日志里会主动提示）：
它给「两路都出现但都排中游」的候选加分，偶尔会把不相关的顶上来。
如果日志里出现黄色警告 `Top1 在两路里都不靠前`，就是这个情况 —— 那正是阶段四要解决的。

### 阶段四：重排序（两段式检索）

| 步骤 | 做什么 | 日志里能看到什么 |
|------|--------|------------------|
| RERANK_RECALL | 混合召回 Top-N | 候选数、RRF 排名、前 5 条 |
| RERANK_SCORE | 交叉编码器逐条打分 | 模型名、条数、耗时、tokens、**分数分布 min/max/mean** |
| RERANK_REORDER | 按重排分重排 | **逐条打印「召回#13 → 重排#1 ↑12」** |
| RERANK_RESULT | 输出 Top-K | 选中几条、被挤出几条、挤出的是哪些 |
| RERANK_DONE | 落库 | Top1 分数 + 它召回时的原始排名 |

页面表现：

- 「重排概览」：召回数 / 打分条数 / Top1 重排分 / 耗时
- 每条结果带 **↑N / ↓N 排名变化徽章**，悬浮显示「召回 #x → 重排 #y」
- 单独一块「**被挤出的候选**」：召回时在 Top-K 内、重排后掉出去的
- 点击仍可跳到阶段一的切片解剖

**双塔 vs 交叉编码器 —— 这是这一阶段的全部重点**：

| | 双塔（embedding） | 交叉编码器（rerank） |
|---|---|---|
| 怎么算 | query 和 doc **分开**编码，算两个向量的距离 | query 和 doc **拼在一起**过模型 |
| 速度 | 快（doc 可以离线预算好） | 慢（每一对都要过一遍模型） |
| 精度 | 粗，只能比「整体像不像」 | 准，能看到词与词怎么相互作用 |
| 用在哪 | **召回**：几万条 → 20 条 | **精排**：20 条 → 3~5 条 |

为什么必须两段：交叉编码器太慢，不可能对全库跑；双塔够快但分不清细节。
所以标准做法是 **召回负责「不漏」，精排负责「准」**。

**实测对比**（问「Python 3.10 有什么新特性」，单条打分）：

```
"Python 3.10 引入了 match-case"   → 0.5287   ← 对的
"Python 3.11 性能提升 10-60%"     → 0.1857   ← 分得清
"今天晚饭吃什么"                   → 0.0059
```

向量检索对这两段的相似度是 0.5374 vs 0.4817（几乎分不开），rerank 拉开了 3 倍差距。

### 阶段五：上下文控制（句子级压缩）

| 步骤 | 做什么 | 日志里能看到什么 |
|------|--------|------------------|
| CONTEXT_RECALL | 召回 + 重排 | Top-K 源切片、每片多少字 |
| CONTEXT_SPLIT | 拆句 | 拆句规则、每片拆出几句 |
| CONTEXT_SCORE | 句子打分 | 策略、各片句子数、最高分及它的位置 |
| CONTEXT_SELECT | 选句去重 | 选中几句、去重丢几句、超长丢几句 |
| CONTEXT_BUILD | 组装 | **压缩前后字数、保留比例、token 估算**、逐句打印 |
| CONTEXT_DONE | 落库 | 总耗时 |

页面表现：

- 「压缩效果」：`2,255 → 640 字`，保留 **28.4%**，token 估算 3383 → 960
- 「最终上下文」黑底展示，**一键复制**，可直接拼进 Prompt
- 「选中的句子」按原文顺序，带相关性分和来源切片
- 「源切片贡献」：每片拆了几句、被选中几句（进度条）

**两个决定成败的细节**：

1. **必须去重**。阶段一加的 overlap 会让相邻切片开头重复 80 字左右，不去重的话你以为压缩了，其实是把同样的话说了两遍。用字符 2-gram Jaccard，阈值 0.85。
2. **必须按原文顺序拼回**。按分数从高到低排的话，句子顺序是跳跃的，大模型理解起来费劲，人也读不通。

**两种打分策略可切换对比**：

| 策略 | 做法 | 实测耗时 | 特点 |
|------|------|---------|------|
| VECTOR | 句子级 embedding + 余弦相似度 | ~2.6s | 准，但每句都要调 API |
| KEYWORD | 分词覆盖率 0.75 + 密度 0.25 | ~1.1s | 免费、毫秒级，但分不清同义词 |

**VECTOR 策略的一个工程优化**（很值得记）：

第一版把所有 81 句都做了 embedding，8 批请求 → **14.7 秒**，交互上完全不可接受。

改成「**先用关键词分粗筛出 30 句，只对候选做 embedding 精排**」后降到 **2.6 秒**，
而质量没降 —— 最高分还是那句 `模型交付 / 采集深度要求`（0.6862），一字不差。

这就是两段式思路的复现：**粗筛负责「不漏」，精排负责「准」**。
和阶段四的召回/重排是同一个哲学。

### 阶段六：评测（同一批问题，四种策略，一把尺子）

**为什么这个阶段比再加一个优化更重要**：前面五个阶段的效果都是靠单案例观感判断的
（「看，3.10 排到第一了」）。换一个问题呢？改一下 chunk size 呢？
没有这套东西，你只能回答「好像好一点」。

**判定口径**（必须写清楚，否则数字没法解释）：

```
一段结果（切片或句子）的文本，小写 + 空白归一后，
完整包含某个「期望关键词组」里的全部关键词 → 这段结果对该组算相关
一个用例命中任意一组即算相关；coverage 才是「命中了几组」
```

为什么用「关键词组」而不是「标注正确的 chunk_id」：
**你随时会改 chunk size 重新入库，chunk_id 全变了，标注就作废了**。关键词跟内容走，用例不用重写。

**指标**：Hit@1 / Hit@3 / Hit@K / MRR / 覆盖率，外加真实耗时。

**实测**（12 条用例，其中 3 条 HARD，语料 = 文章 89 片 + 规范 PDF 1071 片，Top-5）：

| 策略 | Hit@1 | Hit@3 | Hit@5 | MRR | 覆盖率 | 平均耗时 |
|------|-------|-------|-------|-----|--------|---------|
| VECTOR | **83.3%** | 91.7% | 91.7% | **0.875** | 87.5% | 23ms |
| HYBRID | 75.0% | 91.7% | 91.7% | 0.833 | 87.5% | 28ms |
| RERANK | 75.0% | 91.7% | **100%** | 0.854 | **95.8%** | 190ms |
| CONTEXT | 66.7% | **100%** | **100%** | 0.805 | **95.8%** | 1146ms |

**三个必须诚实说明的地方**：

1. **VECTOR 的 Hit@1 反而最高。** 混合检索不是无脑变好 —— RRF 会用 BM25 的排名稀释向量第一名，
   在这个 12 条的小样本上就体现为 Hit@1 掉 8 个点。样本小、别过度解读；
   但这也说明「优化」必须有对照组，不能凭信念。
2. **RERANK 的价值在 Hit@5 和覆盖率**：从 91.7%/87.5% 提到 100%/95.8%，
   代价是耗时 8 倍。它不是让第一名更准，而是让「漏掉的」变少。
3. **CONTEXT 的排序单元仍是切片**（分数 = 片内最相关那句的分数），不是句子。
   一开始我直接拿句子去比命中，Hit@1 只有 16.7%，看着像实现错了 ——
   其实是**口径错了**：一组关键词很难同时出现在同一句话里。
   压缩阶段真正该看的是另一组数：`2223 字 → 1124 字（保留 51%），上下文里期望组覆盖 96%`。

**工程上踩到并修掉的坑**（都写进代码注释了）：

- 重排接口偶发失败，一次失败按 0 分算会让整列指标掉下来 → **加了 3 次重试 + 退避**，
  评测里失败也从分母排除，单独报「失败次数」。
- 逐用例明细能看出「平均分掩盖了什么」：同一条策略可能简单题全中、HARD 全崩。
- 表格类内容（PDF 里的「模型交付/采集深度要求」）在切片级永远排不进前列，
  但在句子级能捞回来 —— 这就是阶段五存在的理由，也是评测数据能证明的事。

### 阶段七：生成（上下文 → Prompt → 答案 + 引用）

```
问题 → 召回 + 重排 + 句子压缩 → 编号化资料 → Prompt → qwen-plus → 答案（带 [n] 引用）
```

**三个不做就会「看起来能跑、实际不可信」的设计**：

1. **system 里写死「资料里没有就说没有」**。
   问「Kubernetes Pod 调度策略」，回答是「资料里没有提到…仅涵盖 RAG 优化与油气田规范」，
   而不是编一段 —— 这才是 RAG 和「直接问大模型」的区别。
2. **每句资料带编号，要求模型引用**。能引用说明它真在读资料；
   引用编号越界会在页面上标红（说明它在编）。
3. **记录真实 prompt/completion tokens**。生成成本几乎全在 prompt 长度上，
   而 prompt 长度直接由阶段五的压缩效果决定 —— 这是「压缩到底省了多少钱」的答案。

**实测**（问「模型的采集深度应执行哪一级要求？」）：

```
5 片 → 7 句 · 上下文 1144 字 · prompt 1358 tokens / completion 95 tokens · 4.8s
答案：新建/改扩建的新建部分 → 第Ⅱ级；已建部分 → 第Ⅰ级   ← 与原文一致
引用：[1][2][3][4]，全部对回具体句子
```

**这一阶段顺手修掉一个真 bug**：句子切分把 `E.12` 从中间切开了。
PDF 里的硬折行（`…应执行表` / `E.12 和表 E.1…`）被当成句末，导致模型第一次回答
把「新建部分 Ⅱ 级」写成了「Ⅰ 级」—— 因为关键半句被切走了。
修法：**连续的普通行先累积，只有句末标点 / 空行 / 标题 / 表格 / 列表项才断开**。

修复后 CONTEXT 的指标也跟着涨了：Hit@1 50% → 66.7%，覆盖率 91.7% → 96%，
代价是压缩率从 36% 放宽到 51% —— **完整比短更重要**，这就是取舍。

### 阶段八：查询重写（实测：在这套语料上没有收益）

**做了什么**

```
问题 → LLM 生成 N 个变体（temperature 0.7）
     → N+1 个查询【一次批量 embedding】
     → 每个查询各跑一遍 向量+BM25+RRF
     → 跨变体 RRF 融合（被多路命中的自然浮上来）
     → 可选：用【原问题】再精排一次
```

**三个实现细节**

1. **原查询永远是第一个，且永远是兜底**。改写跑偏了至少还有原来那条。
2. **所有查询一次批量 embedding**。4 个查询发 1 次请求，不是 4 次 —— 循环里逐个 embed 是常见写法错误。
3. **融合用 RRF 再看一次排名**，而不是把分数相加 —— 余弦相似度和 BM25 分的量纲根本不能相加。

**实测结论（15 条用例，含 3 条口语化）**

| 策略 | Hit@1 | Hit@3 | Hit@5 | MRR | 覆盖率 | 平均耗时 |
|------|-------|-------|-------|-----|--------|---------|
| VECTOR | **80.0%** | 86.7% | 86.7% | **0.833** | 83.3% | 29ms |
| HYBRID | 66.7% | 93.3% | 93.3% | 0.800 | 90.0% | 36ms |
| RERANK | 66.7% | 86.7% | **100%** | 0.797 | **96.7%** | 197ms |
| CONTEXT | 73.3% | **100%** | **100%** | 0.844 | **96.7%** | 1261ms |
| REWRITE | 66.7% | 86.7% | **100%** | 0.797 | **96.7%** | **1450ms** |

**REWRITE 和 RERANK 的指标一模一样，但每次提问多花 1.2 秒和 ~170 tokens。**

我还做了一组对照：把「融合后重排」关掉再跑，REWRITE 掉到 Hit@1 60% / Hit@5 86.7% ——
比 RERANK 更差。说明**融合的排序质量不如交叉编码器，两者叠加时是重排在兜底**，
改写带来的差异被重排重新压回了原查询的语义上。

**为什么没用（三条，都可验证）**

1. **语料太小**：2 篇文档、1160 片。文章自己也写了「知识库小于 1 万条时改写收益有限」。
2. **测试集偏「文档式提问」**：用词本来就和原文重合，改写没有发挥空间。
   改写只在「口语 vs 文档」鸿沟大时才见效（用户说「瞎编」，文档写「幻觉」）。
3. **两个优化互相抵消**：融合后用原问题重排，等于又把结果拉回原问题的语义。

**所以这一阶段的产出不是「更准」，而是「知道自己为什么不该默认用它」。**
没有评测集，你只会看到「加了查询重写」四个字，然后默认它有用 —— 这正是最贵的错觉。

**踩到并修掉的一个显示 bug**：融合分是 4 路累加的（0.0624 = 4 × 1/64），
但页面 `from` 全是 `[0]` —— 因为构造结果时用了单条命中的 `from`，没带累加后的集合。
害我一度以为「变体完全没起作用」，其实是显示错了。**评测系统最怕的就是这种「数字在骗你」。**

### 日志窗口

- 4 级着色：INFO 蓝 / OK 绿 / WARN 黄 / ERROR 红
- ERROR 行自动展开诊断，其他行点击展开
- 自动滚动开关、复制、清屏
- **刷新页面不丢日志**：SSE 订阅时从 `kb_ingest_log` 回放
- `http://localhost:8080/?task=<taskId>` 可直接回放某次任务

### 报错诊断（ErrorDiagnoser）

失败时不只抛堆栈，而是输出：

```
【分类】AUTH — 鉴权失败
【目标】Qwen
【HTTP 状态】401 Unauthorized
【响应体】{"error":{"message":"Invalid API-key provided."}}
【根因链】RestClientResponseException → ExternalServiceException
【业务调用点】QwenEmbeddingClient.embedBatch(QwenEmbeddingClient.java:104)
【可能原因】
  - API Key 无效、过期，或者复制时带了空格/换行
【下一步】
  > echo $DASHSCOPE_API_KEY
  > 到 https://bailian.console.aliyun.com 检查 Key 状态与额度
```

---

## 怎么用它学 RAG

### 1. 看一遍完整过程

上传一份文档 → 右侧黑窗口会依次打印 12 步。
重点是 `SPLIT` 那一条：

```
切成 89 片：平均 431 字，最长 494 / 最短 230，硬切 0 次，尾块合并 0 次（size=500, overlap=80）
```

**硬切 0 次**说明没把任何一句话劈断 —— 这是判断切片参数设得对不对的第一指标。

### 2. 看切片结果

左侧「结果预览」：

- 顶部统计条：共几片、总字数、平均 / 最长 / 最短
- 按 **Markdown 标题分组**：能直接看出「标题 → 段落 → 切片」的结构关系
- 每片显示：序号、字数、`chroma_id`、状态、前 120 字

### 3. 解剖一片（关键）

点任意切片 → 右侧抽屉打开，包含四块：

| 区块 | 能看到什么 |
|------|-----------|
| 元信息 | 所属标题、字符数、估算 token、chroma_id、落库状态 |
| 完整正文 | **黄色高亮的就是与上一片重复的 overlap 部分**，明确标出重复了多少字 |
| 向量 | 从 Chroma 实时读回的 1024 维向量：范数、min/max/均值、前 16 维柱状图、原始数值 |
| 位置 | 第几片、上一片 / 下一片跳转 |

这一步能直观回答三个问题：

- 切片到底长什么样？→ 完整正文
- overlap 有什么用？→ 看黄色部分，它保证句子不在边界处被切断
- 向量化到底产出了什么？→ 那 1024 个浮点数就是这段文字的「语义坐标」

### 4. 调参数做对照实验

把 `slice size` 从 500 改成 200，重新入库同一份文档，然后对比：

| 观察点 | 预期变化 |
|--------|---------|
| 切片数 | 明显变多（约 2.5 倍） |
| 硬切次数 | 可能从 0 变成若干（长句超过 200 字会被硬切） |
| 每片平均长度 | 下降 |
| 语义完整性 | 变差 —— 切片太短会丢上下文 |

改 `overlap` 再试一次，看抽屉里黄色高亮区域变大 / 变小。
这就是文章说的「片段控制在 200-500 字」到底该怎么权衡。

### 5. 切到阶段二做检索

点顶部「02 向量检索」，输入问题，回车。重点观察三件事：

**① 相似度落在什么区间**

Top1 通常 0.6 左右，第 5 条掉到 0.55。中文语义向量就在这个区间浮动，
**绝对值不代表好坏，相对排序才有意义**。

**② Top1 不一定是最能回答问题的**

向量只算「语义像不像」，分不清「讲了这个概念」和「回答了这个问题」。
实测问「大模型的幻觉问题怎么解决」：

```
#1 0.6421  「为什么这么做有效？」      ← 讲了查询重写，沾边
#2 0.6074  「使用示例」含"缓解幻觉"     ← 这才是直接答案，却排第二
#4 0.5893  「速度提升 820 倍」          ← 讲 HNSW，答非所问
```

**③ 无关内容会混进来**

相似度 0.58 那条主题沾边但没用。Top-K 里通常只有前 1-2 条真正能用。

这三点正好是后面所有优化的靶子：

| 问题 | 哪个阶段解决 |
|------|-------------|
| 关键词 / 数字类问题向量检索不上 | 阶段三 混合检索 + RRF |
| Top1 不是最该用的 | 阶段四 重排序 |
| K 条里只有 1-2 条有用 | 阶段五 上下文控制 |

### 6. 切到阶段三做混合检索

点「03 混合检索」。默认两路各召回 20 条，RRF(k=60) 融合取 Top-5。

**重点看「来源徽章」**

| 徽章 | 含义 | 典型场景 |
|------|------|---------|
| 🟢 双路 | 两路都召回 | 最可靠：语义相关 + 字面匹配 |
| 🔵 仅向量 | 只有向量召回 | 同义改写、换了个说法 |
| 🟠 仅 BM25 | 只有关键词召回 | **数字、编号、版本号、专有名词** |

**关键观察：「较向量 ↑N」**

融合结果里会标「较向量 ↑4」—— 意思是这条在向量检索里排第 6，
融合后升到第 2。这就是 BM25 把它捞了上来。

实测问「match-case 语法怎么用」：

```
#1  双路  向量#1(0.4644) + BM25#1(14.71)   「Python 3.10 在 2021 年发布，引入了 match-case 语法」
#2  双路  向量#6(0.3952) + BM25#2(11.81)   较向量 ↑4
#3  双路  向量#2(0.4368) + BM25#9(5.25)    较向量 ↓1
```

**对比阶段二**：同样的问题，纯向量检索只有 0.4644 的相似度，
现在 BM25 那一路给了 14.71 的强信号，融合后稳稳第 1。

### 7. 切到阶段四看重排

点「04 重排序」，召回 20 条 → 重排取 Top-5。**重点看「↑N / ↓N」**。

实测问「数字化交付的模型深度要求」—— 这是阶段三 RRF 失败的案例：

```
阶段三 RRF 结果              阶段四重排结果
#1 版本发布历史（不相关）  →  #4  ↓3
#3 5.1 架构图             →  #1  ↑2
#6 数据资产定义           →  #2  ↑4
#8 交付要求               →  #3  ↑5
```

原来排第一的「本文件及其所代替文件的历次版本发布情况」被压到第 4 ——
它只是字面沾了「Q/SY 01015」这个编号，并不回答任何问题。

**但也要看到重排的局限**：真正讲「模型深度要求」的那段只排到第 5。原因有两个：

1. 它在 PDF 里是**表格**（`模型交付 / 采集深度要求 采集内容 Ⅰ Ⅱ ...`），
   切片把表头和数据切开了，重排模型看到的是一堆碎片
2. rerank 对表格、列表类内容的判断，本来就弱于自然语言段落

这就是真实的检索系统：重排能显著改善，但不保证完美。
下一步「上下文控制」会从另一个角度补救 —— 与其纠结整个片段，
不如把片段里**真正相关的那几句**抽出来。

### 8. 切到阶段五做上下文压缩

点「05 上下文控制」，默认 VECTOR 策略。实测问「数字化交付的模型深度要求」：

```
输入：5 片源切片，共 2255 字、81 句
输出：20 个句子，共 640 字
保留 28.4%，token 估算 3383 → 960
```

**注意最高分那句**：

```
[0.6862]  源片#5  「模型交付 / 采集深度要求」
```

源片#5 正是阶段四里排第 5 的那个**表格切片** —— 整片排名低，但里面这一句
直接就是问题的答案。这就是句子级提取的价值：**不要因为整片排名靠后，
就丢掉它里面最有用的那句话。**

**试着切换策略对比**：把「向量」切成「关键词」再跑一次，观察三点：

1. 耗时从 ~2.6s 降到 ~1.1s
2. 选中的句子有明显差异 —— 关键词模式更容易被高频词带偏
3. 最高分通常不再是那句「模型交付 / 采集深度要求」，而是含「数字化交付」最多的一句

这就是文章说的「方案 2 更快但粗糙」，粗糙在哪儿你自己看到了。

### 9. 直接跳转

| URL | 效果 |
|-----|------|
| `/?task=<taskId>` | 回放某次入库的完整日志 |
| `/?doc=1` | 直接打开文档 1 的切片列表 |
| `/?doc=1&chunk=38` | 直接打开第 38 片的解剖抽屉 |
| `/?stage=2` | 直接进入阶段二 |
| `/?stage=2&trace=<id>` | 回放某次向量检索 |
| `/?stage=3` | 直接进入阶段三 |
| `/?stage=3&trace=<id>` | 回放某次混合检索（含两路召回明细） |
| `/?stage=4` | 直接进入阶段四 |
| `/?stage=4&trace=<id>` | 回放某次重排（含排名变化） |
| `/?stage=5` | 直接进入阶段五 |
| `/?stage=5&trace=<id>` | 回放某次上下文压缩 |
| `/?stage=6` | 直接进入阶段六（评测） |
| `/?stage=6&run=<id>` | 回放某次评测（策略对比 + 逐用例明细） |
| `/?stage=7` | 直接进入阶段七（生成） |
| `/?stage=7&trace=<id>` | 回放某次生成（答案 + 引用 + Prompt 原文） |
| `/?stage=8` | 直接进入阶段八（查询重写） |
| `/?stage=8&trace=<id>` | 回放某次重写（变体 + 前后对比 + 多路来源） |
| `/?runs=1` | 直接打开运行记录列表 |

---

## 向量化到底怎么做的

**模型**：阿里云百炼 `text-embedding-v4`（DashScope OpenAI 兼容模式），输出 **1024 维**。

**请求**（`QwenEmbeddingClient.embedBatch`）：

```http
POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
Authorization: Bearer $DASHSCOPE_API_KEY
Content-Type: application/json

{
  "model": "text-embedding-v4",
  "input": ["切片1正文", "切片2正文", ...],   // 最多 10 条
  "dimensions": 1024,
  "encoding_format": "float"
}
```

**响应**：

```json
{
  "data": [
    { "index": 0, "embedding": [0.0123, -0.0456, ...] },   // 1024 个 float
    { "index": 1, "embedding": [...] }
  ],
  "usage": { "prompt_tokens": 2406, "total_tokens": 2406 }
}
```

**三个必须处理的细节**（不做就会静默出错）：

1. **单次最多 10 条** —— 超了直接 400。代码里 `batchSize` 硬卡在 `Math.min(config, 10)`。
2. **`data` 数组不保证顺序** —— 必须按 `index` 字段回填到对应位置，否则切片 A 会拿到切片 B 的向量，
   而且**不会报错**，只是检索结果莫名其妙。这是最阴的坑。
3. **返回维度必须校验** —— 与 `dimensions` 参数、与 collection 维度三方一致，
   否则 Chroma 写入时才报错，报错信息还很难懂。

**成本参考**（实测那篇文章）：

```
39,980 字 → 清洗后 35,090 字 → 89 片
9 批 × 10 条 = 16,632 tokens，耗时 5.9 秒
```

**存到哪**：Chroma 的 `raglab_chunks` 集合，每条记录包含
`id`（`d{docId}_c{index}`）、`embedding`（1024 维）、`document`（原文）、
`metadata`（docId / docName / chunkIndex / charCount / heading）。

---

## 运行日志与查询效率

日志一直是落库的（`kb_ingest_log`），这次补的是**查看入口**和**查询效率设计**。

### 怎么查

点右上角 **⏱ 运行记录**，或直接访问 `/?runs=1`。

抽屉里把三类运行统一成一个列表：

| 类型 | 来源表 | 点进去 |
|------|--------|--------|
| 入库 | `kb_ingest_task` | 回放 12 步入库日志 |
| 向量检索 | `kb_retrieve_trace` | 回放 4 步检索日志 |
| 混合检索 | `kb_hybrid_trace` | 回放两路召回 + RRF 融合日志 |

每条显示：时间、耗时、**产生了几条日志**、**其中几条 ERROR**。
支持按类型筛选、只看失败。

### 查询效率怎么保证的

**① 三个子查询各自 LIMIT，再归并**

```sql
-- ✅ 每段走自己的时间索引，各取 50 条，归并量很小
SELECT * FROM (
  (SELECT ... FROM kb_ingest_task  ORDER BY started_at DESC LIMIT 50)
  UNION ALL
  (SELECT ... FROM kb_retrieve_trace ORDER BY created_at DESC LIMIT 50)
  UNION ALL
  (SELECT ... FROM kb_hybrid_trace   ORDER BY created_at DESC LIMIT 50)
) t ORDER BY ts DESC LIMIT 50

-- ❌ 不是这样：先 UNION 三张全表，再排序取前 50
```

**② 列表不读 `detail`**

`kb_ingest_log.detail` 是 MEDIUMTEXT，实测占了表体积的 1/3。
列表只要「几条日志、几条错误」，一条 `GROUP BY` 就够：

```sql
SELECT task_id, COUNT(*), SUM(IF(level='ERROR',1,0))
  FROM kb_ingest_log WHERE task_id IN (?,?,...) GROUP BY task_id
```

配合 `idx_log_task_level(task_id, level)` 复合索引，连回表都不用。

**③ 一次 IN 查询批量统计，不做 N+1**

50 条记录不是查 50 次，而是一条 SQL。

### 索引与实测

`SchemaMigrator` 启动时会幂等补索引（MySQL 8 不支持 `ADD INDEX IF NOT EXISTS`）：

| 索引 | 用在哪 |
|------|--------|
| `idx_log_task (task_id, seq)` | 按任务回放日志 |
| `idx_log_task_level (task_id, level)` | 列表的日志统计（覆盖索引） |
| `idx_log_created (created_at)` | 按时间清理旧日志 |
| `idx_log_level_time (level, created_at)` | 只看 ERROR |

**5 万行压测结果**（`EXPLAIN ANALYZE` 实测）：

```
按时间清理：WHERE created_at < ? LIMIT 1000
→ Covering index range scan on idx_log_created
→ 取 1000 行 0.357ms（无索引要扫完 5 万行）

按任务回放：WHERE task_id = ? ORDER BY seq
→ Index lookup on idx_log_task
→ 39 行 0.109ms
```

有意思的是「取最近 50 条」这个查询，加 `created_at` 索引后优化器**仍然选全表扫** ——
因为 5 万行对它来说太小，且要回表取 6 个列，它算下来觉得顺序扫更便宜。
所以「取最新 N 条」不要迷信索引，走主键倒序（`ORDER BY id DESC`）往往更快。

### 保留策略

```yaml
raglab:
  log:
    retention-days: 30    # <=0 表示永久保留
```

每天 03:30 自动清理，分批删除（每批 1000 行，避免长事务锁表）。
这是**从根上控制表规模**的办法 —— 比任何查询优化都管用。

> 日志表涨到百万行以上时，`RunController` 里的 `COUNT(*)` 会成为瓶颈，
> 那时应改成 `information_schema` 估算值或加缓存。但在 30 天保留期下很难到这个量级。

---

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 三组件连通性（MySQL / Chroma / Qwen） |
| POST | `/api/health/qwen-probe` | 真调一次 Qwen，验证 Key + 维度 |
| POST | `/api/ingest/file` | 文件入库（multipart，可选 chunkSize/chunkOverlap） |
| POST | `/api/ingest/text` | 文本入库 |
| GET | `/api/ingest/tasks/{id}/stream` | **SSE 日志流**（自动回放） |
| GET | `/api/ingest/tasks/{id}` | 任务状态 |
| GET | `/api/ingest/docs` | 文档列表 |
| GET | `/api/ingest/docs/{id}/chunks` | 切片列表 |
| GET | `/api/ingest/docs/{id}/chunks/{index}` | **单片解剖**：完整正文 + overlap + Chroma 真实向量 |
| POST | `/api/retrieve` | 提交向量检索 `{query, topK}` |
| GET | `/api/retrieve/{traceId}/stream` | **检索过程 SSE 日志流** |
| GET | `/api/retrieve/{traceId}` | 轨迹 + 命中明细 |
| GET | `/api/retrieve/recent` | 最近检索记录 |
| POST | `/api/hybrid` | 混合检索 `{query, vecTopN, bm25TopN, finalTopK, rrfK}` |
| GET | `/api/hybrid/{traceId}/stream` | **混合检索 SSE 日志流** |
| GET | `/api/hybrid/{traceId}` | 两路召回明细 + 融合结果 |
| GET | `/api/hybrid/recent` | 最近混合检索记录 |
| POST | `/api/rerank` | 重排序 `{query, recallTopN, finalTopK}` |
| GET | `/api/rerank/{traceId}/stream` | **重排过程 SSE 日志流** |
| GET | `/api/rerank/{traceId}` | 召回排名 + 重排分 + 排名变化 |
| GET | `/api/rerank/recent` | 最近重排记录 |
| POST | `/api/context` | 上下文压缩 `{query, recallTopN, topK, strategy, maxChars}` |
| GET | `/api/context/{traceId}/stream` | **压缩过程 SSE 日志流** |
| GET | `/api/context/{traceId}` | 源切片 + 选中句子 + 最终上下文 |
| GET | `/api/context/recent` | 最近压缩记录 |
| POST | `/api/eval/run` | 跑评测 `{strategies, topK, recallTopN, caseIds?}` |
| GET | `/api/eval/{runId}/stream` | **评测过程 SSE 日志流** |
| GET | `/api/eval/{runId}` | 策略汇总 + 逐用例明细 |
| GET | `/api/eval/cases` | 测试集列表 |
| POST | `/api/eval/cases` | 新增用例 `{name, query, expectText, difficulty}` |
| POST | `/api/eval/cases/{id}/toggle` | 启用/停用用例 |
| DELETE | `/api/eval/cases/{id}` | 删除用例 |
| POST | `/api/generate` | 生成答案 `{query, topK, recallTopN}` |
| GET | `/api/generate/{traceId}/stream` | **生成过程 SSE 日志流** |
| GET | `/api/generate/{traceId}` | 答案 + 引用依据 + Prompt 原文 + token |
| GET | `/api/generate/recent` | 最近生成记录 |
| POST | `/api/rewrite` | 查询重写 `{query, variants, topK, recallN, rerank}` |
| GET | `/api/rewrite/{traceId}/stream` | **重写过程 SSE 日志流** |
| GET | `/api/rewrite/{traceId}` | 变体 + 每路召回 + 融合明细 + 前后对比 |
| GET | `/api/rewrite/recent` | 最近重写记录 |
| GET | `/api/runs` | **运行记录列表** `?limit=50&type=ALL&onlyFailed=false` |

---

## 常见问题

**Q：日志窗口一直「重连中」**
A：后端没起来或端口不对。先访问 `/api/health` 看三盏灯。

**Q：健康检查 Chroma 红**
A：`docker ps` 看容器映射的宿主机端口，改 `raglab.chroma.base-url`。
Chroma 1.x 的接口路径是 `/api/v2/...`，不是 `/api/v1`。

**Q：Qwen 报 400 / 大批量失败**
A：`text-embedding` 系列单次最多 10 条，`raglab.embedding.batch-size` 不要超过 10。

**Q：换了向量模型后写入报错**
A：collection 的维度在创建时就固定了。换模型要给 `raglab.chroma.collection` 换新名字，
或删掉旧集合重来。

**Q：PDF 解析出 0 字符**
A：扫描件（图片）没有文字层，需要先 OCR。日志里 PARSE 步骤会明确提示。

**Q：切片数量太多 / 太少**
A：页面上调 `slice size` 和 `overlap` 重新入库，日志会告诉你平均长度和硬切次数。
硬切次数 > 0 说明 size 小于句子长度，读起来会断句。

---

## 下一步

「检索 → 精排 → 压缩 → 生成」主链路已经闭环，评测也能量化每一步的效果。
剩下三个方向，按「能带来多少新认知」排序：

**A. 多层缓存（阶段九）**

文章里的优化 5，也是现在唯一还没做的。同一类问题反复问时，
把「问题向量 → 答案」缓存起来，相似度超阈值直接返回，省掉检索和生成两次调用。
关键设计是**阈值必须被评测验证**：太低会返回错答案，太高等于没缓存。

**B. 流式生成**

现在 `/api/generate` 是等模型全部说完再返回。改成 SSE 逐字吐，
体感和现在这套日志推送是同一个机制（`SseEmitter` + 事件），实现不难，
但页面上「答案一个字一个字长出来」的体验差别很大。

**C. 换一套更「口语」的语料再试改写**

阶段八的结论只对**这套语料**成立。真正想验证改写，需要一批「用户真实提问」
（口音重、缩写多、业务黑话），以及足够大的知识库。同一个机制在不同数据上结论可以相反 ——
这也是评测集的价值边界：**它证明的是「在这批数据上」，不是「永远如此」。**

如果继续按阶段推进，建议顺序是 **A（省钱效果最直接）→ B（体验）→ C（补实验）**。

只有这一步做完，前面所有优化才算「可验证」而不是「感觉变好了」。
