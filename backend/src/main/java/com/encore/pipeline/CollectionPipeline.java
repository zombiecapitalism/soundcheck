package com.encore.pipeline;

import com.encore.batch.BatchLock;
import com.encore.batch.CollectionLog;
import com.encore.prediction.AccuracyService;
import com.encore.prediction.PredictionBatch;
import com.encore.setlist.SetlistCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 일일 배치 파이프라인: 수집 → 적중률 매칭 → 예측 재계산.
 * 수집만 하고 끝나면 데이터는 새것인데 예측은 어제 것으로 남는다 — 수집이 끝나면
 * 항상 예측까지 이어져야 서비스가 방치해도 스스로 돈다. 스케줄러와 관리자 트리거가
 * 같은 흐름을 공유한다(setlist↔prediction 순환을 피하려고 별도 패키지에 둔다).
 */
@Component
public class CollectionPipeline {

    private static final Logger log = LoggerFactory.getLogger(CollectionPipeline.class);

    private final SetlistCollector collector;
    private final PredictionBatch predictionBatch;
    private final AccuracyService accuracyService;
    private final BatchLock batchLock;
    private final AsyncTaskExecutor taskExecutor;

    public CollectionPipeline(SetlistCollector collector, PredictionBatch predictionBatch,
                              AccuracyService accuracyService, BatchLock batchLock,
                              @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.collector = collector;
        this.predictionBatch = predictionBatch;
        this.accuracyService = accuracyService;
        this.batchLock = batchLock;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 수집 파이프라인 시작을 시도한다(백그라운드 — rate limit 대기 때문에 오래 걸린다).
     * 이미 실행 중이면 false. (@Async는 자기 호출에서 프록시를 타지 않으므로 직접 제출한다.)
     */
    public boolean tryStartCollection() {
        if (!batchLock.tryAcquireCollect()) {
            return false;
        }
        try {
            taskExecutor.submit(this::runCollectionThenPredict);
        } catch (RuntimeException e) {
            // 제출 자체가 실패하면(executor 종료 등) 락이 영구히 잠기므로 반드시 되돌린다
            batchLock.releaseCollect();
            throw e;
        }
        return true;
    }

    public boolean isCollecting() {
        return batchLock.isCollecting();
    }

    /** 지난 이벤트의 실제 셋리스트 매칭(적중률 정답 채우기) 후 다가오는 이벤트를 재계산한다. */
    public List<CollectionLog> matchAndPredict() {
        int matched = accuracyService.matchPastEvents();
        if (matched > 0) {
            log.info("적중률 검증용 실제 셋리스트 {}건 연결", matched);
        }
        return predictionBatch.predictUpcoming();
    }

    private void runCollectionThenPredict() {
        try {
            collector.collectAll();
            matchAndPredict();
        } catch (RuntimeException e) {
            log.error("수집 파이프라인 실패", e);
        } finally {
            batchLock.releaseCollect();
        }
    }
}
