package com.encore.prediction;

import com.encore.prediction.TrendChanges.Changes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 예측 변화 LLM 요약(E4 요약부) — 예측 재계산 시에만 갱신하는 캐시(target_event.trend_summary).
 * 조회당 LLM 호출은 없다. 변화 곡이 없으면 LLM을 부르지 않고 요약을 지운다(화면 미표시).
 */
@Service
public class TrendSummaryService implements TrendSummarizer {

    private static final Logger log = LoggerFactory.getLogger(TrendSummaryService.class);

    private final TargetEventRepository targetEventRepository;
    private final PredictionRepository predictionRepository;
    private final ChatClient chatClient;

    public TrendSummaryService(TargetEventRepository targetEventRepository,
                               PredictionRepository predictionRepository,
                               ChatClient.Builder chatClientBuilder) {
        this.targetEventRepository = targetEventRepository;
        this.predictionRepository = predictionRepository;
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    @Transactional
    public void update(Long targetEventId) {
        try {
            TargetEvent event = targetEventRepository.findById(targetEventId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "존재하지 않는 예측 대상: " + targetEventId));
            List<Prediction> predictions =
                    predictionRepository.findByTargetEvent_IdOrderByRankAsc(targetEventId);
            Changes changes = TrendChanges.from(predictions);
            if (changes.isEmpty()) {
                event.updateTrendSummary(null, null);
                return;
            }
            String summary = chatClient.prompt()
                    .system(TrendSummaryPrompts.system())
                    .user(TrendSummaryPrompts.user(event.getArtist().getName(), changes))
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                log.warn("빈 변화 요약은 저장하지 않음: event={}", targetEventId);
                return;
            }
            event.updateTrendSummary(summary.strip(), Instant.now());
        } catch (RuntimeException e) {
            // 요약은 부가 기능 — 예측 배치가 이것 때문에 FAILED로 남으면 안 된다
            log.warn("변화 요약 갱신 실패(예측 결과에는 영향 없음): event={}", targetEventId, e);
        }
    }
}
