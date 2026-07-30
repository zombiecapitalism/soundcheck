package com.encore.prediction;

/**
 * 예측 신뢰도 라벨 — "이 확률을 예습 결정에 얼마나 믿어도 되나"를 표본 크기와 확률로 요약한다.
 * 규칙은 상수로 고정하고 경계값을 단위 테스트로 못박는다(E1).
 */
public enum PredictionConfidence {
    VERY_HIGH, HIGH, MEDIUM, LOW;

    /** 이 미만이면 확률과 무관하게 LOW — 표본이 결론을 지탱하지 못한다. */
    static final int MIN_SAMPLE = 8;
    static final int VERY_HIGH_SAMPLE = 15;
    static final double VERY_HIGH_PROBABILITY = 0.9;
    static final double HIGH_PROBABILITY = 0.7;
    static final double MEDIUM_PROBABILITY = 0.4;

    public static PredictionConfidence of(int sampleSize, double probability) {
        if (sampleSize < MIN_SAMPLE) {
            return LOW;
        }
        if (probability >= VERY_HIGH_PROBABILITY && sampleSize >= VERY_HIGH_SAMPLE) {
            return VERY_HIGH;
        }
        if (probability >= HIGH_PROBABILITY) {
            return HIGH;
        }
        if (probability >= MEDIUM_PROBABILITY) {
            return MEDIUM;
        }
        return LOW;
    }
}
