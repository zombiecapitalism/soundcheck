package com.encore.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {

    /** 대시보드 집계 — 기간 내 call_type별 호출 수·지연·토큰·캐시 히트·취소·오류. */
    interface TypeStats {
        String getCallType();
        Long getCalls();
        Double getAvgLatencyMs();
        Long getInputTokens();
        Long getOutputTokens();
        Long getCacheHits();
        Long getCancelled();
        Long getErrors();
    }

    /** 취소('cancelled' 센티널)는 오류가 아니다 — 오류 수에서 빼고 별도 집계한다. */
    @Query(value = """
            select l.call_type as "callType",
                   count(*) as "calls",
                   avg(l.latency_ms) as "avgLatencyMs",
                   coalesce(sum(l.input_tokens), 0) as "inputTokens",
                   coalesce(sum(l.output_tokens), 0) as "outputTokens",
                   count(*) filter (where l.cache_hit) as "cacheHits",
                   count(*) filter (where l.error_message = 'cancelled') as "cancelled",
                   count(*) filter (where l.error_message is not null
                                      and l.error_message <> 'cancelled') as "errors"
            from llm_call_log l
            where l.created_at >= :since
            group by l.call_type
            order by 2 desc
            """, nativeQuery = true)
    List<TypeStats> statsSince(@Param("since") Instant since);
}
