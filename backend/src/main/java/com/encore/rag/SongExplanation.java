package com.encore.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 생성된 곡 설명 캐시 — 조회마다 LLM을 부르지 않기 위한 저장본.
 * 원천은 항상 rag_chunk 검색 + 생성이며, 새 문서 수집 시 아티스트 단위로 무효화된다.
 * 쓰기는 SongExplanationCache의 upsert SQL이 담당한다 — 이 엔티티는 읽기 전용 매핑.
 */
@Entity
@Table(name = "song_explanation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SongExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "artist_mbid", nullable = false)
    private UUID artistMbid;

    @Column(name = "song_key", nullable = false, length = 300)
    private String songKey;

    @Column(name = "content", nullable = false)
    private String content;

    /** [{name, url, title}] 직렬화 — 응답의 출처 목록 그대로. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", nullable = false, columnDefinition = "jsonb")
    private String sourcesJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
