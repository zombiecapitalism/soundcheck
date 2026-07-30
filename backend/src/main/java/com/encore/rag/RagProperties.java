package com.encore.rag;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 파라미터 — application.yml의 encore.rag.*로 조정한다.
 * 답변 품질 평가(PRD 8장)를 돌며 튜닝할 값들이라 코드에 굳히지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "encore.rag")
public record RagProperties(

        /** 유사도 검색 상위 청크 수. */
        @DefaultValue("5") @Positive int topK,

        /** 코사인 유사도 하한 — 이보다 낮은 청크는 근거로 쓰지 않는다("정보 없음" 판정). */
        @DefaultValue("0.35") @DecimalMin("0.0") @DecimalMax("1.0") double minScore,

        /** 청크 목표 크기(토큰). PRD 기준 500~800 범위 안. */
        @DefaultValue("650") @Positive int chunkTargetTokens,

        /** 문서당 임베딩할 본문 상한(문자). 임베딩 비용과 저품질 꼬리 문단을 제어한다. */
        @DefaultValue("40000") @Positive int maxContentChars,

        /** 수집 배치에서 아티스트당 곡 문서 상한. */
        @DefaultValue("30") @Positive int maxSongsPerArtist,

        /**
         * Wikipedia User-Agent에 붙일 연락처(이메일/URL) — Wikimedia 정책 권고 사항.
         * 개인 연락처를 코드에 커밋하지 않도록 환경변수(RAG_CONTACT)로만 주입한다. 비우면 생략.
         */
        @DefaultValue("") String userAgentContact
) {
}
