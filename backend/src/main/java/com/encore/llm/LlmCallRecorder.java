package com.encore.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 호출 기록(E9) — 계측 실패가 원 기능을 깨면 안 되므로 절대 예외를 던지지 않는다.
 * 호출 지점: 곡 설명(EXPLANATION, 캐시 히트 포함), 변화 요약(TREND_SUMMARY),
 * Chat(E8), 임베딩(EMBEDDING — 문서 적재·질의).
 */
@Component
public class LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final LlmCallLogRepository repository;

    public LlmCallRecorder(LlmCallLogRepository repository) {
        this.repository = repository;
    }

    public void record(LlmCallType type, String model, Integer inputTokens, Integer outputTokens,
                       long latencyMs, boolean cacheHit, String errorMessage) {
        try {
            repository.save(LlmCallLog.builder()
                    .callType(type)
                    .model(model)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                    .cacheHit(cacheHit)
                    .errorMessage(errorMessage)
                    .build());
        } catch (RuntimeException e) {
            log.warn("LLM 호출 기록 실패(기능에는 영향 없음): {}", type, e);
        }
    }

    /** Spring AI usage 메타데이터에서 기록 — usage가 없으면(스트리밍 등) 토큰 null. */
    public void recordUsage(LlmCallType type, String model, Usage usage, long latencyMs) {
        Integer input = usage != null ? usage.getPromptTokens() : null;
        Integer output = usage != null ? usage.getCompletionTokens() : null;
        record(type, model, input, output, latencyMs, false, null);
    }

    public void recordCacheHit(LlmCallType type) {
        record(type, null, null, null, 0, true, null);
    }

    /** error_message 센티널 — 취소는 오류도 성공도 아니라 별도 집계된다(대시보드). */
    public static final String CANCELLED = "cancelled";

    /** 클라이언트가 스트림을 중간에 끊음 — 비용은 발생했으므로 기록하되 성공과 구분한다. */
    public void recordCancelled(LlmCallType type, long latencyMs) {
        record(type, null, null, null, latencyMs, false, CANCELLED);
    }

    public void recordError(LlmCallType type, String model, long latencyMs, String message) {
        record(type, model, null, null, latencyMs, false,
                message != null ? message : "unknown error");
    }

    /**
     * 스트리밍 LLM 호출 계측 — 완료·오류·취소 중 정확히 한 번만 기록한다(클라이언트가
     * 스트림을 끊어도 토큰 비용은 발생했으므로 취소도 기록). 스트리밍이라 usage가 없어
     * 토큰은 null, 지연·성공 여부만 남는다.
     */
    public Flux<String> instrumentStream(LlmCallType type, Flux<String> tokens) {
        long start = System.currentTimeMillis();
        AtomicBoolean recorded = new AtomicBoolean();
        return tokens
                .doOnError(e -> {
                    if (recorded.compareAndSet(false, true)) {
                        recordError(type, null, System.currentTimeMillis() - start, e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        record(type, null, null, null,
                                System.currentTimeMillis() - start, false, null);
                    }
                })
                .doOnCancel(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        recordCancelled(type, System.currentTimeMillis() - start);
                    }
                });
    }

    /** 임베딩 응답 계측 — 적재(RagIngester)·질의(RagRetriever)가 같은 추출 로직을 공유한다. */
    public void recordEmbedding(EmbeddingResponse response, long latencyMs) {
        recordUsage(LlmCallType.EMBEDDING,
                response.getMetadata() != null ? response.getMetadata().getModel() : null,
                response.getMetadata() != null ? response.getMetadata().getUsage() : null,
                latencyMs);
    }
}
