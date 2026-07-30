package com.encore.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/** 비용 계산 순수 함수 — 단가는 설정값이며 여기서는 계산식만 검증한다. */
class LlmCostsTest {

    private static final LlmCostProperties COST = new LlmCostProperties(0.15, 0.60, 0.02);

    @Test
    void estimatesCostPerMillionTokens() {
        // 입력 1M → $0.15, 출력 1M → $0.60, 임베딩 1M → $0.02
        assertThat(LlmCosts.estimateUsd(1_000_000, 0, 0, COST)).isCloseTo(0.15, offset(1e-9));
        assertThat(LlmCosts.estimateUsd(0, 1_000_000, 0, COST)).isCloseTo(0.60, offset(1e-9));
        assertThat(LlmCosts.estimateUsd(0, 0, 1_000_000, COST)).isCloseTo(0.02, offset(1e-9));
        assertThat(LlmCosts.estimateUsd(500_000, 100_000, 2_000_000, COST))
                .isCloseTo(0.075 + 0.06 + 0.04, offset(1e-9));
    }

    @Test
    void zeroTokensCostNothing() {
        assertThat(LlmCosts.estimateUsd(0, 0, 0, COST)).isZero();
    }

    @Test
    void rejectsNegativePrices() {
        assertThatThrownBy(() -> new LlmCostProperties(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
