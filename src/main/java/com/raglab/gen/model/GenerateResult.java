package com.raglab.gen.model;

import com.raglab.retrieval.model.ContextResult;

import java.util.List;

/**
 * 生成结果（阶段七）。
 *
 * 除了 answer，这里刻意保留了另外三样东西 —— 它们才是「RAG 与直接问大模型」的区别：
 *   sentences  依据的句子（带编号），答案里的 [n] 就是指向它
 *   sources    这些句子来自哪几片，能一路跳回原文和向量
 *   prompts    真发给模型的原文，出问题时第一眼就能看出是检索的锅还是提示词的锅
 */
public record GenerateResult(
        String answer,
        String model,
        String systemPrompt,
        String userPrompt,
        List<ContextResult.Sentence> sentences,
        List<ContextResult.SourceChunk> sources,
        ContextResult.Stats stats,
        List<Integer> cited,
        int promptTokens,
        int completionTokens,
        String finishReason
) {
}
