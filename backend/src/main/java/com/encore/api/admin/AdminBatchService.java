package com.encore.api.admin;

import com.encore.batch.BatchLock;
import com.encore.batch.CollectionLog;
import com.encore.prediction.AccuracyService;
import com.encore.prediction.PredictionBatch;
import com.encore.setlist.SetlistCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 관리자 수동 배치 트리거. 수집은 rate limit 준수 대기 때문에 오래 걸리므로
 * 백그라운드로 돌리고, 진행 상황은 collection_log 대시보드로 확인한다.
 * (@Async는 자기 호출에서 프록시를 타지 않으므로 TaskExecutor에 직접 제출한다.)
 * 예측은 조회·계산뿐이라 동기로 즉시 결과를 돌려준다.
 */
@Service
public class AdminBatchService {

    private static final Logger log = LoggerFactory.getLogger(AdminBatchService.class);

    private final SetlistCollector collector;
    private final PredictionBatch predictionBatch;
    private final AccuracyService accuracyService;
    private final BatchLock batchLock;
    private final AsyncTaskExecutor taskExecutor;

    public AdminBatchService(SetlistCollector collector, PredictionBatch predictionBatch,
                             AccuracyService accuracyService, BatchLock batchLock,
                             @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.collector = collector;
        this.predictionBatch = predictionBatch;
        this.accuracyService = accuracyService;
        this.batchLock = batchLock;
        this.taskExecutor = taskExecutor;
    }

    /** 수집 시작을 시도한다. 이미 실행 중이면 false — 호출자는 409로 응답한다. */
    public boolean tryStartCollection() {
        if (!batchLock.tryAcquireCollect()) {
            return false;
        }
        taskExecutor.submit(this::runCollection);
        return true;
    }

    private void runCollection() {
        try {
            collector.collectAll();
        } catch (RuntimeException e) {
            log.error("관리자 트리거 수집 실패", e);
        } finally {
            batchLock.releaseCollect();
        }
    }

    public boolean isCollecting() {
        return batchLock.isCollecting();
    }

    /** 지난 이벤트의 실제 셋리스트 매칭(적중률 정답 채우기) 후 다가오는 이벤트를 재계산한다. */
    public List<CollectionLog> predictNow() {
        int matched = accuracyService.matchPastEvents();
        if (matched > 0) {
            log.info("적중률 검증용 실제 셋리스트 {}건 연결", matched);
        }
        return predictionBatch.predictUpcoming();
    }
}
