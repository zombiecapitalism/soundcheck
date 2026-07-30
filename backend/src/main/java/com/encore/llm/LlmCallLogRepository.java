package com.encore.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {

    /** 대시보드 집계 — 기간 내 call_type별 호출 수·지연·토큰·캐시 히트. */
    interface TypeStats {
        String getCallType();
        Long getCalls();
        Double getAvgLatencyMs();
        Long getInputTokens();
        Long getOutputTokens();
        Long getCacheHits();
        Long getErrors();
    }

    @Query(value = """
            select l.call_type as "callType",
                   count(*) as "calls",
                   avg(l.latency_ms) as "avgLatencyMs",
                   coalesce(sum(l.input_tokens), 0) as "inputTokens",
                   coalesce(sum(l.output_tokens), 0) as "outputTokens",
                   count(*) filter (where l.cache_hit) as "cacheHits",
                   count(*) filter (where l.error_message is not null) as "errors"
            from llm_call_log l
            where l.created_at >= :since
            group by l.call_type
            order by 2 desc
            """, nativeQuery = true)
    List<TypeStats> statsSince(@Param("since") Instant since);
}
