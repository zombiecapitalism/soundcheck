package com.encore.api.admin;

import com.encore.api.ApiNotFoundException;
import com.encore.rag.RagDocumentRepository;
import com.encore.rag.SongExplanationCache;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 관리자 — RAG 저장소 상태·문서 관리·설명 캐시 관리(E10). */
@RestController
@RequestMapping("/api/admin/rag")
public class AdminRagController {

    private final JdbcClient jdbcClient;
    private final RagDocumentRepository ragDocumentRepository;
    private final SongExplanationCache explanationCache;

    public AdminRagController(JdbcClient jdbcClient, RagDocumentRepository ragDocumentRepository,
                              SongExplanationCache explanationCache) {
        this.jdbcClient = jdbcClient;
        this.ragDocumentRepository = ragDocumentRepository;
        this.explanationCache = explanationCache;
    }

    public record ArtistRagStatus(UUID artistMbid, String artistName, long documentCount,
                                  long chunkCount, long explanationCount,
                                  Instant lastEmbedAt, String lastEmbedStatus) {
    }

    /** 수집 대상 아티스트별 임베딩·캐시 상태 + 마지막 EMBED 실행. */
    @GetMapping("/status")
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

    public record RagDocumentSummary(long id, String title, String sourceUrl, String docType,
                                     String songKey, long chunkCount, Instant collectedAt) {
    }

    /** 아티스트의 수집 문서 목록 — 삭제 대상 확인용. */
    @GetMapping("/documents")
    public List<RagDocumentSummary> documents(@RequestParam UUID artistMbid) {
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

    /** 문서 삭제 — 청크는 FK CASCADE, 이 문서를 근거로 한 설명 캐시도 함께 지운다. */
    @DeleteMapping("/documents/{id}")
    @Transactional
    public void deleteDocument(@PathVariable Long id) {
        var document = ragDocumentRepository.findById(id)
                .orElseThrow(() -> new ApiNotFoundException("존재하지 않는 문서입니다: " + id));
        UUID artistMbid = document.getArtistMbid();
        ragDocumentRepository.delete(document);
        // 어느 설명이 이 문서를 인용했는지 추적하지 않으므로 아티스트 단위로 무효화한다
        explanationCache.evictArtist(artistMbid);
    }

    /** 설명 캐시 아티스트 단위 무효화 — 다음 조회 때 재생성된다. */
    @DeleteMapping("/cache/{artistMbid}")
    public void evictCache(@PathVariable UUID artistMbid) {
        explanationCache.evictArtist(artistMbid);
    }
}
