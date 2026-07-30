package com.encore.rag;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * rag_chunk 저장·검색. 벡터 컬럼은 JPA로 매핑하지 않고 JdbcClient로 직접 다룬다 —
 * Hibernate에 vector 타입 확장을 붙이는 것보다 SQL이 짧고, ddl-auto: validate가
 * 엔티티 아닌 테이블은 건드리지 않으므로 마이그레이션과도 충돌하지 않는다.
 */
@Repository
public class RagChunkRepository {

    private final JdbcClient jdbcClient;

    public RagChunkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(long documentId, int chunkIndex, String content, float[] embedding, int tokenCount) {
        jdbcClient.sql("""
                        INSERT INTO rag_chunk (document_id, chunk_index, content, embedding, token_count)
                        VALUES (:documentId, :chunkIndex, :content, CAST(:embedding AS vector), :tokenCount)
                        """)
                .param("documentId", documentId)
                .param("chunkIndex", chunkIndex)
                .param("content", content)
                .param("embedding", toVectorLiteral(embedding))
                .param("tokenCount", tokenCount)
                .update();
    }

    /**
     * 코사인 유사도 top-k + 메타 필터(PRD 8장).
     * 곡 문서는 해당 곡(song_key)만, 앨범·아티스트 문서는 공용 배경으로 포함한다.
     * minScore 미만은 근거가 아니다 — 빈 결과는 "정보 없음"으로 이어진다.
     */
    public List<RetrievedChunk> search(UUID artistMbid, String songKey, float[] queryEmbedding,
                                       int topK, double minScore) {
        return jdbcClient.sql("""
                        SELECT c.content, d.title, d.source_name, d.source_url,
                               1 - (c.embedding <=> CAST(:query AS vector)) AS score
                        FROM rag_chunk c
                        JOIN rag_document d ON d.id = c.document_id
                        WHERE d.artist_mbid = :artistMbid
                          AND (d.song_key = :songKey OR d.doc_type <> 'SONG')
                          AND 1 - (c.embedding <=> CAST(:query AS vector)) >= :minScore
                        ORDER BY c.embedding <=> CAST(:query AS vector)
                        LIMIT :topK
                        """)
                .param("query", toVectorLiteral(queryEmbedding))
                .param("artistMbid", artistMbid)
                .param("songKey", songKey)
                .param("minScore", minScore)
                .param("topK", topK)
                .query((rs, rowNum) -> new RetrievedChunk(
                        rs.getString("content"),
                        rs.getString("title"),
                        rs.getString("source_name"),
                        rs.getString("source_url"),
                        rs.getDouble("score")))
                .list();
    }

    /** Chat(E8)용 아티스트 전체 검색 — 곡 필터 없이 SONG 문서까지 전부 후보다. */
    public List<RetrievedChunk> searchAll(UUID artistMbid, float[] queryEmbedding,
                                          int topK, double minScore) {
        return jdbcClient.sql("""
                        SELECT c.content, d.title, d.source_name, d.source_url,
                               1 - (c.embedding <=> CAST(:query AS vector)) AS score
                        FROM rag_chunk c
                        JOIN rag_document d ON d.id = c.document_id
                        WHERE d.artist_mbid = :artistMbid
                          AND 1 - (c.embedding <=> CAST(:query AS vector)) >= :minScore
                        ORDER BY c.embedding <=> CAST(:query AS vector)
                        LIMIT :topK
                        """)
                .param("query", toVectorLiteral(queryEmbedding))
                .param("artistMbid", artistMbid)
                .param("minScore", minScore)
                .param("topK", topK)
                .query((rs, rowNum) -> new RetrievedChunk(
                        rs.getString("content"),
                        rs.getString("title"),
                        rs.getString("source_name"),
                        rs.getString("source_url"),
                        rs.getDouble("score")))
                .list();
    }

    /** pgvector 입력 리터럴: "[0.1,0.2,...]". */
    static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
