package com.encore.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

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

    public void recordError(LlmCallType type, String model, long latencyMs, String message) {
        record(type, model, null, null, latencyMs, false,
                message != null ? message : "unknown error");
    }
}
