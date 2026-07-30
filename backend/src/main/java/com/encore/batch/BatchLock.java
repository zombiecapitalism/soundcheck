package com.encore.batch;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 수집 배치의 동시 실행 방지. 스케줄러와 관리자 수동 트리거가 겹칠 수 있다 —
 * 겹쳐도 versionId 스킵 덕에 데이터는 안전하지만, rate limit 소모와 로그 중복이 낭비다.
 * 단일 인스턴스 전제(PRD: 클라우드 1대)라 프로세스 내 락으로 충분하다.
 */
@Component
public class BatchLock {

    private final AtomicBoolean collecting = new AtomicBoolean(false);
    private final AtomicBoolean ragIngesting = new AtomicBoolean(false);

    /** 획득 성공 시 true. 성공했다면 반드시 finally에서 releaseCollect를 호출할 것. */
    public boolean tryAcquireCollect() {
        return collecting.compareAndSet(false, true);
    }

    public void releaseCollect() {
        collecting.set(false);
    }

    public boolean isCollecting() {
        return collecting.get();
    }

    private final AtomicBoolean predicting = new AtomicBoolean(false);

    /**
     * 예측 재계산 락 — 수집 파이프라인 말미의 예측과 관리자 수동 재계산이 겹치면
     * 같은 이벤트의 DELETE+INSERT가 경쟁해 유니크 위반(가짜 FAILED 로그)이 난다.
     */
    public boolean tryAcquirePredict() {
        return predicting.compareAndSet(false, true);
    }

    public void releasePredict() {
        predicting.set(false);
    }

    /** RAG 문서 수집(임베딩) — 수집 락과 독립이다. 외부 API·비용 소모가 커서 중복 실행만 막는다. */
    public boolean tryAcquireRagIngest() {
        return ragIngesting.compareAndSet(false, true);
    }

    public void releaseRagIngest() {
        ragIngesting.set(false);
    }

    public boolean isRagIngesting() {
        return ragIngesting.get();
    }
}
