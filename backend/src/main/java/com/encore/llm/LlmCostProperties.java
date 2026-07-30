package com.encore.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모델 단가(USD / 1M 토큰) — 대시보드 예상 비용 계산용. 모델 교체 시 설정만 바꾼다.
 * 기본값은 gpt-4o-mini / text-embedding-3-small 공시가 기준.
 */
@ConfigurationProperties("encore.llm.cost")
public record LlmCostProperties(
        double inputUsdPer1m,
        double outputUsdPer1m,
        double embeddingUsdPer1m
) {

    public LlmCostProperties {
        if (inputUsdPer1m < 0 || outputUsdPer1m < 0 || embeddingUsdPer1m < 0) {
            throw new IllegalArgumentException("단가는 음수일 수 없습니다");
        }
    }
}
