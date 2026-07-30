package com.encore.rag;

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

import java.time.Instant;
import java.util.UUID;

/**
 * RAG 원천 문서 메타데이터. 본문은 청크(rag_chunk)에만 저장한다.
 * 출처명·URL은 NOT NULL — 출처 없는 문서는 존재할 수 없다(CLAUDE.md 규칙 8).
 * 아티스트 참조는 CollectionLog처럼 연관관계가 아닌 UUID 값이다 — RAG는 아티스트
 * 객체가 필요 없고, 도메인 결합을 만들 이유가 없다.
 */
@Entity
@Table(name = "rag_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "artist_mbid", nullable = false)
    private UUID artistMbid;

    /** 곡 단위 문서면 채운다(정규화 키). 앨범·아티스트 문서는 null. */
    @Column(name = "song_key", length = 300)
    private String songKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocType docType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "source_name", nullable = false, length = 200)
    private String sourceName;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Builder
    private RagDocument(UUID artistMbid, String songKey, DocType docType,
                        String title, String sourceName, String sourceUrl) {
        this.artistMbid = artistMbid;
        this.songKey = songKey;
        this.docType = docType;
        this.title = title;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.collectedAt = Instant.now();
    }
}
