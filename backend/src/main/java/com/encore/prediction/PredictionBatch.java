package com.encore.prediction;

import com.encore.batch.CollectionCounts;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.batch.JobType;
import com.encore.common.KoreaTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 예측 배치 오케스트레이터. 이벤트마다 collection_log(PREDICT) 1건을 남기고,
 * 한 이벤트의 실패는 다음 이벤트로 번지지 않는다(수집 배치와 같은 규약).
 * counts 의미: fetched = 표본 공연 수, updated = 저장한 예측 곡 수.
 */
@Component
public class PredictionBatch {

    private static final Logger log = LoggerFactory.getLogger(PredictionBatch.class);

    private final TargetEventRepository targetEventRepository;
    private final CollectionLogRepository collectionLogRepository;
    private final PredictionGenerator generator;
    private final TrendSummarizer trendSummarizer;

    public PredictionBatch(TargetEventRepository targetEventRepository,
                           CollectionLogRepository collectionLogRepository,
                           PredictionGenerator generator,
                           TrendSummarizer trendSummarizer) {
        this.targetEventRepository = targetEventRepository;
        this.collectionLogRepository = collectionLogRepository;
        this.generator = generator;
        this.trendSummarizer = trendSummarizer;
    }

    /** 아직 열리지 않은(당일 포함) 이벤트 전체를 재계산한다. */
    public List<CollectionLog> predictUpcoming() {
        List<TargetEvent> events = targetEventRepository.findByEventDateGreaterThanEqual(KoreaTime.today());
        log.info("예측 배치 시작 — 대상 이벤트 {}건", events.size());
        List<CollectionLog> results = new ArrayList<>();
        for (TargetEvent event : events) {
            CollectionLog result = predictOne(event);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private CollectionLog predictOne(TargetEvent event) {
        Instant startedAt = Instant.now();
        try {
            PredictionGenerator.Summary summary = generator.predict(event.getId());
            // 변화 요약(E4)은 재계산 직후에만 갱신 — 실패해도 예외를 던지지 않는 계약
            trendSummarizer.update(event.getId());
            log.info("{} 예측 완료 — 표본 {}회, {}곡", event.getEventName(),
                    summary.sampleSize(), summary.savedPredictions());
            return collectionLogRepository.save(CollectionLog.success(
                    JobType.PREDICT, event.getArtist().getMbid(),
                    CollectionCounts.builder()
                            .fetched(summary.sampleSize())
                            .updated(summary.savedPredictions())
                            .build(),
                    startedAt));
        } catch (RuntimeException e) {
            log.error("{} 예측 실패 — 다음 이벤트로 계속", event.getEventName(), e);
            return saveFailedQuietly(event, e, startedAt);
        }
    }

    /** FAILED 기록 자체가 실패해도 순회는 계속돼야 한다 — 수집 배치와 같은 규약. */
    private CollectionLog saveFailedQuietly(TargetEvent event, RuntimeException cause, Instant startedAt) {
        try {
            return collectionLogRepository.save(CollectionLog.failed(
                    JobType.PREDICT, event.getArtist().getMbid(), cause.getMessage(), startedAt));
        } catch (RuntimeException logFailure) {
            log.error("{} collection_log 기록도 실패", event.getEventName(), logFailure);
            return null;
        }
    }
}
