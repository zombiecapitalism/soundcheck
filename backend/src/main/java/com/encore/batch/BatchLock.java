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
}
