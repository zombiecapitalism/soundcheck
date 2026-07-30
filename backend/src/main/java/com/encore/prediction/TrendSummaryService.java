package com.encore.prediction;

import com.encore.llm.LlmCallRecorder;
import com.encore.llm.LlmCallType;
import com.encore.prediction.TrendChanges.Changes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 예측 변화 LLM 요약(E4 요약부) — 예측 재계산 시에만 갱신하는 캐시(target_event.trend_summary).
 * 조회당 LLM 호출은 없다. 변화 곡이 없으면 LLM을 부르지 않고 요약을 지운다(화면 미표시).
 * <p>
 * 의도적으로 트랜잭션이 없다: 수 초짜리 LLM 호출이 DB 커넥션을 점유하면 안 되고,
 * 외부 트랜잭션에 계측 저장이 참여하면 실패 시 rollback-only가 새어나가 "요약 실패가
 * 예측 배치를 실패시키면 안 된다"는 계약이 깨진다. 조회·저장은 각각 리포지토리의
 * 짧은 자체 트랜잭션(findByIdWithArtist / updateTrendSummary 벌크 UPDATE)으로 끝낸다.
 */
@Service
public class TrendSummaryService implements TrendSummarizer {

    private static final Logger log = LoggerFactory.getLogger(TrendSummaryService.class);

    private final TargetEventRepository targetEventRepository;
    private final PredictionRepository predictionRepository;
    private final ChatClient chatClient;
    private final LlmCallRecorder llmCallRecorder;

    public TrendSummaryService(TargetEventRepository targetEventRepository,
                               PredictionRepository predictionRepository,
                               ChatClient.Builder chatClientBuilder,
                               LlmCallRecorder llmCallRecorder) {
        this.targetEventRepository = targetEventRepository;
        this.predictionRepository = predictionRepository;
        this.chatClient = chatClientBuilder.build();
        this.llmCallRecorder = llmCallRecorder;
    }

    @Override
    public void update(Long targetEventId) {
        try {
            // 아티스트 이름을 트랜잭션 밖에서 쓰므로 fetch join 필수(지연 로딩 불가)
            TargetEvent event = targetEventRepository.findByIdWithArtist(targetEventId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "존재하지 않는 예측 대상: " + targetEventId));
            List<Prediction> predictions =
                    predictionRepository.findByTargetEvent_IdOrderByRankAsc(targetEventId);
            Changes changes = TrendChanges.from(predictions);
            if (changes.isEmpty()) {
                // 변화가 없어졌으면 낡은 요약이 남지 않게 지운다 — LLM 호출 없음
                targetEventRepository.updateTrendSummary(targetEventId, null, null);
                return;
            }
            long start = System.currentTimeMillis();
            ChatResponse response = chatClient.prompt()
                    .system(TrendSummaryPrompts.system())
                    .user(TrendSummaryPrompts.user(event.getArtist().getName(), changes))
                    .call()
                    .chatResponse();
            // 동기 호출이라 usage 메타데이터가 있다 — 토큰까지 계측(E9)
            llmCallRecorder.recordUsage(LlmCallType.TREND_SUMMARY,
                    response != null && response.getMetadata() != null
                            ? response.getMetadata().getModel() : null,
                    response != null && response.getMetadata() != null
                            ? response.getMetadata().getUsage() : null,
                    System.currentTimeMillis() - start);
            String summary = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText()
                    : null;
            if (summary == null || summary.isBlank()) {
                log.warn("빈 변화 요약은 저장하지 않음: event={}", targetEventId);
                return;
            }
            targetEventRepository.updateTrendSummary(targetEventId, summary.strip(), Instant.now());
        } catch (RuntimeException e) {
            // 요약은 부가 기능 — 예측 배치가 이것 때문에 FAILED로 남으면 안 된다
            log.warn("변화 요약 갱신 실패(예측 결과에는 영향 없음): event={}", targetEventId, e);
        }
    }
}
