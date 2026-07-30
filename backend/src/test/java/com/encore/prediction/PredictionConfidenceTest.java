package com.encore.prediction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 신뢰도 라벨 경계값 — 규칙 상수가 바뀌면 이 테스트도 의도적으로 함께 바뀌어야 한다. */
class PredictionConfidenceTest {

    @Test
    void smallSampleIsAlwaysLow() {
        // MIN_SAMPLE(8) 미만이면 확률이 아무리 높아도 LOW
        assertThat(PredictionConfidence.of(7, 1.0)).isEqualTo(PredictionConfidence.LOW);
        assertThat(PredictionConfidence.of(0, 0.95)).isEqualTo(PredictionConfidence.LOW);
    }

    @Test
    void veryHighNeedsBothProbabilityAndSample() {
        assertThat(PredictionConfidence.of(15, 0.9)).isEqualTo(PredictionConfidence.VERY_HIGH);
        // 표본이 VERY_HIGH_SAMPLE(15) 미만이면 확률 0.9여도 HIGH로 강등
        assertThat(PredictionConfidence.of(14, 0.9)).isEqualTo(PredictionConfidence.HIGH);
        // 확률이 0.9 미만이면 표본이 커도 HIGH
        assertThat(PredictionConfidence.of(20, 0.89)).isEqualTo(PredictionConfidence.HIGH);
    }

    @Test
    void midAndLowProbabilityBands() {
        assertThat(PredictionConfidence.of(20, 0.7)).isEqualTo(PredictionConfidence.HIGH);
        assertThat(PredictionConfidence.of(20, 0.69)).isEqualTo(PredictionConfidence.MEDIUM);
        assertThat(PredictionConfidence.of(20, 0.4)).isEqualTo(PredictionConfidence.MEDIUM);
        assertThat(PredictionConfidence.of(20, 0.39)).isEqualTo(PredictionConfidence.LOW);
    }
}
