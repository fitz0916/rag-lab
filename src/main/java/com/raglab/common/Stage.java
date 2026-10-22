package com.raglab.common;

/**
 * 入库流水线的步骤编码。
 * 前端右侧日志窗口按这个顺序展示，一眼能看出卡在哪一步。
 */
public enum Stage {

    RECEIVE("接收文档", "拿到原始文件/文本，校验大小与类型"),
    HASH("内容指纹", "SHA-256 计算，用于秒传与去重"),
    SAVE_DOC("落库(元数据)", "写入 kb_document，任务从这里开始有痕迹"),
    PARSE("解析正文", "PDF/Word/Markdown → 纯文本"),
    CLEAN("清洗文本", "统一换行、压缩空行、去掉控制字符"),
    SPLIT("智能切片", "标题 → 段落 → 句子 → 硬切，带 overlap"),
    CHROMA_INIT("准备向量集合", "get_or_create collection，校验维度与距离度量"),
    EMBED("向量化", "调用 Qwen text-embedding 批量转向量"),
    CHROMA_WRITE("写入向量库", "分批 add 到 Chroma，回写 chroma_id"),
    VERIFY("结果校验", "比对 collection 条数与切片数"),
    DONE("完成", "更新文档状态为 DONE"),

    // ============ 阶段二：向量检索 ============
    QUERY_RECEIVE("接收问题", "校验问题文本，确定 Top-K"),
    QUERY_EMBED("问题向量化", "用同一个 embedding 模型把问题变成向量 —— 和文档必须同模型，否则维度/语义空间对不上"),
    VECTOR_SEARCH("向量检索", "在 Chroma 里做最近邻搜索（cosine 空间）"),
    SCORE_RANK("打分排序", "余弦距离转相似度：similarity = 1 - distance"),
    HIT_LIST("命中结果", "逐条列出 Top-K，这就是后面要做优化的 baseline"),
    QUERY_DONE("完成", "把轨迹写入 kb_retrieve_trace"),

    // ============ 阶段三：混合检索 + RRF ============
    HYBRID_RECEIVE("接收问题", "校验问题，确定两路各自的候选数"),
    VEC_RECALL("向量召回", "语义相近的一路 —— 擅长「意思差不多」的匹配"),
    BM25_RECALL("关键词召回", "字面匹配的一路 —— 擅长数字、版本号、专有名词"),
    RRF_FUSE("RRF 融合", "不看分数只看排名：score = Σ 1/(k + rank)"),
    HYBRID_RESULT("融合结果", "按 RRF 分数重排，输出最终 Top-K"),
    HYBRID_DONE("完成", "把两路明细与融合结果写入 kb_hybrid_trace"),

    // ============ 阶段四：重排序 Rerank ============
    RERANK_RECALL("混合召回", "向量 + BM25 + RRF 先粗筛出候选集 —— 召回阶段宁可多，别漏"),
    RERANK_SCORE("重排序打分", "交叉编码器把 (query, doc) 拼在一起过模型，逐条打相关性分"),
    RERANK_REORDER("按分重排", "用重排分取代 RRF 分重新排序，这是精排"),
    RERANK_RESULT("重排结果", "输出 Top-K，并标注每条在召回时的原始排名"),
    RERANK_DONE("完成", "写入 kb_rerank_trace"),

    // ============ 阶段五：上下文控制 ============
    CONTEXT_RECALL("召回+重排", "先拿到最相关的 K 片 —— 复用阶段四的两段式检索"),
    CONTEXT_SPLIT("拆句", "把每个切片拆成句子 —— 句子才是检索的最小语义单位"),
    CONTEXT_SCORE("句子打分", "每句单独和问题算相似度（向量 or 关键词密度）"),
    CONTEXT_SELECT("选句去重", "选最相关的 N 句；相似度过高的只留一条（overlap 会造成重复）"),
    CONTEXT_BUILD("组装上下文", "按原文顺序拼接，按字数上限截断 —— 顺序乱了读起来就不通顺"),
    CONTEXT_DONE("完成", "写入 kb_context_trace"),

    // ============ 阶段六：评测 ============
    EVAL_LOAD("载入测试集", "从 kb_eval_case 读出问题与期望关键词组"),
    EVAL_CASE("逐用例检索", "同一个问题分别跑多种策略 —— 只有同题对比才说明问题"),
    EVAL_SCORE("计算指标", "Hit@1 / Hit@3 / Hit@K、MRR、关键词覆盖率"),
    EVAL_AGGREGATE("汇总对比", "按策略聚合，看谁在哪类问题上更强、代价多少"),
    EVAL_DONE("完成", "写入 kb_eval_run / kb_eval_detail"),

    // ============ 阶段七：生成 ============
    GEN_CONTEXT("准备上下文", "复用阶段四/五：召回 + 重排 + 句子级压缩，得到可直接拼进 Prompt 的内容"),
    GEN_PROMPT("拼装 Prompt", "系统提示 + 带编号的参考资料 + 问题 —— 编号是为了让模型能引用"),
    GEN_CALL("调用大模型", "qwen-plus 生成答案，记录真实 prompt/completion token"),
    GEN_CITE("标注依据", "把答案里的 [n] 对回具体句子，没引用到的不算依据"),
    GEN_DONE("完成", "写入 kb_generate_trace"),

    // ============ 阶段八：查询重写 ============
    REWRITE_GEN("生成变体", "调大模型把问题改写成多个表达，覆盖「瞎编→幻觉」这类用词差异"),
    REWRITE_RECALL("多变体召回", "每个变体各跑一遍混合检索 —— 原查询始终保留，改写只是补充"),
    REWRITE_MERGE("跨变体融合", "RRF 再看一次排名：被多个变体同时命中的，说明不是偶然"),
    REWRITE_RERank("融合后重排", "可选：把融合结果再交给交叉编码器精排，压掉「多路都沾边但都不准」的"),
    REWRITE_DONE("完成", "写入 kb_rewrite_trace"),

    // ============ 阶段九：多层缓存 ============
    CACHE_LOOKUP("查缓存", "先问三层缓存：命中就不检索、不调模型 —— 5 秒变 5 毫秒"),
    CACHE_STORE("写缓存", "按「问题 + 参数」存进 L1/L2/L3，并设置 TTL");

    private final String label;
    private final String description;

    Stage(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
