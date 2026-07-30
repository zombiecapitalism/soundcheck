package com.encore.prediction;

/**
 * 예측 재계산 직후 변화 요약을 갱신하는 훅.
 * 인터페이스로 분리한 이유: PredictionBatch를 ChatClient 없는 슬라이스 테스트(@DataJpaTest)에서
 * no-op 구현으로 조립할 수 있어야 한다.
 */
public interface TrendSummarizer {

    /** 실패해도 예외를 던지지 않는다 — 요약은 부가 기능이라 예측 배치를 실패시키면 안 된다. */
    void update(Long targetEventId);
}
