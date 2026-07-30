package com.encore.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** LLM 호출 이력(E9) — 호출마다 1행. 토큰은 제공자 메타데이터가 없으면 null. */
@Entity
@Table(name = "llm_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 30)
    private LlmCallType callType;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private LlmCallLog(LlmCallType callType, String model, Integer inputTokens, Integer outputTokens,
                       int latencyMs, boolean cacheHit, String errorMessage) {
        this.callType = callType;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.cacheHit = cacheHit;
        this.errorMessage = errorMessage;
    }
}
