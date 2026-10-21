package com.raglab.ingest.service;

/**
 * 清洗结果：除了正文，还要把「变化了多少」讲清楚，否则日志里看不出清洗到底干了什么。
 */
public record CleanResult(String text, int beforeChars, int afterChars, int removedLines) {

    public int removedChars() {
        return beforeChars - afterChars;
    }

    public double removedRatio() {
        return beforeChars == 0 ? 0 : (double) removedChars() / beforeChars * 100;
    }
}
