package com.encore.llm;

/** 예상 비용 계산 — 순수 함수. 임베딩은 입력 토큰만 과금된다. */
public final class LlmCosts {

    private LlmCosts() {
    }

    public static double estimateUsd(long chatInputTokens, long chatOutputTokens,
                                     long embeddingTokens, LlmCostProperties cost) {
        return chatInputTokens / 1_000_000.0 * cost.inputUsdPer1m()
                + chatOutputTokens / 1_000_000.0 * cost.outputUsdPer1m()
                + embeddingTokens / 1_000_000.0 * cost.embeddingUsdPer1m();
    }
}
