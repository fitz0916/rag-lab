package com.raglab.common;

/**
 * 外部依赖（Qwen / Chroma）调用失败。显式带上 target，方便 ErrorDiagnoser 给出针对性建议。
 */
public class ExternalServiceException extends RuntimeException {

    private final String target;

    public ExternalServiceException(String target, String message, Throwable cause) {
        super(message, cause);
        this.target = target;
    }

    public String getTarget() {
        return target;
    }
}
