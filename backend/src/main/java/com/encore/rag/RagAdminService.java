package com.encore.rag;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** RAG 저장소 운영 조회·관리(E10) — 집계 SQL과 삭제 트랜잭션을 컨트롤러에서 내린다. */
@Service
public class RagAdminService {

    public record ArtistRagStatus(UUID artistMbid, String artistName, long documentCount,
                                  long chunkCount, long explanationCount,
                                  Instant lastEmbedAt, String lastEmbedStatus) {
    }

    public record RagDocumentSummary(long id, String title, String sourceUrl, String docType,
                                     String songKey, long chunkCount, Instant collectedAt) {
    }

    private final JdbcClient jdbcClient;
    private final RagDocumentRepository ragDocumentRepository;
    private final SongExplanationCache explanationCache;

    public RagAdminService(JdbcClient jdbcClient, RagDocumentRepository ragDocumentRepository,
                           SongExplanationCache explanationCache) {
        this.jdbcClient = jdbcClient;
        this.ragDocumentRepository = ragDocumentRepository;
        this.explanationCache = explanationCache;
    }

    /** 수집 대상 아티스트별 임베딩·캐시 상태 + 마지막 EMBED 실행. */
    public List<ArtistRagStatus> status() {
        return jdbcClient.sql("""
                        SELECT a.mbid, a.name,
                               (SELECT count(*) FROM rag_document d WHERE d.artist_mbid = a.mbid) AS docs,
                               (SELECT count(*) FROM rag_chunk c
                                  JOIN rag_document d2 ON d2.id = c.document_id
                                 WHERE d2.artist_mbid = a.mbid) AS chunks,
                               (SELECT count(*) FROM song_explanation s WHERE s.artist_mbid = a.mbid) AS explanations,
                               (SELECT l.finished_at FROM collection_log l
                                 WHERE l.artist_mbid = a.mbid AND l.job_type = 'EMBED'
                                 ORDER BY l.id DESC LIMIT 1) AS last_embed_at,
                               (SELECT l.status FROM collection_log l
                                 WHERE l.artist_mbid = a.mbid AND l.job_type = 'EMBED'
                                 ORDER BY l.id DESC LIMIT 1) AS last_embed_status
                        FROM artist a
                        WHERE a.is_target = true
                        ORDER BY a.name
                        """)
                .query((rs, rowNum) -> new ArtistRagStatus(
                        rs.getObject("mbid", UUID.class),
                        rs.getString("name"),
                        rs.getLong("docs"),
                        rs.getLong("chunks"),
                        rs.getLong("explanations"),
                        rs.getObject("last_embed_at", OffsetDateTime.class) != null
                                ? rs.getObject("last_embed_at", OffsetDateTime.class).toInstant()
                                : null,
                        rs.getString("last_embed_status")))
                .list();
    }

    /** 아티스트의 수집 문서 목록 — 삭제 대상 확인용. */
    public List<RagDocumentSummary> documents(UUID artistMbid) {
        return jdbcClient.sql("""
                        SELECT d.id, d.title, d.source_url, d.doc_type, d.song_key, d.collected_at,
                               (SELECT count(*) FROM rag_chunk c WHERE c.document_id = d.id) AS chunks
                        FROM rag_document d
                        WHERE d.artist_mbid = :artistMbid
                        ORDER BY d.id DESC
                        """)
                .param("artistMbid", artistMbid)
                .query((rs, rowNum) -> new RagDocumentSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("doc_type"),
                        rs.getString("song_key"),
                        rs.getLong("chunks"),
                        rs.getObject("collected_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * 문서 삭제 — 청크는 FK CASCADE, 이 문서를 근거로 한 설명 캐시도 함께 지운다
     * (어느 설명이 인용했는지 추적하지 않으므로 아티스트 단위 무효화). 없으면 false.
     */
    @Transactional
    public boolean deleteDocument(Long id) {
        return ragDocumentRepository.findById(id)
                .map(document -> {
                    UUID artistMbid = document.getArtistMbid();
                    ragDocumentRepository.delete(document);
                    explanationCache.evictArtist(artistMbid);
                    return true;
                })
                .orElse(false);
    }

    public void evictExplanations(UUID artistMbid) {
        explanationCache.evictArtist(artistMbid);
    }
}
