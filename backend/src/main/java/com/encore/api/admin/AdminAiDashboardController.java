package com.encore.api.admin;

import com.encore.common.KoreaTime;
import com.encore.llm.LlmCallLogRepository;
import com.encore.llm.LlmCallLogRepository.TypeStats;
import com.encore.llm.LlmCallType;
import com.encore.llm.LlmCostProperties;
import com.encore.llm.LlmCosts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** 관리자 — AI 사용량 대시보드(E9). 오늘(KST) 기준 호출·지연·캐시·토큰·예상 비용. */
@RestController
@RequestMapping("/api/admin")
public class AdminAiDashboardController {

    private final LlmCallLogRepository llmCallLogRepository;
    private final LlmCostProperties costProperties;

    public AdminAiDashboardController(LlmCallLogRepository llmCallLogRepository,
                                      LlmCostProperties costProperties) {
        this.llmCallLogRepository = llmCallLogRepository;
        this.costProperties = costProperties;
    }

    public record TypeRow(String callType, long calls, Integer avgLatencyMs,
                          long inputTokens, long outputTokens, long cacheHits, long errors) {
    }

    public record AiDashboardResponse(
            long totalCalls,
            /** 캐시 가능 유형(EXPLANATION)의 히트 비율이 아니라 전체 호출 대비 히트 수 비율 */
            BigDecimal cacheHitRate,
            long inputTokens,
            long outputTokens,
            long embeddingTokens,
            BigDecimal estimatedCostUsd,
            List<TypeRow> byType
    ) {
    }

    @GetMapping("/ai-dashboard")
    public AiDashboardResponse dashboard() {
        Instant since = KoreaTime.today().atStartOfDay(KoreaTime.ZONE).toInstant();
        List<TypeStats> stats = llmCallLogRepository.statsSince(since);

        long totalCalls = 0;
        long cacheHits = 0;
        long chatInput = 0;
        long chatOutput = 0;
        long embeddingTokens = 0;
        for (TypeStats row : stats) {
            totalCalls += row.getCalls();
            cacheHits += row.getCacheHits();
            if (LlmCallType.EMBEDDING.name().equals(row.getCallType())) {
                embeddingTokens += row.getInputTokens();
            } else {
                chatInput += row.getInputTokens();
                chatOutput += row.getOutputTokens();
            }
        }
        BigDecimal hitRate = totalCalls == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf((double) cacheHits / totalCalls).setScale(4, RoundingMode.HALF_UP);
        BigDecimal cost = BigDecimal.valueOf(
                        LlmCosts.estimateUsd(chatInput, chatOutput, embeddingTokens, costProperties))
                .setScale(4, RoundingMode.HALF_UP);

        return new AiDashboardResponse(
                totalCalls, hitRate, chatInput, chatOutput, embeddingTokens, cost,
                stats.stream()
                        .map(row -> new TypeRow(
                                row.getCallType(),
                                row.getCalls(),
                                row.getAvgLatencyMs() != null
                                        ? (int) Math.round(row.getAvgLatencyMs())
                                        : null,
                                row.getInputTokens(),
                                row.getOutputTokens(),
                                row.getCacheHits(),
                                row.getErrors()))
                        .toList());
    }
}
