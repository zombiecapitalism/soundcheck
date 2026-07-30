package com.encore.rag;

import com.encore.rag.SongExplanationService.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 곡 설명 캐시 접근 — 직렬화와 트랜잭션 경계를 서비스에서 분리한다.
 * 저장은 ON CONFLICT DO NOTHING upsert다: 같은 곡을 동시에 두 요청이 생성해도
 * 예외 없이 첫 저장만 남는다(예외로 잡으면 진행 중인 트랜잭션이 rollback-only로 오염된다).
 */
@Component
public class SongExplanationCache {

    private final SongExplanationRepository repository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public SongExplanationCache(SongExplanationRepository repository, JdbcClient jdbcClient,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public record Cached(List<Source> sources, String content) {
    }

    @Transactional(readOnly = true)
    public Optional<Cached> find(UUID artistMbid, String songKey) {
        return repository.findByArtistMbidAndSongKey(artistMbid, songKey)
                .map(e -> new Cached(
                        objectMapper.readValue(e.getSourcesJson(), new TypeReference<List<Source>>() {
                        }),
                        e.getContent()));
    }

    /** 생성 완료 시 저장 — 스트림 완료 콜백(리액터 스레드, 트랜잭션 없음)에서 불리므로 여기서 연다. */
    @Transactional
    public void save(UUID artistMbid, String songKey, List<Source> sources, String content) {
        jdbcClient.sql("""
                        INSERT INTO song_explanation (artist_mbid, song_key, content, sources, generated_at)
                        VALUES (:artistMbid, :songKey, :content, CAST(:sources AS jsonb), now())
                        ON CONFLICT (artist_mbid, song_key) DO NOTHING
                        """)
                .param("artistMbid", artistMbid)
                .param("songKey", songKey)
                .param("content", content)
                .param("sources", objectMapper.writeValueAsString(sources))
                .update();
    }

    /** 새 문서가 수집되면 낡은 설명이 남지 않게 아티스트 단위로 지운다. */
    @Transactional
    public void evictArtist(UUID artistMbid) {
        repository.deleteByArtistMbid(artistMbid);
    }
}
